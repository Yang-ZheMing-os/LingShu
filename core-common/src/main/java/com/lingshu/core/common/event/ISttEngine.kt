package com.lingshu.core.common.event

interface ISttEngine {
    fun startListening(onResult: (SttResult) -> Unit, onError: (String) -> Unit)
    fun stopListening()
    fun isAvailable(): Boolean
    fun cancel()
}
