package com.lingshu.feature.accessibility.domain

import com.lingshu.core.common.error.Result

interface IAccessibilityControl {
    suspend fun tap(x: Int, y: Int): Result<Unit>
    suspend fun tapByText(text: String): Result<Unit>
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int): Result<Unit>
    suspend fun inputText(text: String): Result<Unit>
    suspend fun pressBack(): Result<Unit>
    suspend fun pressHome(): Result<Unit>
    suspend fun getScreenText(): Result<String>
    suspend fun findControlByText(text: String): Result<ControlInfo?>
    suspend fun findControlById(id: String): Result<ControlInfo?>
    suspend fun longPress(x: Float, y: Float, durationMs: Long = 500): Result<Unit>
    suspend fun isServiceRunning(): Boolean
}
