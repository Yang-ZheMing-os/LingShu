package com.lingshu.feature.memory.presentation

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.lingshu.feature.memory.domain.Memory
import com.lingshu.feature.memory.domain.MemoryType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryListScreen(
    onBackClick: () -> Unit = {},
    viewModel: MemoryViewModel = hiltViewModel()
) {
    val shortTermMemories by viewModel.shortTermMemories.collectAsState()
    val longTermMemories by viewModel.longTermMemories.collectAsState()
    val searchKeyword by viewModel.searchKeyword.collectAsState()
    val selectedType by viewModel.selectedType.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var showClearDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<Memory?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记忆管理") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "清空")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = searchKeyword,
                onValueChange = { viewModel.search(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索记忆...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "搜索")
                },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            TypeFilterChips(
                selectedType = selectedType,
                onTypeSelected = { viewModel.selectType(it) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            "短期记忆 (${shortTermMemories.size}/20)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (shortTermMemories.isEmpty()) {
                        item {
                            Text(
                                "暂无短期记忆",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    } else {
                        items(shortTermMemories) { memory ->
                            MemoryCard(
                                memory = memory,
                                onDelete = { showDeleteDialog = memory },
                                onSaveLongTerm = { viewModel.saveToLongTerm(memory) }
                            )
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "长期记忆 (${longTermMemories.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    val filteredMemories = viewModel.filteredLongTermMemories()
                    if (filteredMemories.isEmpty()) {
                        item {
                            Text(
                                "暂无长期记忆",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(filteredMemories) { memory ->
                            MemoryCard(
                                memory = memory,
                                onDelete = { showDeleteDialog = memory },
                                showSaveButton = false
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空确认") },
            text = { Text("确定要清空所有长期记忆吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllLongTerm()
                    showClearDialog = false
                }) {
                    Text("确认清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    showDeleteDialog?.let { memory ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("删除确认") },
            text = { Text("确定要删除这条记忆吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMemory(memory.id)
                    showDeleteDialog = null
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun TypeFilterChips(
    selectedType: MemoryType?,
    onTypeSelected: (MemoryType?) -> Unit
) {
    val types = MemoryType.values().toList()
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AssistChip(
            onClick = { onTypeSelected(null) },
            label = { Text("全部") },
            enabled = selectedType != null
        )
        types.forEach { type ->
            AssistChip(
                onClick = { onTypeSelected(type) },
                label = { Text(getTypeName(type)) },
                enabled = selectedType != type
            )
        }
    }
}

@Composable
private fun MemoryCard(
    memory: Memory,
    onDelete: () -> Unit,
    onSaveLongTerm: () -> Unit = {},
    showSaveButton: Boolean = true
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
                Text(
                    text = getTypeName(memory.type),
                    style = MaterialTheme.typography.labelMedium,
                    color = getTypeColor(memory.type)
                )
                Row {
                    if (showSaveButton) {
                        TextButton(onClick = onSaveLongTerm) {
                            Text("保存")
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = formatTimestamp(memory.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun getTypeName(type: MemoryType): String = when (type) {
    MemoryType.PREFERENCE -> "偏好"
    MemoryType.HABIT -> "习惯"
    MemoryType.FACT -> "事实"
    MemoryType.RELATIONSHIP -> "关系"
    MemoryType.EMOTIONAL -> "情感"
}

@Composable
private fun getTypeColor(type: MemoryType) = when (type) {
    MemoryType.PREFERENCE -> MaterialTheme.colorScheme.primary
    MemoryType.HABIT -> MaterialTheme.colorScheme.secondary
    MemoryType.FACT -> MaterialTheme.colorScheme.tertiary
    MemoryType.RELATIONSHIP -> MaterialTheme.colorScheme.primary
    MemoryType.EMOTIONAL -> MaterialTheme.colorScheme.error
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
