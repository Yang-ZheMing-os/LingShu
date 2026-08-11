package com.lingshu.agent.feature.community

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.hilt.navigation.compose.hiltViewModel
import com.lingshu.agent.core.model.ModInfo
import com.lingshu.agent.core.model.ModSource
import com.lingshu.agent.ui.components.GlassButton
import com.lingshu.agent.ui.components.GlassCard
import com.lingshu.agent.ui.components.GlassChip
import com.lingshu.agent.ui.components.GlassDivider
import com.lingshu.agent.ui.components.GlassIconButton
import com.lingshu.agent.ui.components.GlassSectionTitle
import com.lingshu.agent.ui.components.GlassTextField
import com.lingshu.agent.ui.components.GlassTopAppBar
import com.lingshu.agent.ui.theme.AccentGlow
import com.lingshu.agent.ui.theme.AccentPrimary
import com.lingshu.agent.ui.theme.Error
import com.lingshu.agent.ui.theme.GlassBubble
import com.lingshu.agent.ui.theme.GlassBubbleBorder
import com.lingshu.agent.ui.theme.GlassBubbleStrong
import com.lingshu.agent.ui.theme.IceBlueGradientEnd
import com.lingshu.agent.ui.theme.IceBlueGradientMid
import com.lingshu.agent.ui.theme.IceBlueGradientStart
import com.lingshu.agent.ui.theme.PrimaryBackground
import com.lingshu.agent.ui.theme.Success
import com.lingshu.agent.ui.theme.TextPrimary
import com.lingshu.agent.ui.theme.TextSecondary
import com.lingshu.agent.ui.theme.TextTertiary
import com.lingshu.agent.ui.theme.Warning
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(
    viewModel: CommunityViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val tabs = listOf("人格", "Script", "主题", "整合包", "我的上传")
    val selectedTabIndex = uiState.selectedTab

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBackground)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            GlassTopAppBar(
                title = {
                    Text(
                        text = "创意工坊",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    GlassIconButton(
                        onClick = onBack,
                        icon = Icons.Default.ArrowBack,
                        iconModifier = Modifier.size(22.dp)
                    )
                },
                actions = {
                    GlassIconButton(
                        onClick = {  },
                        icon = Icons.Default.Upload,
                        iconModifier = Modifier.size(20.dp)
                    )
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                GlassTextField(
                    value = uiState.searchKeyword,
                    onValueChange = { viewModel.setSearchKeyword(it) },
                    placeholder = "搜索Mod、作者、标签...",
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            ScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = Color.Transparent,
                contentColor = TextPrimary,
                edgePadding = 16.dp,
                indicator = { tabPositions ->
                    if (selectedTabIndex < tabPositions.size) {
                        TabRowDefaults.Indicator(
                            Modifier
                                .width(tabPositions[selectedTabIndex].width)
                                .padding(horizontal = 16.dp),
                            height = 2.dp,
                            color = AccentGlow
                        )
                    }
                },
                divider = {}
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { viewModel.selectTab(index) },
                        selectedContentColor = TextPrimary,
                        unselectedContentColor = TextTertiary,
                        text = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                    )
                }
            }

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = AccentGlow,
                        strokeWidth = 2.dp
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.mods, key = { it.modId }) { mod ->
                        ModCard(
                            mod = mod,
                            onClick = { viewModel.openModDetail(mod) }
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = uiState.isDetailOpen) {
            uiState.selectedMod?.let { mod ->
                val progress = uiState.downloadProgressMap[mod.modId]
                val modReviews = uiState.reviews[mod.modId] ?: emptyList()
                ModDetailSheet(
                    mod = mod,
                    isInstalled = mod.source == ModSource.LOCAL || mod.source == ModSource.IMPORTED,
                    isLoading = uiState.isLoading,
                    downloadProgress = progress,
                    reviews = modReviews,
                    onClose = { viewModel.closeModDetail() },
                    onDownload = { viewModel.downloadMod(mod) },
                    onEnable = { viewModel.enableMod(mod) },
                    onDisable = { viewModel.disableMod(mod) },
                    onUninstall = { viewModel.uninstallMod(mod) },
                    onSubmitReview = { rating, comment, anonymous ->
                        viewModel.submitReview(mod.modId, rating, comment, anonymous)
                    }
                )
            }
        }
    }
}

@Composable
private fun ModCard(
    mod: ModInfo,
    onClick: () -> Unit
) {
    val installed = mod.source == ModSource.LOCAL || mod.source == ModSource.IMPORTED
    val coverColors = getCoverColors(mod.category.name)

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        padding = PaddingValues(0.dp),
        glowColor = AccentGlow,
        glowAlpha = 0.08f
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.4f)
                    .background(
                        Brush.linearGradient(coverColors)
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(GlassBubble)
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (installed) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Success.copy(alpha = 0.85f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "已安装",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontSize = 10.sp
                                )
                            }
                        }
                        if (mod.enabled) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(AccentPrimary.copy(alpha = 0.85f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "启用中",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }

                    Text(
                        text = mod.name.firstOrNull()?.toString() ?: "?",
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White.copy(alpha = 0.3f),
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = mod.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = mod.author,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = Warning,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = String.format("%.1f", mod.rating),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = formatDownloadCount(mod.downloadCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ModDetailSheet(
    mod: ModInfo,
    isInstalled: Boolean,
    isLoading: Boolean,
    downloadProgress: DownloadProgress?,
    reviews: List<ModReview>,
    onClose: () -> Unit,
    onDownload: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onUninstall: () -> Unit,
    onSubmitReview: (Float, String, Boolean) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    val coverColors = getCoverColors(mod.category.name)
    var showReviewSheet by remember { mutableStateOf(false) }
    var reviewRating by remember { mutableFloatStateOf(5f) }
    var reviewComment by remember { mutableStateOf("") }
    var reviewAnonymous by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable { onClose() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize(0.9f)
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(PrimaryBackground)
                .clickable { }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brush.linearGradient(coverColors))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(GlassBubble)
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "截图 ${page + 1}",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    GlassIconButton(
                        onClick = onClose,
                        icon = Icons.Default.Close,
                        iconModifier = Modifier.size(20.dp),
                        backgroundColor = GlassBubbleStrong,
                        size = 36.dp
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.align(Alignment.CenterVertically)
                    ) {
                        repeat(4) { index ->
                            Box(
                                modifier = Modifier
                                    .size(
                                        width = if (index == pagerState.currentPage) 16.dp else 6.dp,
                                        height = 6.dp
                                    )
                                    .clip(CircleShape)
                                    .background(
                                        if (index == pagerState.currentPage) AccentGlow
                                        else Color.White.copy(alpha = 0.3f)
                                    )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.size(36.dp))
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = mod.name,
                            style = MaterialTheme.typography.headlineSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = TextTertiary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = mod.author,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                            }
                            Text(
                                text = "v${mod.version}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextTertiary
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = Warning,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = String.format("%.1f", mod.rating),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextPrimary
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    tint = AccentGlow,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "${formatDownloadCount(mod.downloadCount)} 下载",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }

                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        mod.tags.forEach { tag ->
                            GlassChip(label = tag)
                        }
                    }
                }

                item {
                    GlassDivider()
                }

                item {
                    GlassSectionTitle(title = "Mod描述")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = mod.description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                        lineHeight = 24.sp
                    )
                }

                item {
                    GlassSectionTitle(title = "用户评论")
                    Spacer(modifier = Modifier.height(8.dp))
                    // 写评论入口
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showReviewSheet = true }
                            .background(GlassBubble)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = AccentGlow,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "写一条评论...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextTertiary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // 真实评论
                    if (reviews.isEmpty()) {
                        Text(
                            text = "暂无评论，快来成为第一个评价的吧",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextTertiary,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            reviews.sortedByDescending { it.timestamp }.forEach { review ->
                                RealReviewItem(review = review)
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }

            Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                        .background(GlassBubbleStrong)
                        .border(
                            width = 1.dp,
                            color = GlassBubbleBorder,
                            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                        )
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!isInstalled) {
                        val pct = downloadProgress?.percentage ?: 0
                        val speed = downloadProgress?.speedKbps
                        val isDownloading = downloadProgress?.isActive == true
                        GlassButton(
                            onClick = onDownload,
                            text = when {
                                isDownloading && speed != null -> "$pct% · $speed"
                                isDownloading -> "下载中... $pct%"
                                downloadProgress?.isComplete == true -> "下载完成"
                                else -> "下载安装"
                            },
                            icon = if (!isDownloading) Icons.Default.Download else null,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        if (mod.enabled) {
                            GlassButton(
                                onClick = onDisable,
                                text = "禁用",
                                modifier = Modifier.weight(1f),
                                gradient = Brush.horizontalGradient(
                                    listOf(
                                        Warning.copy(alpha = 0.8f),
                                        Warning.copy(alpha = 0.6f)
                                    )
                                )
                            )
                        } else {
                            GlassButton(
                                onClick = onEnable,
                                text = "启用",
                                icon = Icons.Default.Favorite,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        GlassButton(
                            onClick = onUninstall,
                            text = "卸载",
                            modifier = Modifier.weight(1f),
                            gradient = Brush.horizontalGradient(
                                listOf(
                                    Error.copy(alpha = 0.8f),
                                    Error.copy(alpha = 0.6f)
                                )
                            )
                        )
                    }
                }
            }
        }

        // 评论提交弹出层
        if (showReviewSheet) {
            ReviewSubmitSheet(
                rating = reviewRating,
                onRatingChange = { reviewRating = it },
                comment = reviewComment,
                onCommentChange = { reviewComment = it },
                isAnonymous = reviewAnonymous,
                onAnonymousChange = { reviewAnonymous = it },
                onSubmit = {
                    onSubmitReview(reviewRating, reviewComment, reviewAnonymous)
                    reviewComment = ""
                    showReviewSheet = false
                },
                onDismiss = { showReviewSheet = false }
            )
        }
    }

@Composable
private fun RealReviewItem(review: ModReview) {
    val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        .format(java.util.Date(review.timestamp))

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        padding = PaddingValues(12.dp),
        strong = false
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.verticalGradient(
                                    listOf(AccentPrimary, AccentGlow)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (review.isAnonymous) "匿" else review.userId.first().uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                    }
                    Column {
                        Text(
                            text = if (review.isAnonymous) "匿名用户" else review.userId,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = dateStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(5) { i ->
                        val filled = review.rating > i
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = if (filled) Warning else TextTertiary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
            if (review.comment.isNotBlank()) {
                Text(
                    text = review.comment,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun ReviewSubmitSheet(
    rating: Float,
    onRatingChange: (Float) -> Unit,
    comment: String,
    onCommentChange: (String) -> Unit,
    isAnonymous: Boolean,
    onAnonymousChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("写评论", style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 评分
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("评分:", style = MaterialTheme.typography.bodyMedium)
                    repeat(5) { i ->
                        Icon(
                            imageVector = if (rating > i) Icons.Default.Star else Icons.Default.Star,
                            contentDescription = "${i + 1}星",
                            tint = if (rating > i) Warning else TextTertiary,
                            modifier = Modifier
                                .size(28.dp)
                                .clickable { onRatingChange(i + 1f) }
                        )
                    }
                }
                // 评论
                OutlinedTextField(
                    value = comment,
                    onValueChange = onCommentChange,
                    placeholder = { Text("分享你的使用体验...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    maxLines = 5
                )
                // 匿名
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onAnonymousChange(!isAnonymous) }
                ) {
                    Checkbox(
                        checked = isAnonymous,
                        onCheckedChange = onAnonymousChange
                    )
                    Text(
                        "匿名分享",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit) {
                Text("提交", color = AccentGlow)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextTertiary)
            }
        }
    )
}

private fun formatDownloadCount(count: Int): String {
    return when {
        count >= 10000 -> String.format("%.1fw", count / 10000f)
        count >= 1000 -> String.format("%.1fk", count / 1000f)
        else -> count.toString()
    }
}

private fun getCoverColors(seed: String): List<Color> {
    val palettes = listOf(
        listOf(IceBlueGradientStart, IceBlueGradientMid, IceBlueGradientEnd),
        listOf(Color(0xFFA78BFA), Color(0xFF8B5CF6), Color(0xFF7C3AED)),
        listOf(Color(0xFFFB7185), Color(0xFFF43F5E), Color(0xFFE11D48)),
        listOf(Color(0xFF34D399), Color(0xFF10B981), Color(0xFF059669)),
        listOf(Color(0xFFFBBF24), Color(0xFFF59E0B), Color(0xFFD97706)),
        listOf(Color(0xFFF472B6), Color(0xFFEC4899), Color(0xFFDB2777))
    )
    val index = (seed.hashCode().and(Int.MAX_VALUE)) % palettes.size
    return palettes[index]
}
