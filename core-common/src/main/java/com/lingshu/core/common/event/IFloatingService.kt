package com.lingshu.core.common.event

interface IFloatingService {
    fun show()
    fun hide()
    fun toggleVisibility()
    fun updateState(state: FloatingState)
    fun setOpacity(opacity: Float)
    fun setSize(size: FloatingSize)
    fun isShowing(): Boolean
}
