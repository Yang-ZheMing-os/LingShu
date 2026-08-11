package com.lingshu.feature.mod.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lingshu.core.common.state.UiState
import com.lingshu.core.ui.component.GlassCard
import com.lingshu.feature.mod.domain.Mod
import com.lingshu.feature.mod.domain.ModInfo
import com.lingshu.feature.mod.domain.PermissionLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModScreen(
    onBackClick: () -> Unit = {},
    viewModel: ModViewModel = hiltViewModel()
) {
    val installedMods by viewModel.installedMods.collectAsState()
    val availableMods by viewModel.availableMods.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val installState by viewModel.installState.collectAsState()

    var showUninstallDialog by remember { mutableStateOf<Mod?>(null) }
    var showInstallDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mod 系统") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showInstallDialog = true }) {
                        Icon(Icons.Default.Extension, contentDescription = "安装")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TabRow(
                selectedTabIndex = selectedTab.ordinal
            ) {
                Tab(
                    selected = selectedTab == ModTab.INSTALLED,
                    onClick = { viewModel.selectTab(ModTab.INSTALLED) },
                    text = { Text("已安装") }
                )
                Tab(
                    selected = selectedTab == ModTab.STORE,
                    onClick = { viewModel.selectTab(ModTab.STORE) },
                    text = { Text("商店") }
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                when (selectedTab) {
                    ModTab.INSTALLED -> {
                        InstalledModsList(
                            mods = installedMods,
                            onEnable = { viewModel.enableMod(it) },
                            onDisable = { viewModel.disableMod(it) },
                            onUninstall = { showUninstallDialog = it }
                        )
                    }
                    ModTab.STORE -> {
                        StoreModsList(
                            mods = availableMods,
                            onDownload = { viewModel.downloadAndInstallMod(it) }
                        )
                    }
                }
            }
        }
    }

    if (installState is UiState.Loading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("安装中") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("正在安装 Mod...")
                }
            },
            confirmButton = {}
        )
    }

    if (installState is UiState.Success) {
        AlertDialog(
            onDismissRequest = { viewModel.resetInstallState() },
            title = { Text("安装成功") },
            text = { Text("Mod 安装完成！") },
            confirmButton = {
                TextButton(onClick = { viewModel.resetInstallState() }) {
                    Text("确定")
                }
            }
        )
    }

    if (installState is UiState.Error) {
        AlertDialog(
            onDismissRequest = { viewModel.resetInstallState() },
            title = { Text("安装失败") },
            text = { Text("Mod 安装失败，请重试。") },
            confirmButton = {
                TextButton(onClick = { viewModel.resetInstallState() }) {
                    Text("确定")
                }
            }
        )
    }

    showUninstallDialog?.let { mod ->
        AlertDialog(
            onDismissRequest = { showUninstallDialog = null },
            title = { Text("卸载确认") },
            text = { Text("确定要卸载 Mod\"${mod.name}\"吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.uninstallMod(mod.id)
                    showUninstallDialog = null
                }) {
                    Text("卸载")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallDialog = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun InstalledModsList(
    mods: List<Mod>,
    onEnable: (String) -> Unit,
    onDisable: (String) -> Unit,
    onUninstall: (Mod) -> Unit
) {
    if (mods.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "暂无已安装的 Mod",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(mods) { mod ->
                ModCard(
                    mod = mod,
                    onToggle = { enabled ->
                        if (enabled) onEnable(mod.id) else onDisable(mod.id)
                    },
                    onUninstall = { onUninstall(mod) }
                )
            }
        }
    }
}

@Composable
private fun StoreModsList(
    mods: List<ModInfo>,
    onDownload: (String) -> Unit
) {
    if (mods.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "商店暂无 Mod",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(mods) { modInfo ->
                StoreModCard(
                    modInfo = modInfo,
                    onDownload = { onDownload(modInfo.id) }
                )
            }
        }
    }
}

@Composable
private fun ModCard(
    mod: Mod,
    onToggle: (Boolean) -> Unit,
    onUninstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mod.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "v${mod.version} · ${mod.author}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = mod.enabled,
                    onCheckedChange = onToggle
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = mod.description,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PermissionLevelBadge(level = mod.manifest.permissionLevel)
                Row {
                    Text(
                        text = formatDate(mod.installedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = onUninstall) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "卸载",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreModCard(
    modInfo: ModInfo,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = modInfo.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "v${modInfo.version} · ${modInfo.author}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDownload) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "下载",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = modInfo.description,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PermissionLevelBadge(level = modInfo.permissionLevel)
                Text(
                    text = formatFileSize(modInfo.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PermissionLevelBadge(level: PermissionLevel) {
    val (text, color) = when (level) {
        PermissionLevel.NORMAL -> "普通" to MaterialTheme.colorScheme.primary
        PermissionLevel.INTERMEDIATE -> "中级" to MaterialTheme.colorScheme.secondary
        PermissionLevel.ADVANCED -> "高级" to MaterialTheme.colorScheme.tertiary
        PermissionLevel.DANGEROUS -> "危险" to MaterialTheme.colorScheme.error
    }

    GlassCard(
        backgroundColor = color.copy(alpha = 0.1f),
        borderColor = color.copy(alpha = 0.3f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        else -> "${size / (1024 * 1024)} MB"
    }
}
