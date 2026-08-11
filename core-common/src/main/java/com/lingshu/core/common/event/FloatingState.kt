package com.lingshu.core.common.event

import androidx.annotation.ColorInt

enum class FloatingState(@ColorInt val color: Int) {
    IDLE(0xFF888888.toInt()),
    LISTENING(0xFF4A8CFF.toInt()),
    THINKING(0xFF8A6EFF.toInt()),
    EXECUTING(0xFF4CAF50.toInt()),
    ERROR(0xFFFF4444.toInt())
}
