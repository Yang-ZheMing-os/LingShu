package com.lingshu.feature.rag.presentation

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.lingshu.feature.rag.domain.Chunk
import com.lingshu.feature.rag.domain.Document
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagScreen(
    viewModel: RagViewModel = hiltViewModel()
) {
    val documents by viewModel.documents.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val uploadState by viewModel.uploadState.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val askState by viewModel.askState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val askQuery by viewModel.askQuery.collectAsState()

    var showDeleteDialog by remember { mutableStateOf<Document?>(null) }
    var showUploadDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RAG 知识库") },
                actions = {
                    IconButton(onClick = { showUploadDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "上传")
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
                    selected = selectedTab == RagTab.DOCUMENTS,
                    onClick = { viewModel.selectTab(RagTab.DOCUMENTS) },
                    text = { Text("文档") },
                    icon = { Icon(Icons.Default.Description, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == RagTab.CHAT,
                    onClick = { viewModel.selectTab(RagTab.CHAT) },
                    text = { Text("问答") },
                    icon = { Icon(Icons.Default.Chat, contentDescription = null) }
                )
            }

            when (selectedTab) {
                RagTab.DOCUMENTS -> {
                    DocumentsTab(
                        documents = documents,
                        searchQuery = searchQuery,
                        searchResults = searchResults,
                        onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                        onSearch = { viewModel.search(it) },
                        onDelete = { showDeleteDialog = it }
                    )
                }
                RagTab.CHAT -> {
                    ChatTab(
                        askQuery = askQuery,
                        askState = askState,
                        onAskQueryChange = { viewModel.updateAskQuery(it) },
                        onAsk = { viewModel.ask(it) }
                    )
                }
            }
        }
    }

    if (uploadState is UiState.Loading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("上传中") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("正在处理文档...")
                }
            },
            confirmButton = {}
        )
    }

    if (uploadState is UiState.Success) {
        AlertDialog(
            onDismissRequest = { viewModel.resetUploadState() },
            title = { Text("上传成功") },
            text = { Text("文档已成功上传并索引！") },
            confirmButton = {
                TextButton(onClick = { viewModel.resetUploadState() }) {
                    Text("确定")
                }
            }
        )
    }

    if (uploadState is UiState.Error) {
        AlertDialog(
            onDismissRequest = { viewModel.resetUploadState() },
            title = { Text("上传失败") },
            text = { Text("文档上传失败，请检查文件格式后重试。") },
            confirmButton = {
                TextButton(onClick = { viewModel.resetUploadState() }) {
                    Text("确定")
                }
            }
        )
    }

    showDeleteDialog?.let { doc ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("删除确认") },
            text = { Text("确定要删除文档\"${doc.name}\"吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDocument(doc.id)
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

    if (showUploadDialog) {
        AlertDialog(
            onDismissRequest = { showUploadDialog = false },
            title = { Text("上传文档") },
            text = { Text("请选择要上传的文档文件（支持 PDF、TXT、DOCX 等格式）。\n\n注意：这是演示版本，上传功能使用 Mock 数据。") },
            confirmButton = {
                TextButton(onClick = {
                    showUploadDialog = false
                }) {
                    Text("选择文件")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUploadDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun DocumentsTab(
    documents: List<Document>,
    searchQuery: String,
    searchResults: List<Chunk>,
    onSearchQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onDelete: (Document) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索文档内容...") },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = "搜索")
            },
            trailingIcon = {
                TextButton(onClick = { onSearch(searchQuery) }) {
                    Text("搜索")
                }
            },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (searchResults.isNotEmpty()) {
            Text(
                text = "搜索结果 (${searchResults.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(searchResults) { chunk ->
                    SearchResultCard(chunk = chunk)
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "全部文档 (${documents.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (documents.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "暂无文档，点击右上角上传",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(documents) { doc ->
                        DocumentCard(
                            document = doc,
                            onDelete = { onDelete(doc) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatTab(
    askQuery: String,
    askState: UiState<String>,
    onAskQueryChange: (String) -> Unit,
    onAsk: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "知识库问答",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "基于上传的文档进行智能问答",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = askQuery,
            onValueChange = onAskQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("输入你的问题...") },
            maxLines = 3
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { onAsk(askQuery) },
            modifier = Modifier.fillMaxWidth(),
            enabled = askQuery.isNotBlank() && askState !is UiState.Loading
        ) {
            if (askState is UiState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("思考中...")
            } else {
                Text("提问")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (askState) {
            is UiState.Success -> {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "回答",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = askState.data,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            is UiState.Error -> {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                ) {
                    Text(
                        text = "回答生成失败，请重试",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            else -> {}
        }
    }
}

@Composable
private fun DocumentCard(
    document: Document,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${formatFileSize(document.size)} · ${document.chunkCount} 个段落",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatDate(document.uploadedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
}

@Composable
private fun SearchResultCard(
    chunk: Chunk
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "相关度: ${String.format("%.2f", chunk.score * 100)}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "文档 ID: ${chunk.documentId.take(10)}...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = chunk.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3
            )
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        else -> "${String.format("%.1f", size / (1024.0 * 1024.0))} MB"
    }
}
