package com.lingshu.agent.feature.mod

enum class ModCategory

enum class PermissionLevel

data class PermissionDeclaration

data class ModManifest

data class LoadedMod

data class ScanRule

sealed class ModLoadState

object Idle

object Scanning

data class Installing

data class Ready

data class Error

sealed class ModInstallResult

data class Success

data class Failed

data class SecurityBlocked

sealed class ModUpdateResult

object NoUpdate

data class Available

data class Unknown

data class MaliciousCodeFinding

data class MaliciousCodeScanResult

