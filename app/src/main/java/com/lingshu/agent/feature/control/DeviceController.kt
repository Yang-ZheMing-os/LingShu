package com.lingshu.agent.feature.control

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.lingshu.agent.services.LingShuAccessibilityService
import java.io.File

/**
 * 设备控制器（模块7：手机控制）
 *
 * 封装无障碍服务调用，解析用户自然语言指令并执行 Android 操作。
 *
 * 支持的指令模式：
 * - "打开XX" → 按应用名或包名启动
 * - "返回/退出/回退" → performGlobalAction BACK
 * - "主页/桌面" → performGlobalAction HOME
 * - "滑动上/下/左/右" → dispatchSwipe
 * - "点击XX" → clickText / clickAt
 * - "输入XX" → inputText
 * - "截图" → screencap
 */
class DeviceController(private val context: Context) {

    companion object {
        private const val TAG = "DeviceController"
        private const val SCREENSHOT_DIR = "DeviceScreenshots"

        /** 检查无障碍服务是否正在运行 */
        fun isAccessibilityServiceRunning(): Boolean = LingShuAccessibilityService.isConnected()
    }

    /**
     * 获取无障碍服务实例，未运行返回 null
     */
    private fun service(): LingShuAccessibilityService? = LingShuAccessibilityService.instance

    /**
     * 解析并执行用户指令
     */
    fun execute(command: String): DeviceActionResult {
        val normalized = command.trim()

        if (!isAccessibilityServiceRunning()) {
            return DeviceActionResult(
                success = false,
                action = "执行失败",
                message = "无障碍服务未开启，请在 设置 → 无障碍 → 灵枢助手 中开启。"
            )
        }

        val svc = service() ?: return DeviceActionResult(
            success = false,
            action = "执行失败",
            message = "无障碍服务连接丢失，请重新开启。"
        )

        return try {
            when {
                // ============ 返回/退出/回退 ============
                normalized.let { it == "返回" || it == "退出" || it == "回退" || it.startsWith("返回") } -> {
                    val ok = svc.pressBack()
                    DeviceActionResult(ok, "返回", if (ok) "已执行返回" else "返回失败")
                }

                // ============ 主页/桌面 ============
                normalized.let { it == "主页" || it == "桌面" || it.startsWith("回到") } -> {
                    val ok = svc.pressHome()
                    DeviceActionResult(ok, "主页", if (ok) "已回到主页" else "操作失败")
                }

                // ============ 最近任务 ============
                normalized == "最近任务" || normalized == "多任务" -> {
                    val ok = svc.pressRecents()
                    DeviceActionResult(ok, "最近任务", if (ok) "已打开最近任务" else "操作失败")
                }

                // ============ 通知栏 ============
                normalized == "通知" || normalized == "通知栏" || normalized == "下拉通知" -> {
                    val ok = svc.openNotifications()
                    DeviceActionResult(ok, "通知栏", if (ok) "已打开通知栏" else "操作失败")
                }

                // ============ 打开应用 ============
                normalized.startsWith("打开") -> {
                    val target = normalized.removePrefix("打开").trim()
                    openApp(svc, target)
                }

                normalized.startsWith("启动") -> {
                    val target = normalized.removePrefix("启动").trim()
                    openApp(svc, target)
                }

                // ============ 滑动操作 ============
                normalized.startsWith("滑动上") || normalized == "向上滑" -> {
                    val ok = svc.dispatchSwipe(540f, 1500f, 540f, 500f, 400)
                    DeviceActionResult(ok, "向上滑动", if (ok) "已向上滑动" else "滑动失败")
                }

                normalized.startsWith("滑动下") || normalized == "向下滑" -> {
                    val ok = svc.dispatchSwipe(540f, 500f, 540f, 1500f, 400)
                    DeviceActionResult(ok, "向下滑动", if (ok) "已向下滑动" else "滑动失败")
                }

                normalized.startsWith("滑动左") || normalized == "向左滑" -> {
                    val ok = svc.dispatchSwipe(900f, 1000f, 100f, 1000f, 400)
                    DeviceActionResult(ok, "向左滑动", if (ok) "已向左滑动" else "滑动失败")
                }

                normalized.startsWith("滑动右") || normalized == "向右滑" -> {
                    val ok = svc.dispatchSwipe(100f, 1000f, 900f, 1000f, 400)
                    DeviceActionResult(ok, "向右滑动", if (ok) "已向右滑动" else "滑动失败")
                }

                // ============ 点击文本控件 ============
                normalized.startsWith("点击") -> {
                    val target = normalized.removePrefix("点击").trim()
                    // 尝试按坐标解析 "点击 540 1000"
                    val coords = target.split("\\s+".toRegex())
                    if (coords.size >= 2 && coords[0].toIntOrNull() != null && coords[1].toIntOrNull() != null) {
                        val x = coords[0].toFloat()
                        val y = coords[1].toFloat()
                        val ok = svc.dispatchClick(x, y, 50)
                        DeviceActionResult(ok, "点击坐标", if (ok) "已点击($x, $y)" else "点击失败")
                    } else {
                        // 按文本查找点击
                        val ok = svc.clickText(target)
                        DeviceActionResult(ok, "点击\"$target\"", if (ok) "已点击\"$target\"" else "未找到\"$target\"")
                    }
                }

                // ============ 输入文本 ============
                normalized.startsWith("输入") -> {
                    val text = normalized.removePrefix("输入").trim()
                    val ok = svc.inputText(text)
                    DeviceActionResult(ok, "输入文本", if (ok) "已输入\"$text\"" else "输入失败，请确保焦点在输入框")
                }

                // ============ 截图 ============
                normalized == "截图" -> {
                    takeScreenshot()
                }

                // ============ 未匹配指令 ============
                else -> {
                    DeviceActionResult(
                        success = false,
                        action = "未知指令",
                        message = "无法识别指令「$normalized」。支持的操作：打开XX/返回/主页/滑动/点击XX/输入XX/截图"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行指令失败: $command", e)
            DeviceActionResult(
                success = false,
                action = "执行异常",
                message = "执行「$command」时出错: ${e.message}"
            )
        }
    }

    /**
     * 按应用名或包名启动应用
     */
    private fun openApp(svc: LingShuAccessibilityService, target: String): DeviceActionResult {
        // 先尝试通过无障碍服务点击桌面搜索 + 输入
        svc.pressHome()
        Thread.sleep(300)

        // 尝试通过包名直接启动
        val pkg = resolvePackage(target)
        if (pkg != null) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return DeviceActionResult(true, "打开\"$target\"", "已启动应用「$target」")
            }
        }

        // 回退：通过无障碍点击桌面上可能存在的应用图标
        val clicked = svc.clickText(target)
        return if (clicked) {
            DeviceActionResult(true, "打开\"$target\"", "已尝试打开「$target」")
        } else {
            val searchClicked = svc.clickText("搜索")
            if (searchClicked) {
                Thread.sleep(500)
                svc.inputText(target)
                Thread.sleep(800)
                DeviceActionResult(
                    success = true,
                    action = "打开\"$target\"",
                    message = "已在搜索框中搜索「$target」，请点选结果。"
                )
            } else {
                DeviceActionResult(
                    success = false,
                    action = "打开\"$target\"",
                    message = "无法启动「$target」，请确认应用已安装。"
                )
            }
        }
    }

    /**
     * 常见应用名 → 包名映射
     */
    private fun resolvePackage(name: String): String? {
        val mapping = mapOf(
            "微信" to "com.tencent.mm",
            "qq" to "com.tencent.mobileqq",
            "支付宝" to "com.eg.android.AlipayGphone",
            "淘宝" to "com.taobao.taobao",
            "抖音" to "com.ss.android.ugc.aweme",
            "微博" to "com.sina.weibo",
            "美团" to "com.sankuai.meituan",
            "高德地图" to "com.autonavi.minimap",
            "百度地图" to "com.baidu.BaiduMap",
            "京东" to "com.jingdong.app.mall",
            "拼多多" to "com.xunmeng.pinduoduo",
            "哔哩哔哩" to "tv.danmaku.bili",
            "头条" to "com.ss.android.article.news",
            "知乎" to "com.zhihu.android",
            "网易云音乐" to "com.netease.cloudmusic",
            "QQ音乐" to "com.tencent.qqmusic",
            "计算器" to "com.android.calculator2",
            "日历" to "com.android.calendar",
            "时钟" to "com.android.deskclock",
            "设置" to "com.android.settings",
            "相机" to "com.android.camera",
            "相册" to "com.android.gallery3d",
            "文件管理" to "com.android.documentsui",
        )

        val lower = name.lowercase()
        mapping.forEach { (label, pkg) ->
            if (lower == label.lowercase()) return pkg
        }

        // 如果输入看起来像包名（含 '.'），直接返回
        if (name.contains(".")) return name

        // 遍历已安装应用匹配
        val pm = context.packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (app in installed) {
            val label = pm.getApplicationLabel(app).toString().lowercase()
            if (label.contains(lower)) return app.packageName
        }
        return null
    }

    /**
     * 截取屏幕到文件
     */
    private fun takeScreenshot(): DeviceActionResult {
        return try {
            val dir = File(context.getExternalFilesDir(null), SCREENSHOT_DIR)
            if (!dir.exists()) dir.mkdirs()

            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val file = File(dir, "screenshot_$timestamp.png")

            val process = Runtime.getRuntime().exec(
                arrayOf("screencap", "-p", file.absolutePath)
            )
            process.waitFor()

            if (file.exists() && file.length() > 0) {
                DeviceActionResult(
                    success = true,
                    action = "截图",
                    message = "截图已保存",
                    screenshotPath = file.absolutePath
                )
            } else {
                DeviceActionResult(
                    success = false,
                    action = "截图",
                    message = "截图失败：无法生成截图文件"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "截图失败", e)
            DeviceActionResult(
                success = false,
                action = "截图",
                message = "截图失败: ${e.message}"
            )
        }
    }
}
