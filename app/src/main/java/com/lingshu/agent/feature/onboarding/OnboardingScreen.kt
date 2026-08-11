package com.lingshu.agent.feature.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.agent.ui.theme.AccentGlow
import com.lingshu.agent.ui.theme.AccentPrimary
import com.lingshu.agent.ui.theme.GlassBubble
import com.lingshu.agent.ui.theme.GlassBubbleBorder
import com.lingshu.agent.ui.theme.GlassBubbleStrong
import com.lingshu.agent.ui.theme.IceBlueGradientEnd
import com.lingshu.agent.ui.theme.IceBlueGradientMid
import com.lingshu.agent.ui.theme.IceBlueGradientStart
import com.lingshu.agent.ui.theme.PrimaryBackground
import com.lingshu.agent.ui.theme.SecondaryBackground
import com.lingshu.agent.ui.theme.Success
import com.lingshu.agent.ui.theme.TextPrimary
import com.lingshu.agent.ui.theme.TextSecondary
import com.lingshu.agent.ui.theme.TextTertiary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

// ==================== ViewModel ====================

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    companion object {
        val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }

    private val _isCompleted = MutableStateFlow(false)
    val isCompleted: StateFlow<Boolean> = _isCompleted.asStateFlow()

    fun markCompleted() {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_ONBOARDING_COMPLETED] = true
            }
            _isCompleted.value = true
        }
    }
}

// ==================== 主Screen ====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel = hiltViewModel(),
    onFinish: () -> Unit = {}
) {
    val pagerState = rememberPagerState(pageCount = { 5 })
    val scope = rememberCoroutineScope()
    val currentPage = pagerState.currentPage

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(PrimaryBackground, Color(0xFF0A1025), PrimaryBackground)
                )
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Pager 内容区
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    when (page) {
                        0 -> PageBrand()
                        1 -> PageCapabilities()
                        2 -> PageVoice()
                        3 -> PageControl()
                        4 -> PagePrivacy()
                    }
                }
            }

            // 底部控制区
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(GlassBubbleStrong)
                    .border(1.dp, GlassBubbleBorder, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 圆点指示器
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(5) { index ->
                        val isCurrent = currentPage == index
                        val width by animateFloatAsState(
                            targetValue = if (isCurrent) 24f else 8f,
                            animationSpec = tween(300),
                            label = "dotWidth"
                        )
                        val indicatorColor by animateColorAsState(
                            targetValue = if (isCurrent) AccentGlow else TextTertiary.copy(alpha = 0.4f),
                            animationSpec = tween(300),
                            label = "dotColor"
                        )
                        Box(
                            modifier = Modifier
                                .height(8.dp)
                                .width(width.dp)
                                .clip(CircleShape)
                                .background(indicatorColor)
                        )
                    }
                }

                // 最后一页显示"开始使用"按钮
                if (currentPage == 4) {
                    Button(
                        onClick = {
                            viewModel.markCompleted()
                            onFinish()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Success.copy(alpha = 0.9f),
                                        Color(0xFF22C55E),
                                        Success.copy(alpha = 0.8f)
                                    )
                                )
                            ),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = null,
                            tint = Color(0xFF052E16),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "开始使用",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF052E16),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// ==================== 第1页：品牌页 ====================

@Composable
private fun PageBrand() {
    // Logo 圆形图标
    Box(
        modifier = Modifier
            .size(160.dp)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(IceBlueGradientStart, IceBlueGradientMid, IceBlueGradientEnd)
                )
            )
            .border(2.dp, Brush.verticalGradient(listOf(AccentGlow, AccentPrimary)), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(56.dp)
            )
            Text(
                text = "灵枢",
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }

    Spacer(modifier = Modifier.height(48.dp))

    Text(
        text = "灵枢",
        style = MaterialTheme.typography.headlineLarge,
        color = TextPrimary,
        fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(12.dp))

    Text(
        text = "你的AI数字生命体",
        style = MaterialTheme.typography.titleLarge,
        color = AccentGlow,
        textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(32.dp))

    Text(
        text = "全天候智能助手，懂你所想，伴你左右",
        style = MaterialTheme.typography.bodyLarge,
        color = TextSecondary,
        textAlign = TextAlign.Center
    )
}

// ==================== 第2页：核心能力 ====================

@Composable
private fun PageCapabilities() {
    Text(
        text = "核心能力",
        style = MaterialTheme.typography.headlineSmall,
        color = TextPrimary,
        fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "灵枢能为你做什么",
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary
    )

    Spacer(modifier = Modifier.height(36.dp))

    val capabilities = listOf(
        Triple(Icons.Default.Mic, "语音对话", "自然语音交互\n随时唤醒随时聊"),
        Triple(Icons.Default.PhoneAndroid, "控制手机", "打开App、调亮度\n自动化操作手机"),
        Triple(Icons.Default.VolumeUp, "主动关怀", "健康监测、智能提醒\n贴心陪伴每一天"),
        Triple(Icons.Default.Lock, "隐私安全", "数据本地存储\n不上传任何云端")
    )

    capabilities.chunked(2).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            row.forEach { (icon, title, desc) ->
                CapabilityCard(
                    icon = icon,
                    title = title,
                    description = desc,
                    modifier = Modifier.weight(1f)
                )
            }
            if (row.size == 1) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun CapabilityCard(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = GlassBubble,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, GlassBubbleBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(AccentPrimary.copy(alpha = 0.3f), AccentGlow.copy(alpha = 0.15f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = AccentGlow,
                    modifier = Modifier.size(28.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ==================== 第3页：语音能力 ====================

@Composable
private fun PageVoice() {
    Box(
        modifier = Modifier
            .size(140.dp)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF6366F1),
                        Color(0xFF8B5CF6),
                        Color(0xFFA78BFA)
                    )
                )
            )
            .border(2.dp, AccentGlow.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(64.dp)
        )
    }

    Spacer(modifier = Modifier.height(40.dp))

    Text(
        text = "语音唤醒",
        style = MaterialTheme.typography.headlineSmall,
        color = TextPrimary,
        fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "你可以随时说",
        style = MaterialTheme.typography.bodyLarge,
        color = TextSecondary
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "\"灵枢\"",
        style = MaterialTheme.typography.headlineMedium,
        color = AccentGlow,
        fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "唤醒我",
        style = MaterialTheme.typography.bodyLarge,
        color = TextSecondary
    )

    Spacer(modifier = Modifier.height(28.dp))

    Text(
        text = "无需触碰屏幕，随时随地用语音\n与灵枢自由对话",
        style = MaterialTheme.typography.bodyMedium,
        color = TextTertiary,
        textAlign = TextAlign.Center
    )
}

// ==================== 第4页：手机控制 ====================

@Composable
private fun PageControl() {
    Box(
        modifier = Modifier
            .size(140.dp)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0EA5E9),
                        Color(0xFF38BDF8),
                        Color(0xFF7DD3FC)
                    )
                )
            )
            .border(2.dp, AccentGlow.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.PhoneAndroid,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(64.dp)
        )
    }

    Spacer(modifier = Modifier.height(40.dp))

    Text(
        text = "控制手机",
        style = MaterialTheme.typography.headlineSmall,
        color = TextPrimary,
        fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "我可以帮你",
        style = MaterialTheme.typography.bodyLarge,
        color = TextSecondary
    )

    Spacer(modifier = Modifier.height(24.dp))

    val actions = listOf(
        "打开任意 App",
        "调节屏幕亮度",
        "发送消息、拨打电话",
        "帮你操作手机完成复杂任务"
    )

    actions.forEach { action ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(AccentGlow)
            )
            Text(
                text = action,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
        }
    }
}

// ==================== 第5页：隐私承诺 ====================

@Composable
private fun PagePrivacy() {
    Box(
        modifier = Modifier
            .size(140.dp)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF10B981),
                        Color(0xFF34D399),
                        Color(0xFF6EE7B7)
                    )
                )
            )
            .border(2.dp, AccentGlow.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.PrivacyTip,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(64.dp)
        )
    }

    Spacer(modifier = Modifier.height(40.dp))

    Text(
        text = "隐私承诺",
        style = MaterialTheme.typography.headlineSmall,
        color = TextPrimary,
        fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "所有数据存在本地\n不上传云端",
        style = MaterialTheme.typography.titleLarge,
        color = Success,
        textAlign = TextAlign.Center,
        fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(28.dp))

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = GlassBubble,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, GlassBubbleBorder)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            PrivacyItem("你的对话记录仅存储在本地设备")
            PrivacyItem("语音数据在本地处理，不外传")
            PrivacyItem("不上传任何个人信息到远程服务器")
            PrivacyItem("你可以随时删除所有本地数据")
            PrivacyItem("无第三方SDK收集你的隐私信息")
        }
    }
}

@Composable
private fun PrivacyItem(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = Success,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary
        )
    }
}
