package com.lingshu.core.common.event

/**
 * 可启动/停止的事件 Bridge 契约。
 *
 * 各 feature 模块实现此接口并通过 Hilt @Inject 构造，
 * EventBridgesStarter 通过 MultiBinding 收集所有实现并统一启动/停止，
 * 避免 core-common 反向依赖 feature 模块。
 */
interface StartableBridge {
    fun start()
    fun stop()
}