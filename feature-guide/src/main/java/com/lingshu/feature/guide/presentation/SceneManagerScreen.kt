package com.lingshu.feature.guide.presentation

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lingshu.feature.control.domain.scenes.BuiltInScenes
import com.lingshu.feature.control.domain.scenes.GenericScene

/**
 * 通用场景管理页（真机可测版，不再强依赖 SceneRepository 跨模块注入）。
 *
 * 功能目标（对应「用户不会只用三个场景」）：
 *  1. 展示「内置场景 12 条」列表；自定义场景后续接 repo.observeAll()
 *  2. 「新建示例 / 导入 JSON」按钮：后续版本接 SAF + repo.importFromJson
 *  3. 点击卡片：预留，后续做详情/编辑
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SceneManagerScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // 直接取 BuiltInScenes.Companion 的 12 个常量（静态，无注入要求）
    val allScenes: List<GenericScene> = remember { BuiltInScenesBuiltinSnapshot.all }

    var showToast by remember { mutableStateOf<String?>(null) }
    showToast?.let {
        Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        showToast = null
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("场景管理 / 自定义") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ExtendedFloatingActionButton(
                    text = { Text("新建示例") },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    onClick = {
                        showToast = "示例创建入口：后续开放。当前内置 12 条已覆盖常用场景。"
                    }
                )
                ExtendedFloatingActionButton(
                    text = { Text("导入 JSON") },
                    icon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                    onClick = {
                        // 预留：后续调 SAF ACTION_OPEN_DOCUMENT (application/json)
                        // 再通过 ISceneRepository.importFromJson(json) 解析写入
                        showToast =
                            "请选择场景 JSON 文件：后续绑定 SAF；也可把 JSON 放 /sdcard/Download 后在此处导入。"
                    }
                )
            }
        }
    ) { pad ->
        if (allScenes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "还没有加载到任何场景。\n如果这是你第一次打开，请等待 BuiltInScenes 初始化。",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        text = "内置场景（${allScenes.size} 条）—— AI 不会只用三个场景",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(allScenes, key = { it.sceneId }) { s ->
                    SceneItemCard(scene = s)
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }
}

/**
 * 把 BuiltInScenes.Companion 里的 12 个常量集中成一个静态 List。
 * 这样 SceneManagerScreen 作为 feature-guide 模块页面，不需要 @Inject SceneRepository 也能展示内容。
 * 后续接入自定义场景时，再组合「这里的内置 + repo.observeAll().filter { !it.builtIn }」即可。
 */
private object BuiltInScenesBuiltinSnapshot {
    val all: List<GenericScene> by lazy {
        listOf(
            BuiltInScenes.SEND_CHAT_SCENE,
            BuiltInScenes.CALL_RIDE_SCENE,
            BuiltInScenes.NAV_SCENE,
            BuiltInScenes.ORDER_TAKEOUT_SCENE,
            BuiltInScenes.MAKE_CALL_SCENE,
            BuiltInScenes.SEND_SMS_SCENE,
            BuiltInScenes.SET_ALARM_SCENE,
            BuiltInScenes.TAKE_PHOTO_SCENE,
            BuiltInScenes.WEB_SEARCH_SCENE,
            BuiltInScenes.OPEN_APP_SCENE,
            BuiltInScenes.CLOSE_APP_SCENE,
            BuiltInScenes.PLAY_MUSIC_SCENE
        )
    }
}

@Composable
private fun SceneItemCard(scene: GenericScene) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = scene.displayName,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (scene.builtIn) "内置" else "自定义",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = buildString {
                    append("槽位：")
                    append(scene.slots.ifEmpty { null }?.joinToString("、") { it.name } ?: "无")
                    append("  ｜  步骤：")
                    append(scene.steps.size)
                    append("  ｜  intent关键词：")
                    append(scene.intentKeywords.ifEmpty { null }?.take(3)?.joinToString("、") ?: "-")
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
