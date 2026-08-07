package com.lingshu.agent.feature.control

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * 屏幕截图与OCR管理器
 *
 * 功能：
 * 1. MediaProjection API 截屏（需要用户授权）
 * 2. OCR文字识别（预留ML Kit / Tesseract接口）
 *
 * 使用方式：
 * 1. 调用 [createScreenCaptureIntent] 获取授权Intent
 * 2. 在Activity的 onActivityResult 中调用 [initialize]
 * 3. 调用 [captureScreen] 获取截屏Bitmap
 */
@Singleton
class ScreenCaptureManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** MediaProjection管理器 */
    private val mediaProjectionManager: MediaProjectionManager by lazy {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    /** MediaProjection实例 */
    private var mediaProjection: MediaProjection? = null

    /** 虚拟显示器 */
    private var virtualDisplay: VirtualDisplay? = null

    /** 图片读取器 */
    private var imageReader: ImageReader? = null

    /** 屏幕宽度 */
    private var screenWidth: Int = 0

    /** 屏幕高度 */
    private var screenHeight: Int = 0

    /** 屏幕密度 */
    private var screenDensity: Int = 0

    /** 初始化是否完成 */
    private var isInitialized: Boolean = false

    /** 主线程Handler */
    private val mainHandler = Handler(Looper.getMainLooper())

    // ==================== 授权与初始化 ====================

    /**
     * 创建屏幕捕捉授权Intent
     * 需要使用此Intent启动一个Activity（通过 startActivityForResult 或 ActivityResultLauncher）
     * @return 授权Intent
     */
    fun createScreenCaptureIntent(): Intent {
        return mediaProjectionManager.createScreenCaptureIntent()
    }

    /**
     * 初始化截屏功能（在获得用户授权后调用）
     *
     * @param resultCode onActivityResult返回的resultCode，应为 Activity.RESULT_OK
     * @param resultData onActivityResult返回的Intent数据
     * @return 是否初始化成功
     */
    fun initialize(resultCode: Int, resultData: Intent): Boolean {
        if (resultCode != Activity.RESULT_OK) {
            return false
        }

        release()

        return try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi

            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
                ?: return false

            imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "LingShuScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                mainHandler
            )

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    release()
                }
            }, mainHandler)

            isInitialized = true
            true
        } catch (e: Exception) {
            release()
            false
        }
    }

    /**
     * 是否已初始化
     */
    fun isReady(): Boolean = isInitialized && mediaProjection != null

    /**
     * 释放资源
     */
    fun release() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (_: Exception) {}
        try {
            imageReader?.close()
            imageReader = null
        } catch (_: Exception) {}
        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (_: Exception) {}
        isInitialized = false
    }

    // ==================== 截屏功能 ====================

    /**
     * 截屏回调
     */
    interface CaptureCallback {
        /** 截屏成功 */
        fun onSuccess(bitmap: Bitmap)
        /** 截屏失败 */
        fun onFailure(reason: String)
    }

    /**
     * 截取当前屏幕
     * @param callback 结果回调
     * @param timeoutMs 超时时间（毫秒），默认3000ms
     */
    fun captureScreen(
        callback: CaptureCallback,
        timeoutMs: Long = 3000L
    ) {
        if (!isReady()) {
            callback.onFailure("ScreenCapture未初始化，请先调用initialize()")
            return
        }

        val reader = imageReader ?: run {
            callback.onFailure("ImageReader不可用")
            return
        }

        var timeoutRunnable: Runnable? = null
        var completed = false

        val onCompleted = { success: Boolean, result: Bitmap?, reason: String? ->
            if (!completed) {
                completed = true
                timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                if (success && result != null) {
                    callback.onSuccess(result)
                } else {
                    callback.onFailure(reason ?: "截屏失败")
                }
            }
        }

        timeoutRunnable = Runnable {
            onCompleted(false, null, "截屏超时")
        }
        mainHandler.postDelayed(timeoutRunnable, timeoutMs)

        reader.setOnImageAvailableListener({ availableReader ->
            var image: Image? = null
            try {
                image = availableReader.acquireLatestImage()
                if (image == null) {
                    return@setOnImageAvailableListener
                }

                val bitmap = imageToBitmap(image)
                if (bitmap != null) {
                    onCompleted(true, bitmap, null)
                } else {
                    onCompleted(false, null, "Bitmap转换失败")
                }
            } catch (e: Exception) {
                onCompleted(false, null, "异常: ${e.message}")
            } finally {
                try {
                    image?.close()
                } catch (_: Exception) {}
            }
        }, mainHandler)
    }

    /**
     * 将Image对象转换为Bitmap
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        if (planes.isEmpty()) return null

        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        val width = screenWidth + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(width, screenHeight, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)

        return if (width == screenWidth) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
        }
    }

    /**
     * 同步截屏（在协程中使用更合适）
     * 注意：此方法会阻塞调用线程，请在后台线程调用
     */
    @Throws(Exception::class)
    suspend fun captureScreenSuspend(): Bitmap = kotlin.coroutines.suspendCoroutine { continuation ->
        captureScreen(object : CaptureCallback {
            override fun onSuccess(bitmap: Bitmap) {
                continuation.resumeWith(Result.success(bitmap))
            }

            override fun onFailure(reason: String) {
                continuation.resumeWith(Result.failure(Exception(reason)))
            }
        })
    }

    /**
     * 保存Bitmap到文件
     * @param bitmap 要保存的Bitmap
     * @param file 目标文件
     * @param format 图片格式，默认JPEG
     * @param quality 质量0-100，默认90
     * @return 是否保存成功
     */
    fun saveBitmap(
        bitmap: Bitmap,
        file: File,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = 90
    ): Boolean {
        return try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                bitmap.compress(format, quality, out)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ==================== OCR 接口 ====================

    /**
     * OCR识别结果
     */
    data class OcrResult(
        /** 识别到的完整文本 */
        val text: String,
        /** 识别到的文本块列表 */
        val blocks: List<OcrTextBlock>,
        /** 识别置信度 (0-1)，-1表示不支持 */
        val confidence: Float = -1f
    )

    /**
     * OCR文本块
     */
    data class OcrTextBlock(
        /** 文本内容 */
        val text: String,
        /** 边界框 (left, top, right, bottom) */
        val boundingBox: IntArray,
        /** 置信度 (0-1) */
        val confidence: Float = -1f,
        /** 语言代码 */
        val language: String? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is OcrTextBlock) return false
            if (text != other.text) return false
            if (!boundingBox.contentEquals(other.boundingBox)) return false
            if (confidence != other.confidence) return false
            if (language != other.language) return false
            return true
        }

        override fun hashCode(): Int {
            var result = text.hashCode()
            result = 31 * result + boundingBox.contentHashCode()
            result = 31 * result + confidence.hashCode()
            result = 31 * result + (language?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * OCR引擎接口
     * 预留接口，可接入 ML Kit、Tesseract、PaddleOCR 等
     */
    interface OcrEngine {
        /** 引擎名称 */
        val name: String

        /** 引擎是否可用 */
        fun isAvailable(): Boolean

        /**
         * 执行OCR识别
         * @param bitmap 要识别的图片
         * @param languages 识别语言列表，如 ["zh", "en"]
         */
        suspend fun recognize(
            bitmap: Bitmap,
            languages: List<String> = listOf("zh", "en")
        ): OcrResult
    }

    /** 当前使用的OCR引擎 */
    private var ocrEngine: OcrEngine? = null

    /**
     * 设置OCR引擎
     */
    fun setOcrEngine(engine: OcrEngine?) {
        ocrEngine = engine
    }

    /**
     * 是否有可用的OCR引擎
     */
    fun isOcrAvailable(): Boolean = ocrEngine?.isAvailable() == true

    /**
     * 截屏并OCR识别
     * @param languages 识别语言列表
     * @param callback 结果回调
     */
    fun captureAndOcr(
        languages: List<String> = listOf("zh", "en"),
        callback: (Result<OcrResult>) -> Unit
    ) {
        val engine = ocrEngine
        if (engine == null || !engine.isAvailable()) {
            callback(Result.failure(Exception("OCR引擎不可用，请先调用 setOcrEngine()")))
            return
        }

        captureScreen(object : CaptureCallback {
            override fun onSuccess(bitmap: Bitmap) {
                GlobalScope.launch(Dispatchers.Default) {
                        try {
                            val result = engine.recognize(bitmap, languages)
                            callback(Result.success(result))
                        } catch (e: Exception) {
                            callback(Result.failure(e))
                        } finally {
                            bitmap.recycle()
                        }
                    }
            }

            override fun onFailure(reason: String) {
                callback(Result.failure(Exception(reason)))
            }
        })
    }

    /**
     * 对指定Bitmap执行OCR
     */
    suspend fun ocrBitmap(
        bitmap: Bitmap,
        languages: List<String> = listOf("zh", "en")
    ): OcrResult {
        val engine = ocrEngine ?: throw IllegalStateException("OCR引擎未设置")
        if (!engine.isAvailable()) {
            throw IllegalStateException("OCR引擎不可用")
        }
        return engine.recognize(bitmap, languages)
    }

    // ==================== 默认占位OCR引擎 ====================

    /**
     * 占位OCR引擎（不做实际识别）
     * 接入真实OCR后请使用 setOcrEngine() 替换
     */
    class PlaceholderOcrEngine : OcrEngine {
        override val name: String = "Placeholder"

        override fun isAvailable(): Boolean = false

        override suspend fun recognize(
            bitmap: Bitmap,
            languages: List<String>
        ): OcrResult {
            return OcrResult(
                text = "",
                blocks = emptyList(),
                confidence = -1f
            )
        }
    }

    init {
        // 默认使用占位引擎
        ocrEngine = PlaceholderOcrEngine()
    }
}
