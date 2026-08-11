package com.lingshu.feature.offlinetts.data

import com.lingshu.core.common.log.LingShuLog
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/**
 * ChatTTS BPE Tokenizer
 *
 * 从 HuggingFace 格式的 tokenizer.json 加载 vocab + merges，
 * 对输入文本执行 BPE 编码，返回 token ID 数组。
 *
 * 格式参考：https://huggingface.co/docs/tokenizers
 *
 * tokenizer.json 结构：
 * {
 *   "model": {
 *     "type": "BPE",
 *     "vocab": { "token_str": id, ... },
 *     "merges": ["token_a token_b", ...]
 *   },
 *   "added_tokens": [{ "id": N, "content": "<s>", ... }, ...]
 * }
 */
class ChatTtsTokenizer private constructor(
    private val vocab: Map<String, Long>,
    private val merges: List<Pair<String, String>>,
    private val addedTokens: Map<String, Long>
) {
    private val moduleTag = "ChatTtsTokenizer"

    val vocabSize: Long
        get() = (vocab.size + addedTokens.size).toLong()

    // BPE merge rank: merge string -> rank (lower = higher priority)
    private val mergeRanks: Map<String, Int> = merges.withIndex()
        .associate { (index, pair) -> "${pair.first} ${pair.second}" to index }

    /**
     * 将文本编码为 token ID 数组
     */
    fun encode(text: String): LongArray {
        if (text.isEmpty()) return LongArray(0)

        // 1. 预分词：按空格和标点拆分
        val words = preTokenize(text)
        val result = mutableListOf<Long>()

        for (word in words) {
            // 2. 对每个 word 执行 BPE
            val subTokens = bpeEncode(word)
            // 3. 查找每个 sub-token 的 ID
            for (subToken in subTokens) {
                val id = vocab[subToken] ?: addedTokens[subToken]
                if (id != null) {
                    result.add(id)
                } else {
                    // 未知 token，尝试用字符级 fallback
                    LingShuLog.v(moduleTag, "unknown token: '$subToken' (word='$word')")
                    for (ch in subToken) {
                        val charToken = ch.toString()
                        val charId = vocab[charToken] ?: vocab["##$charToken"]
                        if (charId != null) {
                            result.add(charId)
                        }
                    }
                }
            }
        }

        return result.toLongArray()
    }

    /**
     * 将 token ID 数组解码回文本
     */
    fun decode(tokenIds: LongArray): String {
        val inverseVocab = vocab.entries.associate { (k, v) -> v to k }
        val sb = StringBuilder()
        for (id in tokenIds) {
            val token = inverseVocab[id] ?: addedTokens.entries.find { it.value == id }?.key
            if (token != null) {
                // 去除 BPE 的 ## 前缀
                val cleaned = token.replace("##", "")
                sb.append(cleaned)
            }
        }
        return sb.toString()
    }

    // ===================== BPE 算法 =====================

    /**
     * 对单个 word 执行 BPE 编码
     */
    private fun bpeEncode(word: String): List<String> {
        if (word.isEmpty()) return emptyList()

        // 初始：每个字符是一个 token
        var tokens = word.toCharArray().map { it.toString() }.toMutableList()
        if (tokens.size <= 1) return tokens

        // 反复合并，直到无法合并
        while (true) {
            var bestPair: Pair<String, String>? = null
            var bestRank = Int.MAX_VALUE

            for (i in 0 until tokens.size - 1) {
                val pair = "${tokens[i]} ${tokens[i + 1]}"
                val rank = mergeRanks[pair]
                if (rank != null && rank < bestRank) {
                    bestRank = rank
                    bestPair = tokens[i] to tokens[i + 1]
                }
            }

            if (bestPair == null) break

            // 合并最佳 pair
            val merged = mutableListOf<String>()
            var i = 0
            while (i < tokens.size) {
                if (i < tokens.size - 1 && tokens[i] == bestPair.first && tokens[i + 1] == bestPair.second) {
                    merged.add(tokens[i] + tokens[i + 1])
                    i += 2
                } else {
                    merged.add(tokens[i])
                    i++
                }
            }
            tokens = merged.toMutableList()
        }

        return tokens
    }

    /**
     * 预分词：按空格、标点拆分为 word
     */
    private fun preTokenize(text: String): List<String> {
        val words = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            if (ch.isWhitespace()) {
                if (sb.isNotEmpty()) {
                    words.add(sb.toString())
                    sb.clear()
                }
                // 空格本身也作为一个 token（GPT 风格用 Ġ 表示空格前缀）
                words.add(" ")
            } else if (isPunctuation(ch)) {
                if (sb.isNotEmpty()) {
                    words.add(sb.toString())
                    sb.clear()
                }
                words.add(ch.toString())
            } else {
                sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) words.add(sb.toString())
        return words
    }

    private fun isPunctuation(ch: Char): Boolean {
        val punctuation = charArrayOf(
            '。', '，', '！', '？', '、', '；', '：',
            '\u201C', '\u201D', '\u2018', '\u2019',
            '（', '）', '【', '】', '《', '》', '〈', '〉',
            '…', '—',
            ',', '.', '!', '?', ';', ':', '"', '\'', '(', ')', '[', ']', '<', '>'
        )
        return ch in punctuation ||
               ch.code in 0x2000..0x206F ||
               ch.code in 0x3000..0x303F
    }

    companion object {
        private const val TAG = "ChatTtsTokenizer"

        /**
         * 从 tokenizer.json 文件加载
         */
        fun fromJson(file: File): ChatTtsTokenizer {
            val jsonStr = file.readText()
            val root = JSONObject(jsonStr)

            // 解析 model 部分
            val model = root.optJSONObject("model")
            val vocab = mutableMapOf<String, Long>()
            val merges = mutableListOf<Pair<String, String>>()

            if (model != null) {
                // vocab
                val vocabJson = model.optJSONObject("vocab")
                if (vocabJson != null) {
                    val keys = vocabJson.keys()
                    while (keys.hasNext()) {
                        val token = keys.next()
                        vocab[token] = vocabJson.getLong(token)
                    }
                }

                // merges
                val mergesArray = model.optJSONArray("merges")
                if (mergesArray != null) {
                    for (i in 0 until mergesArray.length()) {
                        val mergeStr = mergesArray.getString(i)
                        val parts = mergeStr.split(" ", limit = 2)
                        if (parts.size == 2) {
                            merges.add(parts[0] to parts[1])
                        }
                    }
                }
            }

            // 解析 added_tokens
            val addedTokens = mutableMapOf<String, Long>()
            val addedArray = root.optJSONArray("added_tokens")
            if (addedArray != null) {
                for (i in 0 until addedArray.length()) {
                    val obj = addedArray.getJSONObject(i)
                    val content = obj.getString("content")
                    val id = obj.getLong("id")
                    addedTokens[content] = id
                }
            }

            LingShuLog.i(TAG, "Tokenizer loaded | vocab=${vocab.size} | merges=${merges.size} | " +
                    "addedTokens=${addedTokens.size} | file=${file.name}")

            return ChatTtsTokenizer(vocab, merges, addedTokens)
        }
    }
}
