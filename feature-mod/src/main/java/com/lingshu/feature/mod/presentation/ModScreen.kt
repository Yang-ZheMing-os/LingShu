package com.lingshu.feature.mod.presentation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
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
import kotlin.math.max

/**
 * 简单的自动换行布局（替换实验性 FlowRow，避免 @OptIn 编译器报错）
 */
@Composable
private fun WrapRow(
    modifier: Modifier = Modifier,
    horizontalGap: androidx.compose.ui.unit.Dp = 0.dp,
    verticalGap: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit
) {
    Layout(content, modifier) { measurables, constraints ->
        val hGapPx = horizontalGap.roundToPx()
        val vGapPx = verticalGap.roundToPx()
        val rowWidth = constraints.maxWidth
        var x = 0
        var y = 0
        var lineHeight = 0
        var widthSoFar = 0
        val placeables = measurables.map { it.measure(Constraints()) }
        val positions = mutableListOf<Pair<Int, Int>>()
        placeables.forEach { p ->
            val w = p.width
            val needNext = x != 0 && x + w > rowWidth
            if (needNext) {
                x = 0
                y += lineHeight + vGapPx
                lineHeight = 0
            }
            positions.add(x to y)
            x += w + hGapPx
            lineHeight = max(lineHeight, p.height)
            widthSoFar = max(widthSoFar, x - hGapPx)
        }
        val totalHeight = y + lineHeight
        layout(widthSoFar.coerceAtLeast(0), totalHeight.coerceAtLeast(0)) {
            placeables.forEachIndexed { i, p ->
                val (px, py) = positions[i]
                p.place(px, py)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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

            // ========== 声明式能力清单（Day2-1：让用户一眼看到这个 Mod 不只是描述，还提供了什么） ==========
            val hasDeclarative = mod.manifest.quickActions.isNotEmpty()
                    || mod.manifest.aliases.isNotEmpty()
                    || mod.manifest.promptSnippets.isNotEmpty()
                    || mod.manifest.homeNavCards.isNotEmpty()
                    || mod.manifest.personaPrompt != null
            if (hasDeclarative) {
                Spacer(modifier = Modifier.height(8.dp))
                WrapRow(horizontalGap = 6.dp, verticalGap = 6.dp) {
                    if (mod.manifest.quickActions.isNotEmpty()) {
                        AssistChip("快捷动作 x${mod.manifest.quickActions.size}")
                    }
                    if (mod.manifest.aliases.isNotEmpty()) {
                        AssistChip("指令别名 x${mod.manifest.aliases.size}")
                    }
                    if (mod.manifest.promptSnippets.isNotEmpty()) {
                        AssistChip("RAG 片段 x${mod.manifest.promptSnippets.size}")
                    }
                    if (mod.manifest.homeNavCards.isNotEmpty()) {
                        AssistChip("首页卡片 x${mod.manifest.homeNavCards.size}")
                    }
                    if (mod.manifest.personaPrompt != null) {
                        AssistChip("人格加成")
                    }
                }
                // 显示前 3 个快捷动作，点击可预览 canonical command
                if (mod.manifest.quickActions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    WrapRow(horizontalGap = 6.dp, verticalGap = 6.dp) {
                        mod.manifest.quickActions.take(3).forEach { qa ->
                            AssistChip(
                                text = (qa.iconEmoji?.plus(" ") ?: "") + qa.label,
                                onClick = { /* chat 页再真实点击发送；这里只预览 */ }
                            )
                        }
                    }
                }
            }

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

@Composable
private fun AssistChip(text: String, onClick: () -> Unit = {}) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        border = null
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
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
