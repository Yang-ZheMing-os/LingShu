package com.lingshu.agent.utils

import android.os.Build

/**
 * 厂商适配引导文案
 * 根据设备制造商返回适配引导信息
 */
object ManufacturerAdapter {

    data class ManufacturerGuide(
        val manufacturer: String,
        val displayName: String,
        val guideSteps: List<String>,
        val extraNote: String = ""
    )

    fun getGuide(): ManufacturerGuide {
        val manufacturer = Build.MANUFACTURER.lowercase()

        return when {
            manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                ManufacturerGuide(
                    manufacturer = "huawei/honor",
                    displayName = "华为/荣耀",
                    guideSteps = listOf(
                        "打开「手机管家」→「启动管理」",
                        "找到「灵枢」→ 关闭「自动管理」",
                        "手动开启「自启动」「关联启动」「后台活动」三项",
                        "进入「设置」→「应用」→「权限」→「悬浮窗」→ 允许"
                    ),
                    extraNote = "如使用鸿蒙系统，还需在「应用启动管理」中设置为「手动管理」"
                )

            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ->
                ManufacturerGuide(
                    manufacturer = "xiaomi/redmi",
                    displayName = "小米/Redmi",
                    guideSteps = listOf(
                        "打开「设置」→「应用设置」→「应用管理」→「灵枢」",
                        "开启「自启动」",
                        "进入「权限管理」→ 开启「后台弹出界面」",
                        "进入「通知管理」→ 开启「锁屏显示」",
                        "进入「省电策略」→ 选择「无限制」"
                    ),
                    extraNote = "MIUI 系统建议同时关闭「内存清理白名单」中勾选灵枢"
                )

            manufacturer.contains("oppo") || manufacturer.contains("oneplus") ->
                ManufacturerGuide(
                    manufacturer = "oppo/oneplus",
                    displayName = "OPPO/一加",
                    guideSteps = listOf(
                        "打开「设置」→「应用」→「应用管理」→「灵枢」",
                        "进入「耗电保护」→ 开启「后台高耗电」",
                        "开启「允许自启动」",
                        "进入「权限管理」→ 开启「悬浮窗」"
                    ),
                    extraNote = "ColorOS 系统建议在「最近任务」中将灵枢锁定"
                )

            manufacturer.contains("vivo") || manufacturer.contains("iqoo") ->
                ManufacturerGuide(
                    manufacturer = "vivo/iqoo",
                    displayName = "vivo/iQOO",
                    guideSteps = listOf(
                        "打开「设置」→「应用与权限」→「应用管理」→「灵枢」",
                        "进入「权限」→「单项权限设置」→ 开启「后台高耗电」",
                        "开启「自启动」",
                        "进入「悬浮窗」→ 允许"
                    ),
                    extraNote = "OriginOS 系统建议在「电池」设置中将灵枢加入「高耗电白名单」"
                )

            manufacturer.contains("samsung") ->
                ManufacturerGuide(
                    manufacturer = "samsung",
                    displayName = "三星",
                    guideSteps = listOf(
                        "打开「设置」→「应用程序」→「灵枢」",
                        "进入「电池」→ 选择「优化电池使用量」",
                        "关闭灵枢的电池优化（选择「不优化」）",
                        "进入「权限」→ 开启「在其他应用上层显示」"
                    ),
                    extraNote = "One UI 建议在「设备维护」→「内存」中排除灵枢"
                )

            else ->
                ManufacturerGuide(
                    manufacturer = manufacturer,
                    displayName = Build.MANUFACTURER.ifBlank { "未知" },
                    guideSteps = listOf(
                        "打开「设置」→「应用」→「灵枢」",
                        "进入「电池」或「电源管理」→ 关闭电池优化",
                        "进入「权限」→ 开启所需权限（悬浮窗、自启动等）",
                        "如找不到对应选项，请搜索「电池优化」或「后台限制」"
                    ),
                    extraNote = "不同品牌设置路径略有差异，核心目标：允许后台运行 + 悬浮窗权限"
                )
        }
    }
}
