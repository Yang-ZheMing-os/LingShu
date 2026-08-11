package com.lingshu.feature.guide.presentation

import android.Manifest
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.lingshu.core.common.permission.PermissionHelper
import com.lingshu.core.common.permission.PermissionRequest
import com.lingshu.core.ui.component.GlassCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionQueueScreen(
    onAllPermissionsHandled: () -> Unit,
    viewModel: PermissionQueueViewModel = hiltViewModel()
) {
    val currentIndex by viewModel.currentIndex.collectAsState()
    val permissions = viewModel.permissions

    LaunchedEffect(currentIndex) {
        if (currentIndex >= permissions.size) {
            onAllPermissionsHandled()
        }
    }

    if (currentIndex < permissions.size) {
        val currentPermission = permissions[currentIndex]
        val permissionState = rememberPermissionState(
            permission = currentPermission.permission
        )

        LaunchedEffect(permissionState.status.isGranted) {
            if (permissionState.status.isGranted) {
                viewModel.onPermissionGranted()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            PermissionBackground()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                GlassCard(
                    modifier = Modifier
                        .size(160.dp)
                        .clip(RoundedCornerShape(28.dp)),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = getPermissionIcon(currentPermission.permission),
                            fontSize = 64.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))

                Text(
                    text = currentPermission.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = currentPermission.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 24.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(48.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.skipPermission()
                        },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                    ) {
                        Text(
                            text = "暂不开启",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Button(
                        onClick = {
                            permissionState.launchPermissionRequest()
                        },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                    ) {
                        Text(
                            text = "开启权限",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "${currentIndex + 1} / ${permissions.size}",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PermissionBackground(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .size(280.dp)
                .align(Alignment.TopEnd)
                .blur(
                    radius = 90.dp,
                    edgeTreatment = BlurredEdgeTreatment(RoundedCornerShape(140.dp))
                )
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                            Color.Transparent
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .size(220.dp)
                .align(Alignment.BottomStart)
                .blur(
                    radius = 70.dp,
                    edgeTreatment = BlurredEdgeTreatment(RoundedCornerShape(110.dp))
                )
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

private fun getPermissionIcon(permission: String): String {
    return when (permission) {
        Manifest.permission.RECORD_AUDIO -> "🎤"
        Manifest.permission.POST_NOTIFICATIONS -> "🔔"
        Manifest.permission.SYSTEM_ALERT_WINDOW -> "🎯"
        Manifest.permission.BODY_SENSORS -> "💓"
        Manifest.permission.ACCESS_FINE_LOCATION -> "📍"
        else -> "📋"
    }
}

@HiltViewModel
class PermissionQueueViewModel @Inject constructor() : ViewModel() {

    val permissions: List<PermissionRequest> = PermissionHelper.getRequiredPermissions()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _grantedPermissions = MutableStateFlow<Set<String>>(emptySet())
    val grantedPermissions: StateFlow<Set<String>> = _grantedPermissions.asStateFlow()

    fun onPermissionGranted() {
        if (_currentIndex.value < permissions.size) {
            val permission = permissions[_currentIndex.value].permission
            _grantedPermissions.value = _grantedPermissions.value + permission
        }
        moveToNext()
    }

    fun skipPermission() {
        moveToNext()
    }

    private fun moveToNext() {
        viewModelScope.launch {
            if (_currentIndex.value < permissions.size) {
                _currentIndex.value++
            }
        }
    }
}
