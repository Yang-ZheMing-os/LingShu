package com.lingshu.feature.clonevoice.data

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.clonevoice.domain.ICloneVoiceService
import com.lingshu.feature.clonevoice.domain.Voice
import com.lingshu.feature.offlinetts.data.OfflineTtsRouter
import com.lingshu.feature.offlinetts.domain.OfflineTtsConfig
import com.lingshu.feature.offlinetts.domain.OfflineTtsProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.math.roundToInt

class CloneVoiceServiceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ttsRouter: OfflineTtsRouter
) : ICloneVoiceService {

    private val voices = mutableListOf<Voice>()
    private var currentVoice: Voice? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var recordingStartTime = 0L
    private var sdkInitializationState = SdkInitState.UNINITIALIZED
    // 当前录音输出文件，startRecording 时保存，stopRecording 时直接返回
    private var currentRecordingFile: File? = null

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
            // 当前为样本保存模式，不依赖外部 SDK：
            // - 克隆：仅保存用户录制的声音样本
            // - 预览：走 OfflineTtsRouter（内置 Android 系统 TTS 兜底）
            // TODO: 接入真实 Soniqo Speech SDK 后，在此处初始化并训练模型
            sdkInitializationState = SdkInitState.NOT_LICENSED
            LingShuLog.w(TAG, "[$traceId] [SDK初始化] ⚠ SDK 尚未授权，克隆采用样本保存模式，预览使用 OfflineTtsRouter。")
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
                LingShuLog.d(TAG, "[$traceId] [加载声音] 目录为空，使用系统默认音色")
                addSystemVoice(traceId)
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

            // 始终补充一个系统默认音色，保证预览可用
            addSystemVoice(traceId)

            if (currentVoice == null) {
                currentVoice = voices.firstOrNull()
            }
            LingShuLog.i(TAG, "[$traceId] [加载声音] 成功加载 ${voices.size} 个声音，" +
                    "当前默认=${currentVoice?.name ?: "null"}")
        } catch (e: Exception) {
            LingShuLog.e(TAG, "[$traceId] [加载声音] 扫描目录异常", e)
            addSystemVoice(traceId)
        }
    }

    private fun loadVoiceFromDir(dir: File, traceId: String) {
        val voiceId = dir.name
        LingShuLog.d(TAG, "[$traceId] [加载声音] 处理 voiceId=$voiceId")

        val metadataFile = File(dir, METADATA_FILE_NAME)
        val modelDir = File(dir, MODEL_DIR_NAME)
        val sampleFile = File(dir, SAMPLE_FILE_NAME)

        if (!metadataFile.exists()) {
            LingShuLog.w(TAG, "[$traceId] [加载声音] voiceId=$voiceId 缺少 metadata，跳过")
            return
        }
        LingShuLog.d(TAG, "[$traceId] [加载声音] voiceId=$voiceId " +
                "metadata=present, " +
                "modelDir=${modelDir.exists()}, " +
                "sample=${sampleFile.exists()}(${sampleFile.length()} bytes)")

        // 读取 metadata
        val metadata = parseMetadata(metadataFile)
        val tagsStr = parseNullableString(metadata["tags"])
        val voice = Voice(
            id = voiceId,
            name = (metadata["name"] as? String) ?: voiceId,
            modelPath = if (modelDir.exists()) modelDir.absolutePath else dir.absolutePath,
            samplePath = if (sampleFile.exists()) sampleFile.absolutePath else "",
            createdAt = parseLong(metadata["createdAt"]) ?: dir.lastModified(),
            voiceName = parseNullableString(metadata["voiceName"]),
            pitch = parseFloat(metadata["pitch"]) ?: 1.0f,
            rate = parseFloat(metadata["rate"]) ?: 1.0f,
            isSystemVoice = parseBoolean(metadata["isSystemVoice"]),
            author = parseNullableString(metadata["author"]),
            description = parseNullableString(metadata["description"]),
            tags = if (tagsStr.isNullOrEmpty()) emptyList() else tagsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        )
        voices.add(voice)
        LingShuLog.d(TAG, "[$traceId] [加载声音] 成功 voiceId=$voiceId, name=${voice.name}, " +
                "tags=${voice.tags}, createdAt=${formatDate(voice.createdAt)}")
    }

    /**
     * 添加预置系统音色（基于 Android TTS 的 pitch/rate 调整，无需模型/样本文件）。
     * 提供多种音色风格供用户选择，每个均标注 isSystemVoice=true，voiceName=null（用默认 Voice）。
     */
    private fun addSystemVoice(traceId: String) {
        LingShuLog.d(TAG, "[$traceId] [系统音色] 初始化预置系统音色")
        // id, 显示名, pitch, rate
        val presets: List<Voice> = listOf(
            Voice("sys_gentle_female", "温柔女声", "", "", System.currentTimeMillis(), null, 1.2f, 0.9f, true),
            Voice("sys_calm_male", "沉稳男声", "", "", System.currentTimeMillis(), null, 0.8f, 1.0f, true),
            Voice("sys_lively_girl", "活泼少女", "", "", System.currentTimeMillis(), null, 1.5f, 1.1f, true),
            Voice("sys_standard_female", "标准女声", "", "", System.currentTimeMillis(), null, 1.0f, 1.0f, true),
            Voice("sys_standard_male", "标准男声", "", "", System.currentTimeMillis(), null, 0.9f, 1.0f, true)
        )
        var added = 0
        presets.forEach { preset ->
            // 按 id 去重，避免重复添加
            if (voices.any { it.id == preset.id }) return@forEach
            voices.add(preset)
            added++
        }
        if (currentVoice == null) currentVoice = voices.firstOrNull()
        LingShuLog.i(TAG, "[$traceId] [系统音色] 已添加 $added 个预置音色，当前共 ${voices.size} 个声音")
    }

    // ========================================
    // 录音相关 - 详细埋点
    // ========================================

    override suspend fun startRecording(outputFile: File): Result<Unit> {
        val traceId = "rec_${System.currentTimeMillis()}"
        LingShuLog.i(TAG, "[$traceId] ===== 开始录音 =====")
        LingShuLog.d(TAG, "[$traceId] [录音] 输出文件: ${outputFile.absolutePath}")
        LingShuLog.d(TAG, "[$traceId] [录音] 父目录存在: ${outputFile.parentFile?.exists()}")
        LingShuLog.d(TAG, "[$traceId] [录音] SDK 状态: $sdkInitializationState")

        if (isRecording) {
            LingShuLog.w(TAG, "[$traceId] [录音] ❌ 已处于录音状态，忽略重复调用")
            return Result.error(ErrorCodes.UNKNOWN_ERROR, "录音已在进行中")
        }

        // 保存本次录音输出文件路径，stopRecording 时直接返回（不再按修改时间查找）
        currentRecordingFile = outputFile
        LingShuLog.d(TAG, "[$traceId] [录音] 记录输出文件: ${outputFile.absolutePath}")

        // 1. 存储空间检查
        val (freeBytes, freeStr) = checkStorageSpace()
        LingShuLog.d(TAG, "[$traceId] [录音] 可用空间: $freeStr")
        if (freeBytes < RECORDING_MIN_STORAGE) {
            LingShuLog.e(TAG, "[$traceId] [录音] ❌ 存储空间不足，需要至少 50MB")
            currentRecordingFile = null
            return Result.error(ErrorCodes.STORAGE_INSUFFICIENT,
                    ErrorCodes.getMessage(ErrorCodes.STORAGE_INSUFFICIENT))
        }

        return try {
            withContext(Dispatchers.Main) {
                // 确保输出目录存在
                outputFile.parentFile?.mkdirs()

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
            LingShuLog.e(TAG, "[$traceId] [录音] ❌ 录音启动失败（模拟器无麦克风时会到此分支）", e)
            cleanupRecorder(traceId)
            currentRecordingFile = null
            Result.error(
                ErrorCodes.MICROPHONE_UNAVAILABLE,
                ErrorCodes.getMessage(ErrorCodes.MICROPHONE_UNAVAILABLE),
                e
            )
        }
    }

    override suspend fun stopRecording(): Result<File> {
        val traceId = "stp_${System.currentTimeMillis()}"
        val duration = System.currentTimeMillis() - recordingStartTime
        LingShuLog.i(TAG, "[$traceId] ===== 停止录音，录制时长=${duration}ms (${duration/1000}s) =====")

        val targetFile = currentRecordingFile

        if (!isRecording || mediaRecorder == null) {
            LingShuLog.w(TAG, "[$traceId] [录音] 当前未在录音状态，isRecording=$isRecording")
            currentRecordingFile = null
            return Result.error(ErrorCodes.MICROPHONE_UNAVAILABLE, "当前未在录音")
        }

        return try {
            withContext(Dispatchers.Main) {
                val stopStart = System.currentTimeMillis()
                mediaRecorder?.stop()
                LingShuLog.d(TAG, "[$traceId] [录音] stop() 完成，耗时 ${System.currentTimeMillis()-stopStart}ms")
            }

            // 录音时长校验
            if (duration < 10000) {
                LingShuLog.w(TAG, "[$traceId] [录音] ⚠ 录制时长不足10秒 (${duration/1000}s)，" +
                        "建议 10-30 秒以获得更好的克隆效果")
            } else if (duration > 30000) {
                LingShuLog.w(TAG, "[$traceId] [录音] ⚠ 录制时长超过30秒 (${duration/1000}s)，" +
                        "过长可能影响处理速度")
            }

            cleanupRecorder(traceId)

            // 直接返回 startRecording 时记录的输出文件，不再按修改时间查找
            if (targetFile == null || !targetFile.exists() || targetFile.length() == 0L) {
                LingShuLog.e(TAG, "[$traceId] [录音] ❌ 录音文件无效: ${targetFile?.absolutePath}")
                currentRecordingFile = null
                return Result.error(ErrorCodes.MICROPHONE_UNAVAILABLE, "录音文件无效")
            }

            LingShuLog.d(TAG, "[$traceId] [录音] 输出文件大小: ${targetFile.length()} bytes")
            currentRecordingFile = null

            LingShuLog.i(TAG, "[$traceId] [录音] ✅ 录音完成，文件=${targetFile.absolutePath}，" +
                    "大小=${formatFileSize(targetFile.length())}")
            Result.success(targetFile)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "[$traceId] [录音] ❌ 停止录音失败", e)
            cleanupRecorder(traceId)
            currentRecordingFile = null
            Result.error(ErrorCodes.MICROPHONE_UNAVAILABLE, "停止录音失败", e)
        }
    }

    override fun isRecording(): Boolean = isRecording

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
        LingShuLog.d(TAG, "[$traceId] [克隆-步骤1/6] 基础参数校验...")
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
        LingShuLog.d(TAG, "[$traceId] [克隆-步骤2/6] 存储空间检查...")
        val (freeBytes, freeStr) = checkStorageSpace()
        LingShuLog.d(TAG, "[$traceId] [克隆-步骤2] 可用空间: $freeStr")
        if (freeBytes < REQUIRED_MIN_STORAGE) {
            LingShuLog.e(TAG, "[$traceId] [克隆-步骤2] ❌ 存储空间不足，需要至少 500MB")
            return Result.error(ErrorCodes.STORAGE_INSUFFICIENT,
                    ErrorCodes.getMessage(ErrorCodes.STORAGE_INSUFFICIENT))
        }

        // 3. 准备 voice 目录
        LingShuLog.d(TAG, "[$traceId] [克隆-步骤3/6] 准备声音目录...")
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

        // 4. 拷贝样本音频（保存用户录制的声音样本到 voiceDir/sample.wav）
        LingShuLog.d(TAG, "[$traceId] [克隆-步骤4/6] 拷贝样本音频...")
        val copyStart = System.currentTimeMillis()
        val copyResult = copyFile(audioFile, sampleFile)
        LingShuLog.d(TAG, "[$traceId] [克隆-步骤4] 拷贝耗时=${System.currentTimeMillis()-copyStart}ms, " +
                "结果=$copyResult, " +
                "目标大小=${sampleFile.length()} bytes")
        if (!copyResult) {
            cleanupVoiceDir(voiceDir, traceId)
            return Result.error(ErrorCodes.VOICE_CLONE_FAILED, "样本文件保存失败")
        }

        // 5. 样本保存模式：不进行真实模型训练，仅保存用户录制的声音样本。
        //    modelDir 已创建（空目录），预留给后续真实模型训练接入。
        LingShuLog.i(TAG, "[$traceId] [克隆-步骤5/6] 保存录音样本（样本模式，无需模型训练）")
        LingShuLog.d(TAG, "[$traceId] [克隆-步骤5] sample=${sampleFile.absolutePath} " +
                "(${formatFileSize(sampleFile.length())}), modelDir=${modelDir.absolutePath} (空)")

        // 6. 写入 metadata 并加入内存列表
        LingShuLog.d(TAG, "[$traceId] [克隆-步骤6/6] 写入元数据并加入内存列表...")
        val voiceName = "我的声音_${SimpleDateFormat("MMddHHmm", Locale.getDefault()).format(Date())}"
        val metadata = mapOf(
            "id" to voiceId,
            "name" to voiceName,
            "samplePath" to sampleFile.absolutePath,
            "modelPath" to modelDir.absolutePath,
            "createdAt" to System.currentTimeMillis(),
            "durationSec" to duration,
            "type" to "sample",
            // 音色配置：用户录音使用默认 Voice + 默认 pitch/rate
            "voiceName" to "",
            "pitch" to "1.0",
            "rate" to "1.0",
            "isSystemVoice" to "false",
            "traceId" to traceId
        )
        writeMetadata(metadataFile, metadata)
        LingShuLog.d(TAG, "[$traceId] [克隆-步骤6] metadata=${metadata}")

        val newVoice = Voice(
            id = voiceId,
            name = voiceName,
            modelPath = modelDir.absolutePath,
            samplePath = sampleFile.absolutePath,
            createdAt = System.currentTimeMillis(),
            // 用户录制的自定义声音：默认 Voice + 默认 pitch/rate
            voiceName = null,
            pitch = 1.0f,
            rate = 1.0f,
            isSystemVoice = false
        )
        voices.add(newVoice)

        val totalCost = System.currentTimeMillis() - startTime
        LingShuLog.i(TAG, "[$traceId] ========== cloneAudio 成功 ==========")
        LingShuLog.i(TAG, "[$traceId] [克隆-汇总] voiceId=$voiceId, name=$voiceName")
        LingShuLog.i(TAG, "[$traceId] [克隆-汇总] 总耗时=${totalCost}ms")
        LingShuLog.i(TAG, "[$traceId] [克隆-汇总] 当前共有 ${voices.size} 个声音")

        return Result.success(voiceId)
    }

    // ========================================
    // 其他接口 - 详细埋点
    // ========================================

    override suspend fun setCurrentVoice(voiceId: String): Result<Unit> {
        // 选中即应用：转发到 applyVoice，将音色配置写入 TTS 引擎
        return applyVoice(voiceId)
    }

    /**
     * 应用指定音色到 TTS 引擎并切换为当前音色。
     * 读取 voice 的 voiceName/pitch/rate，调用 ttsRouter.setVoiceConfig 生效。
     */
    override suspend fun applyVoice(voiceId: String): Result<Unit> {
        val traceId = "apv_${System.currentTimeMillis()}"
        LingShuLog.i(TAG, "[$traceId] applyVoice: voiceId=$voiceId")
        LingShuLog.d(TAG, "[$traceId] 当前=${currentVoice?.id}(${currentVoice?.name}), " +
                "共有 ${voices.size} 个可选")

        return try {
            val voice = voices.find { it.id == voiceId }
            if (voice == null) {
                LingShuLog.e(TAG, "[$traceId] ❌ 声音不存在，可选 IDs=${voices.map { it.id }}")
                return Result.error(ErrorCodes.UNKNOWN_ERROR, "声音不存在: $voiceId")
            }

            // 检查模型目录（系统音色 modelPath 为空，跳过检查）
            if (voice.modelPath.isNotEmpty()) {
                val modelDir = File(voice.modelPath)
                val sampleExists = File(voice.samplePath).exists()
                LingShuLog.d(TAG, "[$traceId] 模型目录存在=${modelDir.exists()}, " +
                        "样本文件存在=$sampleExists, " +
                        "模型文件数=${modelDir.listFiles()?.size ?: 0}")
            } else {
                LingShuLog.d(TAG, "[$traceId] 系统音色，跳过模型目录检查")
            }

            currentVoice = voice

            // 应用音色配置到 TTS 引擎（系统 Voice 名 + pitch + rate）
            // Android 系统 TTS 不支持用录音做真实声音克隆，通过 pitch/rate/voice 调整音色
            try {
                ttsRouter.setVoiceConfig(voice.voiceName, voice.pitch, voice.rate)
                LingShuLog.i(TAG, "[$traceId] ✅ 已应用音色到 TTS: ${voice.name} " +
                        "(voiceName=${voice.voiceName}, pitch=${voice.pitch}, rate=${voice.rate})")
            } catch (e: Exception) {
                // 应用失败不影响选中状态，TTS 将沿用上一次配置
                LingShuLog.w(TAG, "[$traceId] 应用音色配置异常（不影响选中）", e)
            }

            LingShuLog.i(TAG, "[$traceId] ✅ 切换成功: ${voice.name} (id=${voice.id})")
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

            // 系统音色无物理文件，跳过物理删除
            if (deletedVoice?.isSystemVoice != true) {
                LingShuLog.d(TAG, "[$traceId] 删除物理文件: ${deletedVoice?.modelPath?.substringBeforeLast("/")}")
                val voiceDir = File(voicesDir, voiceId)
                if (voiceDir.exists()) {
                    val deleted = deleteRecursive(voiceDir)
                    LingShuLog.d(TAG, "[$traceId] 物理删除结果: $deleted")
                }
            } else {
                LingShuLog.d(TAG, "[$traceId] 系统音色，仅移除内存引用")
            }

            // 如果删除的是当前声音，回退到第一个
            if (currentVoice?.id == voiceId) {
                currentVoice = voices.firstOrNull()
                LingShuLog.w(TAG, "[$traceId] 已删除当前声音，自动切换到: ${currentVoice?.name ?: "null"}")
            }

            LingShuLog.i(TAG, "[$traceId] ✅ 删除成功: ${deletedVoice?.name}，剩余 ${voices.size} 个")
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
            if (text.isBlank()) {
                LingShuLog.w(TAG, "[$traceId] ⚠ 试听文本为空")
                return Result.error(ErrorCodes.TTS_UNAVAILABLE, "试听文本为空")
            }
            LingShuLog.d(TAG, "[$traceId] 目标声音: name=${voice.name}, model=${voice.modelPath}")

            // 1. 准备合成输出文件
            val previewDir = File(context.cacheDir, "tts_preview").apply { mkdirs() }
            val audioFile = File(previewDir, "preview_${System.currentTimeMillis()}.wav")

            // 2. 优先使用 OfflineTtsRouter 合成（内部自带 Android 系统 TTS 兜底链）
            var synthResult = synthesizeViaRouter(text, audioFile, traceId)

            // 3. Router 不可用时，降级到 Android 系统 TextToSpeech
            if (!synthResult.isSuccess) {
                LingShuLog.w(TAG, "[$traceId] [试听] Router 合成失败，降级到系统 TextToSpeech")
                synthResult = synthesizeViaAndroidTts(text, audioFile, traceId)
            }

            if (!synthResult.isSuccess) {
                val err = synthResult.errorOrNull()
                LingShuLog.e(TAG, "[$traceId] ❌ 试听合成失败: code=${err?.code}, msg=${err?.message}")
                return Result.error(err?.code ?: ErrorCodes.TTS_UNAVAILABLE,
                        err?.message ?: "语音合成失败")
            }

            val synthesizedFile = synthResult.getOrNull()!!
            LingShuLog.d(TAG, "[$traceId] [试听] 合成文件: ${synthesizedFile.absolutePath}, " +
                    "大小=${formatFileSize(synthesizedFile.length())}")

            // 4. 用 MediaPlayer 播放合成的音频
            val played = playAudioFile(synthesizedFile, traceId)

            val cost = System.currentTimeMillis() - startTime
            LingShuLog.i(TAG, "[$traceId] ✅ 预览完成，耗时=${cost}ms, 播放=$played")
            Result.success(Unit)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "[$traceId] ❌ 预览失败", e)
            Result.error(ErrorCodes.TTS_UNAVAILABLE, "预览失败", e)
        }
    }

    // ========================================
    // TTS 合成与播放
    // ========================================

    /**
     * 通过 OfflineTtsRouter 合成音频到指定文件。
     * Router 内部降级链：Android 系统 TTS -> ChatTTS -> EdgeTTS。
     */
    private suspend fun synthesizeViaRouter(
        text: String,
        outputFile: File,
        traceId: String
    ): Result<File> {
        return try {
            if (!ttsRouter.isLoaded()) {
                val config = OfflineTtsConfig(
                    provider = OfflineTtsProvider.ANDROID_TTS,
                    modelDir = "",
                    voiceId = "default",
                    speed = 1.0f,
                    sampleRate = 24000
                )
                val loadResult = ttsRouter.load(config, "$traceId-LOAD")
                if (!loadResult.isSuccess) {
                    LingShuLog.w(TAG, "[$traceId] [试听] TTS 引擎加载失败: ${loadResult.errorOrNull()?.message}")
                    return Result.error(ErrorCodes.TTS_UNAVAILABLE, "TTS 引擎加载失败")
                }
            }
            ttsRouter.synthesize(text, outputFile, "$traceId-SYNTH")
        } catch (e: Exception) {
            LingShuLog.e(TAG, "[$traceId] [试听] Router 合成异常", e)
            Result.error(ErrorCodes.TTS_UNAVAILABLE, "Router 合成异常", e)
        }
    }

    /**
     * 直接使用 Android 系统 TextToSpeech 合成音频（Router 不可用时的兜底）。
     */
    @Suppress("DEPRECATION")
    private suspend fun synthesizeViaAndroidTts(
        text: String,
        outputFile: File,
        traceId: String
    ): Result<File> = withContext(Dispatchers.Main) {
        LingShuLog.d(TAG, "[$traceId] [试听] 使用 Android 系统 TextToSpeech 合成")
        val initLatch = CountDownLatch(1)
        val initStatus = AtomicReference(TextToSpeech.ERROR)
        var tts: TextToSpeech? = null
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                initStatus.set(status)
                initLatch.countDown()
            }
            if (!initLatch.await(10, TimeUnit.SECONDS) || initStatus.get() != TextToSpeech.SUCCESS) {
                return@withContext Result.error(ErrorCodes.TTS_UNAVAILABLE, "系统 TTS 初始化失败")
            }
            tts.language = Locale.SIMPLIFIED_CHINESE

            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()

            val doneLatch = CountDownLatch(1)
            val errorRef = AtomicReference<String?>(null)
            val uttId = "clone_preview_${System.currentTimeMillis()}"
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { doneLatch.countDown() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    errorRef.set("synthesize failed")
                    doneLatch.countDown()
                }
            })
            val params = android.os.Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uttId)
            }
            val r = tts.synthesizeToFile(text, params, outputFile, uttId)
            if (r != TextToSpeech.SUCCESS) {
                return@withContext Result.error(ErrorCodes.TTS_UNAVAILABLE, "synthesizeToFile 失败: $r")
            }
            if (!doneLatch.await(30, TimeUnit.SECONDS)) {
                return@withContext Result.error(ErrorCodes.TTS_UNAVAILABLE, "系统 TTS 合成超时")
            }
            val err = errorRef.get()
            if (err != null) {
                return@withContext Result.error(ErrorCodes.TTS_UNAVAILABLE, err)
            }
            Result.success(outputFile)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "[$traceId] [试听] Android TTS 兜底异常", e)
            Result.error(ErrorCodes.TTS_UNAVAILABLE, "系统 TTS 异常", e)
        } finally {
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
        }
    }

    /**
     * 使用 MediaPlayer 播放合成的音频文件，播放完成后返回。
     */
    private suspend fun playAudioFile(file: File, traceId: String): Boolean {
        if (!file.exists() || file.length() == 0L) {
            LingShuLog.w(TAG, "[$traceId] [试听] 音频文件无效，跳过播放")
            return false
        }
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val player = MediaPlayer()
                try {
                    player.setDataSource(file.absolutePath)
                    player.setOnCompletionListener { mp ->
                        LingShuLog.d(TAG, "[$traceId] [试听] 播放完成")
                        runCatching { mp.release() }
                        if (cont.isActive) cont.resume(true)
                    }
                    player.setOnErrorListener { mp, what, extra ->
                        LingShuLog.e(TAG, "[$traceId] [试听] 播放出错 what=$what extra=$extra")
                        runCatching { mp.release() }
                        if (cont.isActive) cont.resume(false)
                        true
                    }
                    player.prepare()
                    player.start()
                    LingShuLog.d(TAG, "[$traceId] [试听] 开始播放: ${file.absolutePath}")
                } catch (e: Exception) {
                    LingShuLog.e(TAG, "[$traceId] [试听] MediaPlayer 异常", e)
                    runCatching { player.release() }
                    if (cont.isActive) cont.resume(false)
                }
                cont.invokeOnCancellation {
                    runCatching { player.release() }
                }
            }
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

    /** 将 metadata 值解析为 Long，失败返回 null。 */
    private fun parseLong(value: Any?): Long? = value?.toString()?.toLongOrNull()

    /** 将 metadata 值解析为 Float，失败返回 null。 */
    private fun parseFloat(value: Any?): Float? = value?.toString()?.toFloatOrNull()

    /** 将 metadata 值解析为 Boolean（仅 "true" 视为真）。 */
    private fun parseBoolean(value: Any?): Boolean = value?.toString()?.lowercase() == "true"

    /** 将 metadata 值解析为可空字符串：空串/"null" 视为 null。 */
    private fun parseNullableString(value: Any?): String? {
        val s = value?.toString()?.trim()
        if (s.isNullOrEmpty() || s.equals("null", ignoreCase = true)) return null
        return s
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

    // ==================== Day2-2：音色库分享（导入 / 导出 / 快捷创建） ====================
    override suspend fun importPreset(file: File): Result<String> = withContext(Dispatchers.IO) {
        val traceId = "imp_${System.currentTimeMillis()}"
        LingShuLog.i(TAG, "[$traceId] importPreset: file=${file.absolutePath}")
        val preset = runCatching { parsePresetJson(file.readText()) }.getOrElse { e ->
            LingShuLog.e(TAG, "[$traceId] preset JSON 解析失败", e)
            return@withContext Result.error(ErrorCodes.VOICE_CLONE_FAILED, "音色预设文件解析失败: ${e.message}")
        }
        val voiceId = "preset_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val voiceDir = File(voicesDir, voiceId).apply { mkdirs() }
        val metadataFile = File(voiceDir, METADATA_FILE_NAME)
        val metadata = mapOf(
            "id" to voiceId,
            "name" to preset.name,
            "samplePath" to "",
            "modelPath" to voiceDir.absolutePath,
            "createdAt" to System.currentTimeMillis(),
            "type" to "preset",
            "voiceName" to (preset.voiceName ?: ""),
            "pitch" to preset.pitch.toString(),
            "rate" to preset.rate.toString(),
            "isSystemVoice" to "false",
            "author" to preset.author,
            "description" to preset.description,
            "tags" to preset.tags.joinToString(","),
            "traceId" to traceId
        )
        writeMetadata(metadataFile, metadata)

        val newVoice = Voice(
            id = voiceId,
            name = preset.name,
            modelPath = voiceDir.absolutePath,
            samplePath = "",
            createdAt = System.currentTimeMillis(),
            voiceName = preset.voiceName,
            pitch = preset.pitch.coerceIn(0.5f, 2.0f),
            rate = preset.rate.coerceIn(0.5f, 2.0f),
            isSystemVoice = false,
            author = preset.author,
            description = preset.description,
            tags = preset.tags
        )
        voices.add(newVoice)
        LingShuLog.i(TAG, "[$traceId] 导入成功: id=$voiceId, 作者=${preset.author}")
        Result.success(voiceId)
    }

    override suspend fun exportPreset(voiceId: String, targetFile: File): Result<File> = withContext(Dispatchers.IO) {
        val traceId = "exp_${System.currentTimeMillis()}"
        val voice = voices.find { it.id == voiceId }
            ?: return@withContext Result.error(ErrorCodes.VOICE_CLONE_FAILED, "音色不存在: $voiceId")
        val preset = com.lingshu.feature.clonevoice.domain.VoicePresetFile(
            formatVersion = 1,
            name = voice.name,
            author = voice.author ?: "Anonymous",
            description = voice.description ?: "",
            tags = voice.tags,
            voiceName = voice.voiceName,
            pitch = voice.pitch,
            rate = voice.rate,
            sampleBase64 = null,
            createdAt = voice.createdAt
        )
        runCatching {
            targetFile.writeText(toPresetJson(preset))
        }.onFailure { e ->
            LingShuLog.e(TAG, "[$traceId] 导出失败", e)
            return@withContext Result.error(ErrorCodes.VOICE_CLONE_FAILED, "导出失败: ${e.message}")
        }
        LingShuLog.i(TAG, "[$traceId] 导出成功: ${targetFile.absolutePath}")
        Result.success(targetFile)
    }

    override suspend fun createCustomPreset(
        name: String,
        author: String,
        description: String,
        tags: List<String>,
        voiceName: String?,
        pitch: Float,
        rate: Float
    ): Result<String> = withContext(Dispatchers.IO) {
        val traceId = "cvp_${System.currentTimeMillis()}"
        val voiceId = "custom_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val voiceDir = File(voicesDir, voiceId).apply { mkdirs() }
        val metadataFile = File(voiceDir, METADATA_FILE_NAME)
        val metadata = mapOf(
            "id" to voiceId,
            "name" to name,
            "samplePath" to "",
            "modelPath" to voiceDir.absolutePath,
            "createdAt" to System.currentTimeMillis(),
            "type" to "custom",
            "voiceName" to (voiceName ?: ""),
            "pitch" to pitch.toString(),
            "rate" to rate.toString(),
            "isSystemVoice" to "false",
            "author" to author,
            "description" to description,
            "tags" to tags.joinToString(",")
        )
        writeMetadata(metadataFile, metadata)
        val newVoice = Voice(
            id = voiceId,
            name = name,
            modelPath = voiceDir.absolutePath,
            samplePath = "",
            createdAt = System.currentTimeMillis(),
            voiceName = voiceName,
            pitch = pitch.coerceIn(0.5f, 2.0f),
            rate = rate.coerceIn(0.5f, 2.0f),
            isSystemVoice = false,
            author = author,
            description = description,
            tags = tags
        )
        voices.add(newVoice)
        LingShuLog.i(TAG, "[$traceId] 创建自定义音色: id=$voiceId, name=$name")
        Result.success(voiceId)
    }

    // ========== JSON 辅助（VoicePreset 手写解析/序列化，避免引入 Moshi 依赖） ==========
    private fun parsePresetJson(json: String): com.lingshu.feature.clonevoice.domain.VoicePresetFile {
        fun str(k: String): String? = Regex(""""$k"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            .find(json)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")?.replace("\\\\", "\\")
        fun num(k: String): Float? = Regex(""""$k"\s*:\s*(-?[\d.]+)""")
            .find(json)?.groupValues?.get(1)?.toFloatOrNull()
        fun int(k: String): Int? = Regex(""""$k"\s*:\s*(-?\d+)""")
            .find(json)?.groupValues?.get(1)?.toIntOrNull()
        fun arr(k: String): List<String> {
            val outer = Regex(""""$k"\s*:\s*(\[[\s\S]*?\])""").find(json)?.groupValues?.get(1) ?: return emptyList()
            return Regex(""""([^"]*)"""").findAll(outer).map { it.groupValues[1] }.toList()
        }
        return com.lingshu.feature.clonevoice.domain.VoicePresetFile(
            formatVersion = int("formatVersion") ?: 1,
            name = str("name") ?: "未命名",
            author = str("author") ?: "Anonymous",
            description = str("description") ?: "",
            tags = arr("tags"),
            voiceName = str("voiceName"),
            pitch = num("pitch") ?: 1.0f,
            rate = num("rate") ?: 1.0f,
            sampleBase64 = str("sampleBase64"),
            createdAt = int("createdAt")?.toLong() ?: System.currentTimeMillis()
        )
    }

    private fun toPresetJson(p: com.lingshu.feature.clonevoice.domain.VoicePresetFile): String {
        fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val tags = p.tags.joinToString(",") { "\"${esc(it)}\"" }
        val sb = StringBuilder().append("{\n")
            .append("  \"formatVersion\": ${p.formatVersion},\n")
            .append("  \"name\": \"${esc(p.name)}\",\n")
            .append("  \"author\": \"${esc(p.author)}\",\n")
            .append("  \"description\": \"${esc(p.description)}\",\n")
            .append("  \"tags\": [$tags],\n")
            .append("  \"voiceName\": ${p.voiceName?.let { "\"${esc(it)}\"" } ?: "null"},\n")
            .append("  \"pitch\": ${p.pitch},\n")
            .append("  \"rate\": ${p.rate},\n")
            .append("  \"createdAt\": ${p.createdAt}\n")
            .append("}")
        return sb.toString()
    }

    private fun formatDate(ts: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
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
        private const val SYSTEM_VOICE_ID = "system_default"

        private const val REQUIRED_MIN_STORAGE = 500L * 1024 * 1024   // 500MB
        private const val RECORDING_MIN_STORAGE = 50L * 1024 * 1024    // 50MB
        private const val MIN_AUDIO_SIZE = 50L * 1024                   // 50KB
    }
}
