package com.lingshu.agent.feature.knowledge

import android.util.Log
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文档处理流水线
 *
 * RAG 入库的前置处理器，负责：
 * 1. 格式解析：支持 PDF / TXT / Markdown（接口抽象，可扩展 Word、HTML、EPUB 等）
 * 2. 数据清洗：去重、去多余空白、去除页眉页脚、OCR纠错占位
 * 3. 章节切分：优先按 Markdown 标题、PDF 书签等「自然段落」切分
 * 4. 滑动窗口切片：当单章节过长时，按 512token + 64overlap 滑动窗口切片
 *
 * 切分策略说明：
 * - 先按「章节/段落」粗切 → 再按「token数 + overlap」细切
 * - 这样做的好处：避免在句子中间、段落中间硬切，保持语义连贯性
 * - overlap=64token 保证相邻切片有足够上下文衔接，提升召回率
 *
 * Token 估算：
 * - 没有 Tokenizer 时使用简单估算：中文 1字 ≈ 1.5 token，英文 1词 ≈ 1.3 token
 * - 后续可接入 HuggingFace Tokenizer 或 SentencePiece 获得精确值
 */
@Singleton
class DocumentProcessor @Inject constructor() {

    companion object {
        private const val TAG = "DocumentProcessor"

        /** 单切片目标 token 数 */
        const val TARGET_CHUNK_TOKENS = 512

        /** 相邻切片重叠 token 数（避免语义断裂） */
        const val OVERLAP_TOKENS = 64

        /** 最小有效切片字符数（过滤纯噪音，如 < 20 字符的切片直接丢弃） */
        const val MIN_CHUNK_CHARS = 20

        /** 支持的文件扩展名 */
        val SUPPORTED_EXTENSIONS = setOf("txt", "md", "markdown", "pdf")
    }

    // ==================== 对外主入口 ====================

    /**
     * 处理单个文件 → 返回切片列表
     *
     * @param file 待处理文件（需确保已授权读取）
     * @return 切片列表，如果解析失败返回 emptyList
     */
    fun processFile(file: File): List<DocumentChunk> {
        if (!file.exists() || !file.canRead()) {
            Log.w(TAG, "文件不存在或不可读: ${file.absolutePath}")
            return emptyList()
        }
        val ext = file.extension.lowercase()
        if (ext !in SUPPORTED_EXTENSIONS) {
            Log.w(TAG, "不支持的文件类型: .$ext")
            return emptyList()
        }

        return try {
            // 1. 按格式解析出纯文本 + 元数据
            val parsed = when (ext) {
                "txt" -> parseTxt(file)
                "md", "markdown" -> parseMarkdown(file)
                "pdf" -> parsePdf(file)
                else -> ParsedDocument(file.name, "", emptyList())
            }

            // 2. 数据清洗
            val cleaned = cleanDocument(parsed)

            // 3. 切片（章节 + 滑动窗口）
            val rawText = cleaned.fullText
            val sections = cleaned.sections.ifEmpty {
                // 没有章节信息，整段作为一个「虚拟章节」
                listOf(DocumentSection("body", 0, rawText.length, rawText))
            }

            val chunks = mutableListOf<DocumentChunk>()
            val docId = generateDocId(file)
            var chunkIndex = 0

            for (section in sections) {
                // 每个章节内按 token 窗口切片
                val sectionChunks = chunkSection(
                    sectionText = section.text,
                    sectionTitle = section.title
                )
                for ((offset, chunkText) in sectionChunks) {
                    val tokenCount = estimateTokens(chunkText)
                    if (chunkText.trim().length < MIN_CHUNK_CHARS) continue
                    chunks += DocumentChunk(
                        id = "${docId}_c${chunkIndex}",
                        docId = docId,
                        content = chunkText,
                        embedding = FloatArray(0), // 在 VectorStore.addDocuments 时填充
                        metadata = mapOf(
                            "filename" to file.name,
                            "extension" to ext,
                            "section" to section.title,
                            "section_offset" to offset.toString(),
                            "file_size" to file.length().toString(),
                            "last_modified" to file.lastModified().toString()
                        ),
                        chunkIndex = chunkIndex,
                        tokenCount = tokenCount
                    )
                    chunkIndex++
                }
            }
            Log.d(TAG, "文件处理完成：${file.name}，生成 ${chunks.size} 个切片")
            chunks
        } catch (e: Exception) {
            Log.e(TAG, "处理文件失败: ${file.name}, ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 处理纯文本字符串（用户直接粘贴内容、聊天记录等）
     * @param text 文本内容
     * @param docId 文档ID（外部指定，避免重复）
     * @param source 来源标签（metadata 写入）
     */
    fun processText(
        text: String,
        docId: String,
        source: String = "plain_text"
    ): List<DocumentChunk> {
        if (text.isBlank()) return emptyList()

        val sections = splitByParagraphs(text)
        val chunks = mutableListOf<DocumentChunk>()
        var chunkIndex = 0

        for (section in sections) {
            val sectionChunks = chunkSection(section.text, section.title)
            for ((offset, chunkText) in sectionChunks) {
                if (chunkText.trim().length < MIN_CHUNK_CHARS) continue
                chunks += DocumentChunk(
                    id = "${docId}_c${chunkIndex}",
                    docId = docId,
                    content = chunkText,
                    embedding = FloatArray(0),
                    metadata = mapOf(
                        "source" to source,
                        "section" to section.title,
                        "section_offset" to offset.toString()
                    ),
                    chunkIndex = chunkIndex,
                    tokenCount = estimateTokens(chunkText)
                )
                chunkIndex++
            }
        }
        Log.d(TAG, "纯文本处理完成：${chunks.size} 个切片，docId=$docId")
        return chunks
    }

    // ==================== 格式解析器 ====================

    /**
     * 纯文本解析
     */
    private fun parseTxt(file: File): ParsedDocument {
        val text = FileInputStream(file).bufferedReader().use { it.readText() }
        // TXT 没有结构，直接按空行分段
        val sections = splitByParagraphs(text)
        return ParsedDocument(file.name, text, sections)
    }

    /**
     * Markdown 解析
     *
     * 识别 #、##、### 等标题作为章节边界，
     * 提取每个标题 + 其后续正文作为一个章节。
     */
    private fun parseMarkdown(file: File): ParsedDocument {
        val text = FileInputStream(file).bufferedReader().use { it.readText() }
        val sections = mutableListOf<DocumentSection>()
        val lines = text.lines()

        var currentTitle = "前言"
        val currentContent = StringBuilder()
        var currentStart = 0

        for ((idx, line) in lines.withIndex()) {
            val headerMatch = Regex("""^(#{1,6})\s+(.+)$""").find(line)
            if (headerMatch != null) {
                // 先把上一段（如果有内容）落盘
                if (currentContent.isNotBlank()) {
                    sections += DocumentSection(
                        title = currentTitle,
                        start = currentStart,
                        end = idx,
                        text = currentContent.toString().trim()
                    )
                }
                currentTitle = headerMatch.groupValues[2].trim()
                currentContent.clear()
                currentContent.appendLine(line)
                currentStart = idx
            } else {
                currentContent.appendLine(line)
            }
        }
        // 最后一段
        if (currentContent.isNotBlank()) {
            sections += DocumentSection(
                title = currentTitle,
                start = currentStart,
                end = lines.size,
                text = currentContent.toString().trim()
            )
        }
        return ParsedDocument(file.name, text, sections)
    }

    /**
     * PDF 解析（占位实现）
     *
     * 注意：
     * - 真正的 PDF 文本提取需要引入依赖，如 PdfBox-Android、iTextG 等
     * - 此处为了零依赖占位实现，仅读取文件字节大小并给出提示
     * - 实际项目中，建议在子协程中调用 iTextG/PdfBox 做真实提取
     */
    private fun parsePdf(file: File): ParsedDocument {
        Log.w(TAG, "PDF解析使用占位实现，请接入 PdfBox-Android 或 iTextG 依赖")
        // 占位：把 PDF 当二进制读取前N字节尝试转文本（仅用于开发阶段）
        val rawBytes = FileInputStream(file).use { it.readBytes() }
        // 简单提取可打印 ASCII 字符作为文本（粗糙但能看到一些结构）
        val roughText = rawBytes
            .map { if (it in 0x20..0x7E || it == 0x0A.toByte()) it.toChar() else ' ' }
            .joinToString("")
            .replace(Regex("""\s{4,}"""), "\n")

        val sections = splitByParagraphs(roughText)
        return ParsedDocument(file.name, roughText, sections)
    }

    // ==================== 数据清洗 ====================

    /**
     * 文档清洗流水线
     * 1. 去除 BOM / 不可见控制字符
     * 2. 合并连续空行（超过2个换行 → 2个）
     * 3. 去除行首尾多余空格
     * 4. 行内容去重（连续两行完全相同 → 只保留一行，解决页眉页脚重复）
     */
    private fun cleanDocument(doc: ParsedDocument): ParsedDocument {
        val cleanedSections = doc.sections.map { section ->
            val cleaned = cleanText(section.text)
            section.copy(text = cleaned)
        }.filter { it.text.isNotBlank() }
        val cleanedFull = cleanText(doc.fullText)
        return doc.copy(fullText = cleanedFull, sections = cleanedSections)
    }

    private fun cleanText(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw
        // 1. 去除 BOM 和控制字符（保留换行、制表、回车）
        s = s.replace(Regex("""[\uFEFF\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]"""), "")
        // 2. 去除每行首尾空格
        s = s.lineSequence().map { it.trimEnd() }.joinToString("\n")
        // 3. 合并连续空白行（3+空行 → 2空行）
        s = s.replace(Regex("""\n{3,}"""), "\n\n")
        // 4. 去除连续完全重复的行（常见于页眉页脚）
        val lines = s.lines().toMutableList()
        if (lines.size >= 3) {
            var i = 1
            while (i < lines.size - 1) {
                if (lines[i].isNotBlank() &&
                    lines[i] == lines[i - 1] &&
                    lines[i] == lines[i + 1]
                ) {
                    // 连续3行相同 → 删掉第i行（保留1行）
                    lines.removeAt(i)
                } else {
                    i++
                }
            }
        }
        s = lines.joinToString("\n")
        // 5. 去除首尾空行
        s = s.trim()
        return s
    }

    // ==================== 章节分段 ====================

    /**
     * 通用空行分段：两段之间有 >= 1 个空行视为新段落
     */
    private fun splitByParagraphs(text: String): List<DocumentSection> {
        if (text.isBlank()) return emptyList()
        val paragraphs = text.split(Regex("""\n\s*\n"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        var runningOffset = 0
        return paragraphs.mapIndexed { i, para ->
            val start = runningOffset
            runningOffset += para.length + 2
            DocumentSection(
                title = "段落${i + 1}",
                start = start,
                end = start + para.length,
                text = para
            )
        }
    }

    // ==================== 滑动窗口切片 ====================

    /**
     * 章节内滑动窗口切片
     *
     * 策略：
     * 1. 先按句子（。！？.!?）粗切为句块
     * 2. 累加句块，直到 token 数接近 TARGET_CHUNK_TOKENS
     * 3. 下一个切片开头回退 OVERLAP_TOKENS，保证上下文重叠
     *
     * @return 列表 Pair(切片在section内字符偏移, 切片文本)
     */
    private fun chunkSection(
        sectionText: String,
        sectionTitle: String
    ): List<Pair<Int, String>> {
        if (sectionText.isBlank()) return emptyList()

        val totalTokens = estimateTokens(sectionText)
        // 章节本身比窗口小 → 直接返回整段
        if (totalTokens <= TARGET_CHUNK_TOKENS) {
            return listOf(0 to prependSectionTitle(sectionTitle, sectionText))
        }

        // 按句子切分
        val sentences = splitSentences(sectionText)
        if (sentences.isEmpty()) {
            // 没有句号（如英文代码、表格），直接按硬字符数切片
            return hardChunkByChars(sectionText, sectionTitle)
        }

        val result = mutableListOf<Pair<Int, String>>()
        val sentenceTokenCounts = sentences.map { estimateTokens(it.second) }
        val sentenceOffsets = sentences.map { it.first }

        var i = 0
        while (i < sentences.size) {
            // 累加句子直到接近 TARGET
            var chunkTokens = 0
            var j = i
            while (j < sentences.size && chunkTokens < TARGET_CHUNK_TOKENS) {
                chunkTokens += sentenceTokenCounts[j]
                j++
            }
            // 切片 = sentences[i..j-1]
            val chunkStrStart = sentenceOffsets[i]
            val chunkStrEnd = if (j - 1 < sentences.size) {
                // sentences[j-1] 的开始 + 其长度
                val sStart = sentenceOffsets[j - 1]
                val nextStart = if (j < sentences.size) sentenceOffsets[j] else sectionText.length
                sStart + (nextStart - sStart)
            } else sectionText.length
            val chunkText = sectionText.substring(chunkStrStart, chunkStrEnd.coerceAtMost(sectionText.length))
            val withTitle = prependSectionTitle(sectionTitle, chunkText)
            result += (chunkStrStart to withTitle)

            // 下一步：回退 overlap 对应的句子数
            var overlapTokens = 0
            var k = j - 1
            while (k > i && overlapTokens < OVERLAP_TOKENS) {
                overlapTokens += sentenceTokenCounts[k]
                k--
            }
            val nextStart = if (overlapTokens >= OVERLAP_TOKENS) (k + 1) else (j - 1)
            if (nextStart <= i) {
                // 避免死循环：至少推进1句
                i++
            } else {
                i = nextStart
            }
            if (j >= sentences.size) break
        }
        return result
    }

    /**
     * 硬切片：无句子标记时，按字符数近似切片
     * 字符到 token 的比例：混合中英文，粗估 1.4 字符 ≈ 1 token
     */
    private fun hardChunkByChars(
        sectionText: String,
        sectionTitle: String
    ): List<Pair<Int, String>> {
        val result = mutableListOf<Pair<Int, String>>()
        val targetChars = (TARGET_CHUNK_TOKENS * 1.4).toInt()
        val overlapChars = (OVERLAP_TOKENS * 1.4).toInt()
        var pos = 0
        while (pos < sectionText.length) {
            val end = (pos + targetChars).coerceAtMost(sectionText.length)
            val chunk = sectionText.substring(pos, end)
            result += (pos to prependSectionTitle(sectionTitle, chunk))
            if (end >= sectionText.length) break
            pos = (end - overlapChars).coerceAtLeast(pos + 1)
        }
        return result
    }

    /**
     * 切分为句子列表：返回 Pair(字符偏移, 句子文本)
     * 中英文句号、感叹号、问号、换行都作为切分点
     */
    private fun splitSentences(text: String): List<Pair<Int, String>> {
        if (text.isBlank()) return emptyList()
        val result = mutableListOf<Pair<Int, String>>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '。' || c == '！' || c == '？' ||
                c == '.' || c == '!' || c == '?' ||
                c == '\n'
            ) {
                // 吃掉连续标点（"！？"不切为两句）
                while (i < text.length && (text[i] in "。！？.!?\n" || text[i] == ' ')) i++
                val end = i
                val sentence = text.substring(start, end).trim()
                if (sentence.isNotBlank()) {
                    result += (start to sentence)
                }
                start = end
            } else {
                i++
            }
        }
        // 尾部
        if (start < text.length) {
            val tail = text.substring(start).trim()
            if (tail.isNotBlank()) result += (start to tail)
        }
        return result
    }

    /** 在切片前追加章节标题（提升召回时的上下文定位能力） */
    private fun prependSectionTitle(title: String, body: String): String {
        if (title.isBlank() || title.startsWith("段落") || title == "body" || title == "前言") {
            return body
        }
        // 标题长度检查：如果切片已经包含标题就不再重复
        if (body.startsWith("#") && body.contains(title)) return body
        return "【$title】\n$body"
    }

    // ==================== Token 估算 ====================

    /**
     * Token 数量估算（简化版）
     *
     * 经验公式：
     * - 中文字符 × 1.5
     * - 英文单词（按空格分词）× 1.3
     * - 数字、标点 × 0.5
     * 结果向上取整。
     */
    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        var cn = 0
        var en = 0
        var other = 0
        // 英文按词数统计（粗略：空白分隔）
        val enWords = text.split(Regex("""[\s,.;:!?()\[\]{}'"/\\]+"""))
            .filter { it.isNotEmpty() && it.all { c -> c.code < 0x80 && !c.isDigit() } }
        en = enWords.size
        for (c in text) {
            when {
                c.code in 0x4E00..0x9FFF ||
                        c.code in 0x3400..0x4DBF ||
                        c.code in 0x3040..0x30FF ||
                        c.code in 0xAC00..0xD7AF -> cn++
                c.isDigit() -> other++
                c.isLetterOrDigit().not() && c.isWhitespace().not() -> other++
            }
        }
        val total = cn * 1.5 + en * 1.3 + other * 0.5
        return total.toInt().coerceAtLeast(1)
    }

    // ==================== 工具 ====================

    /** 生成稳定文档ID：基于文件名+大小+修改时间的哈希 */
    private fun generateDocId(file: File): String {
        val raw = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        return "doc_${raw.hashCode().toUInt().toString(16)}"
    }

    /**
     * 检查扩展名是否支持
     */
    fun isSupported(file: File): Boolean {
        return file.extension.lowercase() in SUPPORTED_EXTENSIONS
    }
}

// ==================== 内部数据结构 ====================

/** 解析后的文档结构 */
private data class ParsedDocument(
    val fileName: String,
    val fullText: String,
    val sections: List<DocumentSection>
)

/** 一个章节/段落 */
private data class DocumentSection(
    val title: String,
    val start: Int,
    val end: Int,
    val text: String
)
