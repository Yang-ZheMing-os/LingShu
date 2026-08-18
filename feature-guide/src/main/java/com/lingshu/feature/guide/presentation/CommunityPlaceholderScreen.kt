package com.lingshu.feature.guide.presentation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingshu.core.ui.theme.Background
import com.lingshu.core.ui.theme.OnBackground
import com.lingshu.core.ui.theme.OnSurfaceVariant
import com.lingshu.core.ui.theme.Primary

/**
 * 社区页（资源导航版 · 无需后端）
 *
 * 砍掉了"发帖/评论/点赞"的真实社区后端依赖，改成 4 个可跳转资源卡片：
 *   ① 最新版下载  ② 离线模型安装指南  ③ 加入交流群  ④ 反馈 Issue
 * 所有卡片使用 Intent.ACTION_VIEW 跳浏览器，无需任何账号/服务器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityPlaceholderScreen(
    onBackClick: () -> Unit = {}
) {
    val ctx = LocalContext.current

    // 占位链接（后续你上传 GitHub Release 后把 OWNER/REPO 告诉我，我直接替换成真实的）
    val links = remember {
        CommunityLinks(
            latestRelease = "https://github.com/LingShu-AI/LingShu-Android/releases/latest",
            modelsGuide   = "https://github.com/LingShu-AI/LingShu-Android/blob/main/docs/%E7%A6%BB%E7%BA%BF%E6%A8%A1%E5%9E%8B%E9%83%A8%E7%BD%B2.md",
            joinGroup     = "https://qm.qq.com/q/xxxxxxxxxx",
            feedback      = "https://github.com/LingShu-AI/LingShu-Android/issues/new"
        )
    }

    fun openUrl(url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            // 没有浏览器就先静默失败
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "社区 · 资源中心",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        containerColor = Background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // --- 顶部欢迎横幅 ---
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "灵枢资源中心",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = OnBackground
                        )
                        Text(
                            text = "无需注册即可获取最新安装包、离线模型、加群交流或反馈 Bug。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }

            // --- 2×2 卡片网格 ---
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    NavCard(
                        icon = Icons.Default.Download,
                        title = "最新版下载",
                        desc = "GitHub Release\n包含 Release APK",
                        accent = Primary,
                        modifier = Modifier.weight(1f)
                    ) { openUrl(links.latestRelease) }
                    NavCard(
                        icon = Icons.Default.Source,
                        title = "离线模型",
                        desc = "SenseVoice / Sherpa\n部署手册",
                        accent = Color(0xFF0EA5A0.toInt()),
                        modifier = Modifier.weight(1f)
                    ) { openUrl(links.modelsGuide) }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    NavCard(
                        icon = Icons.Default.Group,
                        title = "加入交流群",
                        desc = "QQ 群互助答疑\n反馈新想法",
                        accent = Color(0xFF3B82F6.toInt()),
                        modifier = Modifier.weight(1f)
                    ) { openUrl(links.joinGroup) }
                    NavCard(
                        icon = Icons.Default.BugReport,
                        title = "反馈 Bug / 提需求",
                        desc = "GitHub Issues\n带日志更易定位",
                        accent = Color(0xFFF59E0B.toInt()),
                        modifier = Modifier.weight(1f)
                    ) { openUrl(links.feedback) }
                }
            }

            // --- 底部说明 ---
            item {
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "提示",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "• 最新版下载会直接跳转到 GitHub Release 页，点击 Assets/app-release.apk 下载覆盖安装即可。\n" +
                                   "• 离线模型是" + "手动部署模式（不占用应用流量），按文档放到 /Android/data/com.lingshu/files/models/ 即可启用。\n" +
                                   "• 反馈 Bug 时建议附上「设置 → 导出日志」生成的 zip 文件，定位会更快。",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceVariant,
                            lineHeight = 19.sp
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private data class CommunityLinks(
    val latestRelease: String,
    val modelsGuide: String,
    val joinGroup: String,
    val feedback: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NavCard(
    icon: ImageVector,
    title: String,
    desc: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accentColor = accent
    Card(
        modifier = modifier
            .height(150.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = accentColor.copy(alpha = 0.14f)
                ),
                modifier = Modifier.size(44.dp)
            ) {
                BoxContent {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Column {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun BoxContent(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) { content() }
}
