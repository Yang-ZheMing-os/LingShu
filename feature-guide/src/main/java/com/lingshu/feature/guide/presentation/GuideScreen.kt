package com.lingshu.feature.guide.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.pager.ExperimentalPagerApi
import com.google.accompanist.pager.HorizontalPager
import com.google.accompanist.pager.HorizontalPagerIndicator
import com.google.accompanist.pager.rememberPagerState
import com.lingshu.feature.guide.data.GuidePage
import com.lingshu.feature.guide.presentation.components.GuideBackground
import com.lingshu.feature.guide.presentation.components.GuideBottomBar
import com.lingshu.feature.guide.presentation.components.GuidePageItem

@OptIn(ExperimentalPagerApi::class)
@Composable
fun GuideScreen(
    onGuideComplete: () -> Unit,
    viewModel: GuideViewModel = hiltViewModel()
) {
    val pagerState = rememberPagerState()
    val currentPage by viewModel.currentPage.collectAsState()
    val shouldShowPermissionQueue by viewModel.shouldShowPermissionQueue.collectAsState()

    LaunchedEffect(pagerState.currentPage) {
        viewModel.onPageChanged(pagerState.currentPage)
    }

    LaunchedEffect(currentPage) {
        if (pagerState.currentPage != currentPage) {
            pagerState.animateScrollToPage(currentPage)
        }
    }

    LaunchedEffect(shouldShowPermissionQueue) {
        if (shouldShowPermissionQueue) {
            onGuideComplete()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        GuideBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(64.dp))

            HorizontalPager(
                count = GuidePage.pageCount,
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                GuidePageItem(page = GuidePage.pages[page])
            }

            Spacer(modifier = Modifier.height(32.dp))

            HorizontalPagerIndicator(
                pagerState = pagerState,
                modifier = Modifier.padding(16.dp),
                activeColor = MaterialTheme.colorScheme.primary,
                inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                indicatorWidth = 8.dp,
                indicatorHeight = 8.dp,
                spacing = 8.dp
            )

            Spacer(modifier = Modifier.height(24.dp))

            GuideBottomBar(
                currentPage = currentPage,
                isLastPage = viewModel.isLastPage(),
                onSkip = {
                    viewModel.markGuideComplete()
                },
                onNext = {
                    if (viewModel.isLastPage()) {
                        viewModel.markGuideComplete()
                    } else {
                        viewModel.nextPage()
                    }
                }
            )

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}
