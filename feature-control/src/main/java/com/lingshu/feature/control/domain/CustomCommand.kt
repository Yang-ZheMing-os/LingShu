package com.lingshu.feature.control.domain

/**
 * 用户自定义指令别名。
 *
 * 用户可以把自己的日常口语（如"睡觉觉"）映射到一条标准指令（如"我要睡了"），
 * 解析时优先匹配别名，命中后用目标指令走标准解析链路。
 *
 * @param alias   用户自定义的口语短语
 * @param target  目标标准指令（将被 CommandParser 再次解析）
 */
data class CustomCommand(
    val alias: String,
    val target: String
)
