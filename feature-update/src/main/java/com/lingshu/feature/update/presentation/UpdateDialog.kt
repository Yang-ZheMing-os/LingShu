package com.lingshu.feature.update.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.lingshu.feature.update.domain.UpdateInfo

@Composable
fun UpdateDialog(
    viewModel: UpdateViewModel = hiltViewModel(),
    onDismiss: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val showDialog by viewModel.showUpdateDialog.collectAsState()

    if (!showDialog) return

    AlertDialog(
        onDismissRequest = {
            if (uiState !is UpdateUiState.Downloading) {
                viewModel.dismissUpdateDialog()
                onDismiss()
            }
        },
        title = {
            Text(
                text = getDialogTitle(uiState),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            DialogContent(
                uiState = uiState,
                downloadProgress = downloadProgress
            )
        },
        confirmButton = {
            DialogConfirmButton(
                uiState = uiState,
                onConfirm = {
                    when (uiState) {
                        is UpdateUiState.UpdateAvailable -> {
                            viewModel.startDownload()
                        }
                        is UpdateUiState.DownloadComplete -> {
                            viewModel.installUpdate()
                        }
                        else -> {}
                    }
                },
                isConfirmEnabled = uiState is UpdateUiState.UpdateAvailable ||
                        uiState is UpdateUiState.DownloadComplete
            )
        },
        dismissButton = {
            DialogDismissButton(
                uiState = uiState,
                onDismiss = {
                    viewModel.dismissUpdateDialog()
                    onDismiss()
                }
            )
        },
        shape = RoundedCornerShape(16.dp),
        properties = DialogProperties(
            dismissOnBackPress = uiState !is UpdateUiState.Downloading,
            dismissOnClickOutside = uiState !is UpdateUiState.Downloading
        )
    )
}

@Composable
private fun getDialogTitle(uiState: UpdateUiState): String {
    return when (uiState) {
        is UpdateUiState.Idle -> "检查更新"
        is UpdateUiState.Checking -> "正在检查更新"
        is UpdateUiState.UpdateAvailable -> "发现新版本"
        is UpdateUiState.Downloading -> "正在下载更新"
        is UpdateUiState.DownloadComplete -> "下载完成"
        is UpdateUiState.Installing -> "正在安装"
        is UpdateUiState.Error -> "更新失败"
    }
}

@Composable
private fun DialogContent(
    uiState: UpdateUiState,
    downloadProgress: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        when (uiState) {
            is UpdateUiState.Idle -> {
                Text("正在准备检查更新...")
            }

            is UpdateUiState.Checking -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(24.dp),
                        strokeWidth = 2.dp
                    )
                    Text("正在检查最新版本...")
                }
            }

            is UpdateUiState.UpdateAvailable -> {
                UpdateAvailableContent(uiState.updateInfo)
            }

            is UpdateUiState.Downloading -> {
                DownloadingContent(downloadProgress)
            }

            is UpdateUiState.DownloadComplete -> {
                Text("下载完成，是否立即安装？")
            }

            is UpdateUiState.Installing -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(24.dp),
                        strokeWidth = 2.dp
                    )
                    Text("正在启动安装程序...")
                }
            }

            is UpdateUiState.Error -> {
                Text(
                    text = uiState.message,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun UpdateAvailableContent(updateInfo: UpdateInfo) {
    Column {
        Text(
            text = "新版本: v${updateInfo.version}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "文件大小: ${formatFileSize(updateInfo.fileSize)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (updateInfo.isRequired) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "⚠️ 本次为强制更新",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "更新说明:",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = updateInfo.releaseNotes.ifEmpty { "暂无更新说明" },
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun DownloadingContent(progress: Int) {
    Column {
        Text("正在下载更新包，请稍候...")

        Spacer(modifier = Modifier.height(16.dp))

        LinearProgressIndicator(
            progress = { if (progress > 0) progress / 100f else 0f },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (progress > 0) "$progress%" else "正在连接服务器...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DialogConfirmButton(
    uiState: UpdateUiState,
    onConfirm: () -> Unit,
    isConfirmEnabled: Boolean
) {
    val buttonText = when (uiState) {
        is UpdateUiState.UpdateAvailable -> "立即更新"
        is UpdateUiState.Downloading -> "下载中..."
        is UpdateUiState.DownloadComplete -> "安装"
        is UpdateUiState.Installing -> "安装中..."
        else -> "确定"
    }

    Button(
        onClick = onConfirm,
        enabled = isConfirmEnabled
    ) {
        Text(buttonText)
    }
}

@Composable
private fun DialogDismissButton(
    uiState: UpdateUiState,
    onDismiss: () -> Unit
) {
    val showDismiss = when (uiState) {
        is UpdateUiState.UpdateAvailable -> !uiState.updateInfo.isRequired
        is UpdateUiState.Error -> true
        else -> false
    }

    if (showDismiss) {
        OutlinedButton(onClick = onDismiss) {
            Text("稍后再说")
        }
    }
}

private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        size < 1024 * 1024 * 1024 -> "${String.format("%.2f", size / (1024.0 * 1024.0))} MB"
        else -> "${String.format("%.2f", size / (1024.0 * 1024.0 * 1024.0))} GB"
    }
}
