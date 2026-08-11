package com.lingshu.feature.guide.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingshu.core.ui.theme.Background
import com.lingshu.core.ui.theme.OnBackground
import com.lingshu.core.ui.theme.OnSurfaceVariant
import com.lingshu.core.ui.theme.Primary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityPlaceholderScreen() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "社区",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            )
        },
        containerColor = Background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Campaign,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Text(
                text = "社区即将上线",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = OnBackground
            )
            Text(
                text = "未来可在此浏览和分享 Mod、声音模型、知识库。\n当前版本暂未开放，敬请期待。",
                fontSize = 14.sp,
                color = OnSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
