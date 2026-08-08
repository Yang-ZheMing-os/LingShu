package com.lingshu.agent.feature.model

private fun apiKeysKey() {}
private fun baseUrlKey() {}
private fun enabledKey() {}
private fun modelNameKey() {}
fun getProviderApiKeysFlow() {}
fun getProviderBaseUrlFlow() {}
fun getProviderEnabledFlow() {}
fun getProviderModelNameFlow() {}
suspend fun getDefaultProviderForCapability() {}
suspend fun getProviderApiKeys() {}
suspend fun getProviderBaseUrl() {}
suspend fun isProviderEnabled() {}
suspend fun isAutoFallbackEnabled() {}
suspend fun isApiKeyRotationEnabled() {}
suspend fun setDefaultProvider() {}
suspend fun setDefaultChatProvider() {}
suspend fun setDefaultVisionProvider() {}
suspend fun setDefaultTranscribeProvider() {}
suspend fun setDefaultSynthesizeProvider() {}
suspend fun setAutoFallbackEnabled() {}
suspend fun setApiKeyRotationEnabled() {}
suspend fun setProviderApiKeys() {}
suspend fun addApiKey() {}
suspend fun removeApiKey() {}
suspend fun setProviderBaseUrl() {}
suspend fun setProviderEnabled() {}
suspend fun setProviderModelName() {}
suspend fun resetToDefaults() {}

