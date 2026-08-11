package com.lingshu.feature.accessibility.domain

import android.graphics.Rect

data class ControlInfo(
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val bounds: Rect,
    val isClickable: Boolean
)
