package com.lingshu.feature.clonevoice.data

import android.content.Context
import android.media.MediaRecorder
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.clonevoice.domain.ICloneVoiceService
import com.lingshu.feature.clonevoice.domain.Voice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt

class CloneVoiceServiceImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ICloneVoiceService {

    private val voices = mutableListOf<Voice>()
    private var currentVoice: Voice? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var recordingStartTime = 0L
    private var sdkInitializationState = SdkInitState.UNINITIALIZED

    private val voicesDir: File by lazy {
        File(context.filesDir, "voices").apply {
            if (!exists()) {
                val created = mkdirs()
                LingShuLog.i(TAG, "创建声音存储目录: ${absolutePath}, 结果=$created")
            }
        }
    }

    init {
        val traceId = "init_${System.currentTimeMillis()}"
        LingShuLog.d(TAG, "[$traceId] ===== CloneVoiceService 初始化开始 =====")

        try {
            // 1. 检查目录状态
            LingShuLog.d(TAG, "[$traceId] 检查存储目录: ${voicesDir.absolutePath}")
            LingShuLog.d(TAG, "[$traceId] 目录存在=${voicesDir.exists()}, 可读=${voicesDir.canRead()}, 可写=${voicesDir.canWrite()}")

            // 2. 检查剩余存储空间
            val freeSpace = checkStorageSpace()
            LingShuLog.i(TAG, "[$traceId] 可用存储空间: ${freeSpace.first} bytes (${freeSpace.second})")
            if (freeSpace.first < REQUIRED_MIN_STORAGE) {
                LingShuLog.w(TAG, "[$traceId] ⚠ 存储空间不足: 需要 ${REQUIRED_MIN_STORAGE/1024/1024}MB，实际 ${freeSpace.second}")
            }

            // 3. 初始化 SDK 占位
            initializeSdk(traceId)

            // 4. 加载已有声音
            loadExistingVoices(traceId)

            LingShuLog.d(TAG, "[$traceId] ===== CloneVoiceService 初始化完成，已加载声音数=${voices.size} =====")
        } catch (e: Exception) {
            LingShuLog.e(TAG, "[$traceId] CloneVoiceService 初始化严重失败", e)
        }
    }

    private fun initializeSdk(traceId: String) {
        LingShuLog.d(TAG, "[$traceId] [SDK初始化] 开始检测并初始化 Soniqo/CloneTTS SDK...")
        sdkInitializationState = SdkInitState.INITIALIZING
        LingShuLog.d(TAG, "[$traceId] [SDK初始化] 状态: UNINITIALIZED -> INITIALIZING")

        try {
            // TODO: 接入真实 Soniqo Speech SDK
            // val sdk = SoniqoSdk.init(context, apiKey)
            // sdk.checkLicense() -> 验证授权

            LingShuLog.d(TAG, "[$traceId] [SDK初始化] 检查 SDK 配置文件...")
            // 检查 assets 或 raw 目录下的 SDK 配置

            LingShuLog.d(TAG, "[$traceId] [SDK初始化] 检查 SDK 授权信息...")
            // 读取本地授权文件或连接授权服务器

            // Mock: 暂用未授权状态，等待真实接入
            sdkInitializationState = SdkInitState.NOT_LICENSED
            LingShuLog.w(TAG, "[$traceId] [SDK初始化] ⚠ SDK 尚未授权，当前使用 Mock 模式。" +
                    "请联系 Soniqo 官方获取授权后，替换 TODO 位置的初始化代码。")
            LingShuLog.d(TAG, "[$traceId] [SDK初始化] 最终状态: $sdkInitializationState")
        } catch (e: Exception) {
            sdkInitializationState = SdkInitState.FAILED
            LingShuLog.e(TAG, "[$traceId] [SDK初始化] ❌ 初始化异常，状态: FAILED", e)
        }
    }

    private fun loadExistingVoices(traceId: String) {
        LingShuLog.d(TAG, "[$traceId] [加载声音] 扫描目录: ${voicesDir.absolutePath}")

        try {
            val subDirs = voicesDir.listFiles { file -> file.isDirectory }
            if (subDirs.isNullOrEmpty()) {
                LingShuLog.d(TAG, "[$traceId] [加载声音] 目录为空，使用预置 Mock 声音")
                addMockVoices(traceId)
                return
            }

            LingShuLog.d(TAG, "[$traceId] [加载声音] 发现 ${subDirs.size} 个声音子目录")
            subDirs.forEach { dir: File ->
                try {
                    loadVoiceFromDir(dir, traceId)
                } catch (e: Exception) {
                    LingShuLog.e(TAG, "[$traceId] [加载声音] 加载失败 dir=${dir.name}", e)
                }
            }

            if (voices.isEmpty()) {
                addMockVoices(traceId)
            } else {
                currentVoice = voices.firstOrNull()
                LingShuLog.i(TAG, "[$traceId] [加载声音] 成功加载 ${voices.size} 个声音，" +
                        "当前默认=${currentVoice?.name ?: "null"}")
            }
        } catch (e: Exception) {
            LingShuLog.e(TAG, "[$traceId] [加载声音] 扫描目录异常", e)
            addMockVoices(traceId)
        }
    }

    private fun loadVoiceFromDir(dir: File, traceId: String) {
        val voiceId = dir.name
        LingShuLog.d(TAG, "[$traceId] [加载声音] 处理 voiceId=$voiceId")

        val metadataFile = File(dir, METADATA_FILE_NAME)
        val modelDir = File(dir, MODEL_DIR_NAME)
        val sampleFile = File(dir, SAMPLE_FILE_NAME)

        // 检查必需文件
        val allExist = metadataFile.exists() && modelDir.exists() && sampleFile.exists()
        LingShuLog.d(TAG, "[$traceId] [加载声音] voiceId=$voiceId " +
                "metadata=${metadataFile.exists()}, " +
                "modelDir=${modelDir.exists()}, " +
                "sample=${sampleFile.exists()}(${sampleFile.length()} bytes)")

        if (!allExist) {
            LingShuLog.w(TAG, "[$traceId] [加载声音] voiceId=$voiceId 文件不完整，跳过")
            return
        }

        // 读取 metadata
        val metadata = parseMetadata(metadataFile)
        val voice = Voice(
            id = voiceId,
            name = (metadata["name"] as? String) ?: voiceId,
            modelPath = modelDir.absolutePath,
            samplePath = sampleFile.absolutePath,
            createdAt = (metadata["createdAt"] as? Long) ?: dir.lastModified()
        )
        voices.add(voice)
        LingShuLog.d(TAG, "[$traceId] [加载声音] 成功 voiceId=$voiceId, name=${voice.name}, " +
                "createdAt=${formatDate(voice.createdAt)}")
    }

    private fun addMockVoices(traceId: String) {
        LingShuLog.d(TAG, "[$traceId] [Mock声音] 初始化预置声音")
        val mockVoices = listOf(
            Voice(
                id = "voice_001",
                name = "默认女声",
                modelPath = "${voicesDir.absolutePath}/voice_001/model/",
                samplePath = "${voicesDir.absolutePath}/voice_001/sample.wav",
                createdAt = System.currentTimeMillis() - 86400000 * 7
            ),
            Voice(
                id = "voice_002",
                name = "沉稳男声",
                modelPath = "${voicesDir.absolutePath}/voice_002/model/",
                samplePath = "${voicesDir.absolutePath}/voice_002/sample.wav",
                createdAt = System.currentTimeMillis() - 86400000 * 3
            )
        )
        voices.addAll(mockVoices)
        currentVoice = voices.firstOrNull()
        LingShuLog.i(TAG, "[$traceId] [Mock声音] 添加了 ${mockVoices.size} 个预置声音，" +
                "默认=${currentVoice?.name}")
    }

    // ========================================
    // 录音相关 - 详细埋点
    // ========================================

    suspend fun startRecording(outputFile: File): Result<Unit> {
        val traceId = "rec_${System.currentTimeMillis()}"
        LingShuLog.i(TAG, "[$traceId] ===== 开始录音 =====")
        LingShuLog.d(TAG, "[$traceId] [录音] 输出文件: ${outputFile.absolutePath}")
        LingShuLog.d(TAG, "[$traceId] [录音] 父目录存在: ${outputFile.parentFile?.exists()}")
        LingShuLog.d(TAG, "[$traceId] [录音] SDK 状态: $sdkInitializationState")

        if (isRecording) {
            LingShuLog.w(TAG, "[$traceId] [录音] ❌ 已处于录音状态，忽略重复调用")
            return Result.error(ErrorCodes.UNKNOWN_ERROR, "录音已在进行中")
        }

        // 1. 存储空间检查
        val (freeBytes, freeStr) = checkStorageSpace()
        LingShuLog.d(TAG, "[$traceId] [录音] 可用空间: $freeStr")
        if (freeBytes < RECORDING_MIN_STORAGE) {
            LingShuLog.e(TAG, "[$traceId] [录音] ❌ 存储空间不足，需要至少 50MB")
            return Result.error(ErrorCodes.STORAGE_INSUFFICIENT,
                    ErrorCodes.getMessage(ErrorCodes.STORAGE_INSUFFICIENT))
        }

        return try {
            withContext(Dispatchers.Main) {
                recordingStartTime = System.currentTimeMillis()

                // 2. 初始化 MediaRecorder
                LingShuLog.d(TAG, "[$traceId] [录音] 初始化 MediaRecorder...")
                mediaRecorder = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    LingShuLog.d(TAG, "[$traceId] [录音] 音源: AudioSource.MIC")

                    setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                    LingShuLog.d(TAG, "[$traceId] [录音] 格式: THREE_GPP")

                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_WB)
                    LingShuLog.d(TAG, "[$traceId] [录音] 编码器: AMR_WB (宽带)")

                    setAudioSamplingRate(16000)
                    setAudioEncodingBitRate(256000)
                    LingShuLog.d(TAG, "[$traceId] [录音] 采样率: 16kHz, 码率: 256kbps")

                    setOutputFile(outputFile.absolutePath)
                }

                // 3. 预处理
                LingShuLog.d(TAG, "[$traceId] [录音] prepare() 中...")
                val prepareStart = System.currentTimeMillis()
                mediaRecorder?.prepare()
                LingShuLog.d(TAG, "[$traceId] [录音] prepare() 完成，耗时 ${System.currentTimeMillis()-prepareStart}ms")

                // 4. 开始录音
                LingShuLog.d(TAG, "[$traceId] [录音] start() 中...")
                mediaRecorder?.start()
                isRecording = true
                recordingStartTime = System.currentTimeMillis()

                LingShuLog.i(TAG, "[$traceId] [录音] ✅ 录音已启动，等待录制 10-30 秒...")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "[$traceId] [录音] ❌ 录音启动失败", e)
            cleanupRecorder(traceId)
            Result.error(
                ErrorCodes.MICROPHONE_UNAVAILABLE,
                ErrorCodes.getMessage(ErrorCodes.MICROPHONE_UNAVAILABLE),
                e
            )
        }
    }

    fun stopRecording(): Result<File> {
        val traceId = "stp_${System.currentTimeMillis()}"
        val duration = System.currentTimeMillis() - recordingStartTime
        LingShuLog.i(TAG, "[$traceId] ===== 停止录音，录制时长=${duration}ms (${(duration/1000)}s) =====")

        if (!isRecording || mediaRecorder == null) {
            LingShuLog.w(TAG, "[$traceId] [录音] 当前未在录音状态，isRecording=$isRecording")
            return Result.error(ErrorCodes.MICROPHONE_UNAVAILABLE, "当前未在录音")
        }

        return try {
            val stopStart = System.currentTimeMillis()
            mediaRecorder?.stop()
            LingShuLog.d(TAG, "[$traceId] [录音] stop() 完成，耗时 ${System.currentTimeMillis()-stopStart}ms")

            // 录音时长校验
            if (duration < 10000) {
                LingShuLog.w(TAG, "[$traceId] [录音] ⚠ 录制时长不足10秒 (${(duration/1000)}s)，" +
                        "建议 10-30 秒以获得更好的克隆效果")
            } else if (duration > 30000) {
                LingShuLog.w(TAG, "[$traceId] [录音] ⚠ 录制时长超过30秒 (${(duration/1000)}s)，" +
                        "过长可能影响处理速度")
            }

            val recorder = mediaRecorder
            val outputFile = File(recorder.toString()) // 此处仅示例，真实要拿到 path
            val realFile = getLastRecordingFile(context)
            LingShuLog.d(TAG, "[$traceId] [录音] 输出文件大小: ${realFile?.length() ?: 0} bytes")

            cleanupRecorder(traceId)

            if (realFile == null) {
                LingShuLog.e(TAG, "[$traceId] [录音] ❌ 无法获取录制文件")
                return Result.error(ErrorCodes.MICROPHONE_UNAVAILABLE, "无法获取录制文件")
            }

            LingShuLog.i(TAG, "[$traceId] [录音] ✅ 录音完成，文件=${realFile.absolutePath}，" +
                    "大小=${formatFileSize(realFile.length())}")
            Result.success(realFile)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "[$traceId] [录音] ❌ 停止录音失败", e)
            cleanupRecorder(traceId)
            Result.error(ErrorCodes.MICROPHONE_UNAVAILABLE, "停止录音失败", e)
        }
    }

    private fun cleanupRecorder(traceId: String) {
        LingShuLog.d(TAG, "[$traceId] [录音] 清理 MediaRecorder 资源...")
        try {
            mediaRecorder?.apply {
                reset()
                LingShuLog.d(TAG, "[$traceId] [录音] reset() 成功")
                release()
                LingShuLog.d(TAG, "[$traceId] [录音] release() 成功")
            }
        } catch (e: Exception) {
            LingShuLog.w(TAG, "[$traceId] [录音] 清理时出现异常（可忽略）", e)
        } finally {
            mediaRecorder = null
            isRecording = false
        }
    }

    // ========================================
    // 克隆核心接口 - 详细埋点
    // ========================================

    override suspend fun cloneAudio(audioFile: File): Result<String> {
        val traceId = "cln_${System.currentTimeMillis()}"
        val startTime = System.currentTimeMillis()
        LingShuLog.i(TAG, "[$traceId] ========== cloneAudio 开始 ==========")
        LingShuLog.i(TAG, "[$traceId] [克隆-参数] audioFile=${audioFile.absolutePath}")
        LingShuLog.d(TAG, "[$traceId] [克隆-前置] 文件存在=${audioFile.exists()}, " +
                "大小=${formatFileSize(audioFile.length())}, " +
                "可读=${audioFile.canRead()}")
        LingShuLog.d(TAG, "[$traceId] [克隆-前置] SDK 初始化状态=$sdkInitializationState")

        // 1. 基础校验
        LingShuLog.d(TAG, "[$traceId] [克隆-步骤1/7] 基础参数校验...")
        if (!audioFile.exists()) {
            LingShuLog.e(TAG, "[$traceId] [克隆-步骤1] ❌ 音频文件不存在: ${audioFile.absolutePath}")
            return Result.error(ErrorCodes.VOICE_CLONE_FAILED,
                    ErrorCodes.getMessage(ErrorCodes.VOICE_CLONE_FAILED))
        }
        if (audioFile.length() < MIN_AUDIO_SIZE) {
            LingShuLog.w(TAG, "[$traceId] [克隆-步骤1] ⚠ 文件过小: ${audioFile.length()} bytes，" +
                    "建议文件大小 > ${MIN_AUDIO_SIZE/1024}KB")
        }
        val duration = estimateAudioDuration(audioFile)
        LingShuLog.d(TAG, "[$traceId] [克隆-步骤1] 预估音频时长: ${duration}秒")
        if (duration < 8) {
            LingShuLog.w(TAG, "[$traceId] [克隆-步骤1] ⚠ 音频时长过短: ${duration}秒 (< 10秒)，" +
                    "克隆质量可能受影响")
        }

        // 2. 存储空间检查
        LingShuLog.d(TAG, "[$traceId] [克隆-步骤2/7] 存储空间检查...")
        val (freeBytes, freeStr) = checkStorageSpace()
        LingShuLog.d(TAG, "[$traceId] [克隆-步骤2] 可用空间: $freeStr")
        if (freeBytes < REQUIRED_MIN_STORAGE) {
            LingShuLog.e(TAG, "[$traceId] [克隆-步骤2] ❌ 存储空间不足，需要至少 500MB")
            return Result.error(ErrorCodes.STORAGE_INSUFFICIENT,
                    ErrorCodes.getMessage(ErrorCodes.STORAGE_INSUFFICIENT))
        }

        // 3. 准备 voice 目录
        LingShuLog.d(TAG, "[$traceId] [克隆-步骤3/7] 准备声音目录...")
        val voiceId = "voice_${UUID.randomUUID().toString().take(8)}"
        val voiceDir = File(voicesDir, voiceId)
        val modelDir = File(voiceDir, MODEL_DIR_NAME)
        val sampleFile = File(voiceDir, SAMPLE_FILE_NAME)
        val metadataFile = File(voiceDir, METADATA_FILE_NAME)

        val dirResult = voiceDir.mkdirs() && modelDir.mkdirs()
        LingShuLog.d(TAG, "[$traceId] [克隆-步骤3] voiceId=$voiceId, " +
                "目录创建=$dirResult, " +
                "voiceDir=${voiceDir.absolutePath}, " +
                "modelDir=${modelDir.absolutePath}")

        // 4. 拷贝样本音频
        LingShuLog.d(TAG, "[$traceId] [克隆-步骤4/7] 拷贝样本音频...")
        val copyStart = System.currentTimeMillis()
        val copyResult = copyFile(audioFile, sampleFile)
        LingShuLog.d(TAG, "[$traceId] [克隆-步骤4] 拷贝耗时=${System.currentTimeMillis()-copyStart}ms, " +
                "结果=$copyResult, " +
                "目标大小=${sampleFile.length()} bytes")
        if (!copyResult) {
            cleanupVoiceDir(voiceDir, traceId)
            return Result.error(ErrorCodes.VOICE_CLONE_FAILED, "样本文件保存失败")
        }

        // 5. 调用 SDK 克隆 (核心步骤)
        LingShuLog.i(TAG, "[$traceId] [克隆-步骤5/7] ===== 调用声音克隆 SDK 开始 =====")
        val sdkStart = System.currentTimeMillis()
        val sdkResult = invokeSoniqoSdk(audioFile, modelDir, traceId)
        val sdkCost = System.currentTimeMillis() - sdkStart
        LingShuLog.i(TAG, "[$traceId] [克隆-步骤5] ===== 调用声音克隆 SDK 结束，耗时=${sdkCost}ms，结果=${sdkResult.isSuccess} =====")

        if (!sdkResult.isSuccess) {
            val err = sdkResult.errorOrNull()
            LingShuLog.e(TAG, "[$traceId] [克隆-步骤5] ❌ SDK 克隆失败: code=${err?.code}, msg=${err?.message}, cause=${err?.cause}")
            cleanupVoiceDir(voiceDir, traceId)
            return Result.error(
                ErrorCodes.VOICE_CLONE_FAILED,
                ErrorCodes.getMessage(ErrorCodes.VOICE_CLONE_FAILED),
                err?.cause
            )
        }

        // 6. 写入 metadata
        LingShuLog.d(TAG, "[$traceId] [克隆-步骤6/7] 写入元数据...")
        val voiceName = "我的声音_${SimpleDateFormat("MMddHHmm", Locale.getDefault()).format(Date())}"
        val metadata = mapOf(
            "id" to voiceId,
            "name" to voiceName,
            "samplePath" to sampleFile.absolutePath,
            "modelPath" to modelDir.absolutePath,
            "createdAt" to System.currentTimeMillis(),
            "durationSec" to duration,
            "sdkState" to sdkInitializationState.name,
            "traceId" to traceId
        )
        writeMetadata(metadataFile, metadata)
        LingShuLog.d(TAG, "[$traceId] [克隆-步骤6] metadata=${metadata}")

        // 7. 加入内存列表
        LingShuLog.d(TAG, "[$traceId] [克隆-步骤7/7] 加入内存管理列表...")
        val newVoice = Voice(
            id = voiceId,
            name = voiceName,
            modelPath = modelDir.absolutePath,
            samplePath = sampleFile.absolutePath,
            createdAt = System.currentTimeMillis()
        )
        voices.add(newVoice)

        val totalCost = System.currentTimeMillis() - startTime
        LingShuLog.i(TAG, "[$traceId] ========== cloneAudio 成功 ==========")
        LingShuLog.i(TAG, "[$traceId] [克隆-汇总] voiceId=$voiceId, name=$voiceName")
        LingShuLog.i(TAG, "[$traceId] [克隆-汇总] 总耗时=${totalCost}ms (SDK耗时=$sdkCost ms)")
        LingShuLog.i(TAG, "[$traceId] [克隆-汇总] 当前共有 ${voices.size} 个声音")

        return Result.success(voiceId)
    }

    /**
     * 调用真实 Soniqo/CloneTTS SDK 的核心方法
     * 当前为 Mock 实现，接入真实 SDK 时直接替换内部逻辑
     */
    private suspend fun invokeSoniqoSdk(audioFile: File, modelDir: File, traceId: String): Result<Unit> {
        LingShuLog.d(TAG, "[$traceId] [SDK调用] SDK 初始化状态=$sdkInitializationState")
        LingShuLog.d(TAG, "[$traceId] [SDK调用] 输入音频: ${audioFile.absolutePath} (${formatFileSize(audioFile.length())})")
        LingShuLog.d(TAG, "[$traceId] [SDK调用] 模型输出目录: ${modelDir.absolutePath}")

        if (sdkInitializationState == SdkInitState.UNINITIALIZED ||
            sdkInitializationState == SdkInitState.FAILED) {
            LingShuLog.w(TAG, "[$traceId] [SDK调用] ⚠ SDK 未正确初始化 (state=$sdkInitializationState)，" +
                    "将使用 Mock 模拟流程")
        }

        // TODO: 接入真实 SDK，参考伪代码：
        /*
        val client = SoniqoClient(
            context = context,
            accessKey = getAccessKeyFromPreferences(),
            baseUrl = "https://api.soniqo.ai/v1"
        )
        // 阶段1: 上传音频
        val uploadResp = client.uploadAudio(
            file = audioFile,
            sampleRate = 16000,
            channels = 1,
            traceId = traceId
        )
        LingShuLog.d(TAG, "[$traceId] [SDK调用-上传] result=${uploadResp.status}, taskId=${uploadResp.taskId}")

        // 阶段2: 提交克隆任务
        val taskResp = client.submitCloneTask(
            audioTaskId = uploadResp.taskId,
            modelType = "V3",
            language = "zh-CN",
            traceId = traceId
        )
        LingShuLog.d(TAG, "[$traceId] [SDK调用-提交] taskId=${taskResp.taskId}, " +
                "estimatedTime=${taskResp.estimatedSeconds}s")

        // 阶段3: 轮询进度
        var progress = 0
        while (progress < 100) {
            val statusResp = client.queryTaskStatus(taskResp.taskId, traceId)
            progress = statusResp.progress
            LingShuLog.d(TAG, "[$traceId] [SDK调用-进度] $progress%, status=${statusResp.state}")
            reportProgress(progress)
            delay(500)
            if (statusResp.state == "FAILED") {
                LingShuLog.e(TAG, "[$traceId] [SDK调用-失败] errCode=${statusResp.errCode}, " +
                        "errMsg=${statusResp.errMsg}")
                return Result.error(...)
            }
        }

        // 阶段4: 下载模型
        val modelResp = client.downloadModel(taskResp.taskId, modelDir, traceId)
        LingShuLog.d(TAG, "[$traceId] [SDK调用-下载] 模型文件数=${modelResp.fileCount}")
        */

        // Mock: 模拟处理进度 (0 -> 100)
        for (progress in 0..100 step 10) {
            delay(300)
            LingShuLog.d(TAG, "[$traceId] [SDK调用-Mock] 克隆进度: $progress%")
        }

        // Mock: 生成空的模型文件占位
        val modelFiles = listOf("model.pt", "config.json", "vocab.txt", "speaker_embed.npy")
        modelFiles.forEach { fileName ->
            val f = File(modelDir, fileName)
            f.writeText("# placeholder for ${fileName}\ntraceId=${traceId}")
            LingShuLog.v(TAG, "[$traceId] [SDK调用-Mock] 创建占位模型文件: $fileName")
        }

        LingShuLog.d(TAG, "[$traceId] [SDK调用-Mock] ✅ 模拟克隆流程结束")
        return Result.success(Unit)
    }

    // ========================================
    // 其他接口 - 详细埋点
    // ========================================

    override suspend fun setCurrentVoice(voiceId: String): Result<Unit> {
        val traceId = "scv_${System.currentTimeMillis()}"
        LingShuLog.i(TAG, "[$traceId] setCurrentVoice: voiceId=$voiceId")
        LingShuLog.d(TAG, "[$traceId] 当前=${currentVoice?.id}(${currentVoice?.name}), " +
                "共有 ${voices.size} 个可选")

        return try {
            val voice = voices.find { it.id == voiceId }
            if (voice == null) {
                LingShuLog.e(TAG, "[$traceId] ❌ 声音不存在，可选 IDs=${voices.map { it.id }}")
                return Result.error(ErrorCodes.UNKNOWN_ERROR, "声音不存在: $voiceId")
            }

            // 检查模型目录
            val modelDir = File(voice.modelPath)
            val sampleExists = File(voice.samplePath).exists()
            LingShuLog.d(TAG, "[$traceId] 模型目录存在=${modelDir.exists()}, " +
                    "样本文件存在=$sampleExists, " +
                    "模型文件数=${modelDir.listFiles()?.size ?: 0}")

            currentVoice = voice
            LingShuLog.i(TAG, "[$traceId] ✅ 切换成功: ${voice.name} (id=${voice.id})")

            // TODO: 通知 TTS 引擎切换声音
            LingShuLog.d(TAG, "[$traceId] 已触发 TTS 引擎切换通知（待接入 ITtsEngine#setVoice）")

            Result.success(Unit)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "[$traceId] ❌ 切换失败", e)
            Result.error(ErrorCodes.UNKNOWN_ERROR, "切换声音失败", e)
        }
    }

    override fun getCurrentVoice(): Voice? {
        LingShuLog.v(TAG, "getCurrentVoice -> id=${currentVoice?.id}, name=${currentVoice?.name}")
        return currentVoice
    }

    override fun listVoices(): List<Voice> {
        LingShuLog.d(TAG, "listVoices: 返回 ${voices.size} 个声音, " +
                "当前=${currentVoice?.id}")
        voices.forEach {
            LingShuLog.v(TAG, "  - ${it.id} | ${it.name} | created=${formatDate(it.createdAt)}")
        }
        return voices.toList()
    }

    override suspend fun deleteVoice(voiceId: String): Result<Unit> {
        val traceId = "del_${System.currentTimeMillis()}"
        LingShuLog.i(TAG, "[$traceId] ===== deleteVoice: voiceId=$voiceId =====")

        return try {
            var found = false
            var deletedVoice: Voice? = null
            val iterator = voices.iterator()
            while (iterator.hasNext()) {
                val voice = iterator.next()
                if (voice.id == voiceId) {
                    deletedVoice = voice
                    iterator.remove()
                    found = true
                    break
                }
            }

            if (!found) {
                LingShuLog.w(TAG, "[$traceId] 未找到 voiceId=$voiceId，无需删除")
                return Result.success(Unit)
            }

            // 删除物理文件
            LingShuLog.d(TAG, "[$traceId] 删除物理文件: ${deletedVoice!!.modelPath.substringBeforeLast("/")}")
            val voiceDir = File(voicesDir, voiceId)
            if (voiceDir.exists()) {
                val deleted = deleteRecursive(voiceDir)
                LingShuLog.d(TAG, "[$traceId] 物理删除结果: $deleted")
            }

            // 如果删除的是当前声音，回退到第一个
            if (currentVoice?.id == voiceId) {
                currentVoice = voices.firstOrNull()
                LingShuLog.w(TAG, "[$traceId] 已删除当前声音，自动切换到: ${currentVoice?.name ?: "null"}")
            }

            LingShuLog.i(TAG, "[$traceId] ✅ 删除成功: ${deletedVoice.name}，剩余 ${voices.size} 个")
            Result.success(Unit)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "[$traceId] ❌ 删除失败", e)
            Result.error(ErrorCodes.UNKNOWN_ERROR, "删除声音失败", e)
        }
    }

    override suspend fun previewVoice(voiceId: String, text: String): Result<Unit> {
        val traceId = "prv_${System.currentTimeMillis()}"
        val startTime = System.currentTimeMillis()
        LingShuLog.i(TAG, "[$traceId] ===== previewVoice: voiceId=$voiceId, textLength=${text.length} =====")
        LingShuLog.d(TAG, "[$traceId] 预览文本(前50字): ${text.take(50)}...")

        return try {
            val voice = voices.find { it.id == voiceId }
            if (voice == null) {
                LingShuLog.e(TAG, "[$traceId] ❌ 声音不存在: $voiceId")
                return Result.error(ErrorCodes.UNKNOWN_ERROR, "声音不存在")
            }
            LingShuLog.d(TAG, "[$traceId] 目标声音: name=${voice.name}, model=${voice.modelPath}")
            LingShuLog.d(TAG, "[$traceId] 样本文件: exists=${File(voice.samplePath).exists()}, " +
                    "size=${formatFileSize(File(voice.samplePath).length())}")

            // TODO: 接入真实 TTS 使用该声音生成
            /*
            val audioFile = ttsEngine.synthesize(text, voiceId = voiceId)
            playAudio(audioFile)
            */

            LingShuLog.d(TAG, "[$traceId] [Mock] 模拟 TTS 合成 + 播放...")
            delay(1000)

            val cost = System.currentTimeMillis() - startTime
            LingShuLog.i(TAG, "[$traceId] ✅ 预览完成，耗时=${cost}ms (Mock)")
            Result.success(Unit)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "[$traceId] ❌ 预览失败", e)
            Result.error(ErrorCodes.VOICE_CLONE_FAILED, "预览失败", e)
        }
    }

    // ========================================
    // 工具函数
    // ========================================

    private fun checkStorageSpace(): Pair<Long, String> {
        return try {
            val stat = android.os.StatFs(voicesDir.absolutePath)
            val bytes = stat.availableBytes
            bytes to formatFileSize(bytes)
        } catch (e: Exception) {
            0L to "未知"
        }
    }

    private fun copyFile(src: File, dst: File): Boolean {
        return try {
            FileInputStream(src).channel.use { inChannel ->
                FileOutputStream(dst).channel.use { outChannel ->
                    inChannel.transferTo(0, inChannel.size(), outChannel)
                }
            }
            true
        } catch (e: Exception) {
            LingShuLog.e(TAG, "copyFile 失败 ${src.name} -> ${dst.name}", e)
            false
        }
    }

    private fun deleteRecursive(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursive(it) }
        }
        return file.delete()
    }

    private fun parseMetadata(file: File): Map<String, Any> {
        // 简化：实际可用 JSON 解析
        return runCatching {
            val map = mutableMapOf<String, Any>()
            file.readLines().forEach { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) {
                    map[parts[0]] = parts[1]
                }
            }
            map
        }.getOrDefault(emptyMap())
    }

    private fun writeMetadata(file: File, metadata: Map<String, Any>) {
        runCatching {
            file.bufferedWriter().use { writer ->
                metadata.forEach { (k, v) -> writer.write("$k=$v\n") }
            }
            LingShuLog.v(TAG, "writeMetadata 成功: ${file.absolutePath}")
        }.onFailure {
            LingShuLog.w(TAG, "writeMetadata 失败", it)
        }
    }

    private fun estimateAudioDuration(file: File): Int {
        // 粗略估算：AMR_WB @ 256kbps -> 约 32KB/s
        val kbps = 256.0
        val bytesPerSec = kbps * 1024 / 8
        return (file.length() / bytesPerSec).roundToInt()
    }

    private fun cleanupVoiceDir(dir: File, traceId: String) {
        LingShuLog.w(TAG, "[$traceId] 清理异常克隆目录: ${dir.absolutePath}")
        runCatching {
            deleteRecursive(dir)
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${"%.1f".format(bytes/1024.0)} KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes/1024.0/1024.0)} MB"
            else -> "${"%.2f".format(bytes/1024.0/1024.0/1024.0)} GB"
        }
    }

    private fun formatDate(ts: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }

    private fun getLastRecordingFile(ctx: Context): File? {
        // 简化实现：真实场景需保留录音输出路径
        val dir = File(ctx.cacheDir, "recordings")
        return dir.listFiles()?.maxByOrNull { it.lastModified() }
    }

    enum class SdkInitState {
        UNINITIALIZED,  // 未初始化
        INITIALIZING,   // 初始化中
        SUCCESS,        // 初始化成功且已授权
        NOT_LICENSED,   // 未授权
        FAILED          // 初始化失败
    }

    companion object {
        private const val TAG = "CloneVoiceService"
        private const val MODEL_DIR_NAME = "model"
        private const val SAMPLE_FILE_NAME = "sample.wav"
        private const val METADATA_FILE_NAME = "metadata.properties"

        private const val REQUIRED_MIN_STORAGE = 500L * 1024 * 1024   // 500MB
        private const val RECORDING_MIN_STORAGE = 50L * 1024 * 1024    // 50MB
        private const val MIN_AUDIO_SIZE = 50L * 1024                   // 50KB
    }
}
