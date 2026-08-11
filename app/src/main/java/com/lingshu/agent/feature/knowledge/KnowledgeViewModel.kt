package com.lingshu.agent.feature.knowledge

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.agent.feature.model.ModelResponse
import com.lingshu.agent.utils.FileHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * 知识库 ViewModel
 *
 * 绑定UI场景：
 * 1. 知识库首页：文档列表、索引状态统计、RAG开关
 * 2. 文档上传页：选择文件 → 处理进度 → 入库结果
 * 3. 智能问答页：输入问题 → 显示思考中 → 展示回答 + 引用来源
 * 4. 问答历史页：历史记录、再次编辑问题
 * 5. 安装引导页：Ollama/Termux 安装引导、可用性检测
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class KnowledgeViewModel @Inject constructor(
    private val manager: KnowledgeManager,
    private val fileHelper: FileHelper
) : ViewModel() {

    companion object {
        private const val TAG = "KnowledgeViewModel"
    }

    // ==================== 一次性事件 ====================
    private val _event = MutableSharedFlow<KnowledgeUiEvent>(extraBufferCapacity = 16)
    val event: SharedFlow<KnowledgeUiEvent> = _event.asSharedFlow()

    // ==================== 基础配置状态 ====================

    /** RAG 是否启用（问问题时是否检索知识库） */
    private val _ragEnabled = MutableStateFlow(true)
    val ragEnabled: StateFlow<Boolean> = _ragEnabled.asStateFlow()

    /** 检索 topK */
    private val _topK = MutableStateFlow(KnowledgeManager.DEFAULT_TOP_K)
    val topK: StateFlow<Int> = _topK.asStateFlow()

    // ==================== 索引状态（转发自 KnowledgeManager） ====================

    val storeStats: Flow<VectorStoreStats> = manager.storeStats
    val isIndexing: StateFlow<Boolean> = manager.isIndexing
    val indexingProgress: StateFlow<IndexingProgress> = manager.indexingProgress

    // ==================== 文档列表 ====================

    /** 已索引的文档ID列表（UI层需要展示文件名等信息的话，可由 events 自行扩展） */
    private val _documentIds = MutableStateFlow<List<String>>(emptyList())
    val documentIds: StateFlow<List<String>> = _documentIds.asStateFlow()

    // ==================== 问答状态 ====================

    /** 问答历史 */
    val qaHistory: StateFlow<List<QaEntry>> = manager.qaHistory

    /** 问答输入框内容 */
    private val _questionInput = MutableStateFlow("")
    val questionInput: StateFlow<String> = _questionInput.asStateFlow()

    /** 问答加载中 */
    private val _isAsking = MutableStateFlow(false)
    val isAsking: StateFlow<Boolean> = _isAsking.asStateFlow()

    /** 当前正在回答的QA条目（对话页展示） */
    private val _currentAnswer = MutableStateFlow<QaEntry?>(null)
    val currentAnswer: StateFlow<QaEntry?> = _currentAnswer.asStateFlow()

    // ==================== Ollama 环境状态 ====================

    private val _ollamaAvailable = MutableStateFlow<Boolean?>(null)
    val ollamaAvailable: StateFlow<Boolean?> = _ollamaAvailable.asStateFlow()

    private val _termuxInstalled = MutableStateFlow<Boolean?>(null)
    val termuxInstalled: StateFlow<Boolean?> = _termuxInstalled.asStateFlow()

    // ==================== 初始化 ====================
    init {
        // 订阅 manager 的通用事件，转发给 UI 层（转换类型）
        manager.events
            .onEach { internalEvent ->
                when (internalEvent) {
                    KnowledgeEvent.IndexingBusy ->
                        _event.emit(KnowledgeUiEvent.ShowToast("正在处理其他文档，请稍候"))
                    is KnowledgeEvent.FileTypeNotSupported ->
                        _event.emit(KnowledgeUiEvent.ShowToast("不支持的文件类型：.${internalEvent.ext}"))
                    is KnowledgeEvent.DocumentEmpty ->
                        _event.emit(KnowledgeUiEvent.ShowToast("${internalEvent.name} 无有效内容"))
                    is KnowledgeEvent.DocumentAdded -> {
                        _event.emit(KnowledgeUiEvent.DocumentAddedUi(
                            internalEvent.docId, internalEvent.name, internalEvent.chunks
                        ))
                        refreshDocumentList()
                    }
                    is KnowledgeEvent.DocumentDeleted -> {
                        _event.emit(KnowledgeUiEvent.DocumentDeletedUi(internalEvent.docId))
                        refreshDocumentList()
                    }
                    KnowledgeEvent.ClearedAll -> {
                        _event.emit(KnowledgeUiEvent.AllCleared)
                        _documentIds.value = emptyList()
                    }
                    is KnowledgeEvent.ErrorOccurred ->
                        _event.emit(KnowledgeUiEvent.ShowError(internalEvent.msg))
                    is KnowledgeEvent.RagAnswerFailed ->
                        _event.emit(KnowledgeUiEvent.RagAnswerFailedUi(internalEvent.reason))
                    is KnowledgeEvent.RagNoContextFallback ->
                        _event.emit(KnowledgeUiEvent.RagNoContextUi(internalEvent.question))
                }
            }
            .launchIn(viewModelScope)

        refreshDocumentList()
        checkEnvironment()
    }

    // ==================== 文档操作 ====================

    /**
     * 刷新文档ID列表
     */
    fun refreshDocumentList() {
        viewModelScope.launch {
            _documentIds.value = manager.listAllDocuments()
        }
    }

    /**
     * 通过 URI 添加文件（来自系统文件选择器）
     */
    fun addDocumentFromUri(uri: Uri) {
        viewModelScope.launch {
            val file = runCatching {
                // FileHelper 负责把 content:// URI 拷贝到 app 私有目录
                fileHelper.copyUriToCache(uri, "knowledge_${System.currentTimeMillis()}")
            }.getOrElse {
                Log.e(TAG, "读取URI失败: ${it.message}", it)
                _event.emit(KnowledgeUiEvent.ShowError("读取文件失败：${it.message}"))
                null
            } ?: return@launch

            val docId = manager.addDocumentFile(file)
            if (docId == null) {
                // 错误事件已在 manager 内部转发
            }
            // 刷新列表
            refreshDocumentList()
        }
    }

    /**
     * 通过 File 对象直接添加
     */
    fun addDocumentFile(file: File) {
        viewModelScope.launch {
            manager.addDocumentFile(file)
            refreshDocumentList()
        }
    }

    /**
     * 添加纯文本内容
     */
    fun addDocumentText(content: String, sourceName: String = "纯文本") {
        viewModelScope.launch {
            val docId = "text_${System.currentTimeMillis()}"
            val ok = manager.addDocumentText(content, docId, sourceName)
            if (ok) refreshDocumentList()
        }
    }

    /**
     * 删除文档
     */
    fun deleteDocument(docId: String) {
        viewModelScope.launch {
            val ok = manager.deleteDocument(docId)
            if (ok) {
                _event.emit(KnowledgeUiEvent.ShowToast("文档已删除"))
            }
        }
    }

    /**
     * 清空知识库（带二次确认，UI层先弹确认再调用本方法）
     */
    fun clearAllDocuments() {
        viewModelScope.launch {
            manager.clearAll()
            _event.emit(KnowledgeUiEvent.ShowToast("知识库已清空"))
        }
    }

    // ==================== 问答操作 ====================

    /** 更新输入框内容（双向绑定） */
    fun setQuestionInput(text: String) {
        _questionInput.value = text
    }

    /**
     * 提交问题并请求回答
     */
    fun submitQuestion() {
        val q = _questionInput.value.trim()
        if (q.isBlank()) {
            viewModelScope.launch {
                _event.emit(KnowledgeUiEvent.ShowToast("请输入问题"))
            }
            return
        }
        if (_isAsking.value) return

        _isAsking.value = true
        _currentAnswer.value = null

        viewModelScope.launch {
            try {
                val response: ModelResponse = manager.ask(
                    question = q,
                    useKnowledge = _ragEnabled.value,
                    topK = _topK.value
                )
                // 最新回答一定在 history 最后一条
                val latest = qaHistory.value.lastOrNull()
                if (latest != null && latest.question == q) {
                    _currentAnswer.value = latest
                }
                if (!response.isSuccess) {
                    _event.emit(KnowledgeUiEvent.ShowError(
                        response.errorMessage ?: "回答失败"
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "提问异常: ${e.message}", e)
                _event.emit(KnowledgeUiEvent.ShowError("提问失败：${e.message}"))
            } finally {
                _isAsking.value = false
            }
        }
    }

    /**
     * 基于历史问题再次提问
     */
    fun askFromHistory(entryId: String) {
        val entry = qaHistory.value.find { it.id == entryId } ?: return
        _questionInput.value = entry.question
        submitQuestion()
    }

    /**
     * 清空问答历史
     */
    fun clearQaHistory() {
        manager.clearQaHistory()
        _currentAnswer.value = null
        viewModelScope.launch {
            _event.emit(KnowledgeUiEvent.ShowToast("问答历史已清空"))
        }
    }

    /** 切换 RAG 开关 */
    fun toggleRagEnabled() {
        _ragEnabled.value = !_ragEnabled.value
    }

    /** 设置检索 topK */
    fun setTopK(k: Int) {
        _topK.value = k.coerceIn(1, 20)
    }

    // ==================== 环境检测 ====================

    /**
     * 检测 Ollama 和 Termux 环境
     */
    fun checkEnvironment() {
        viewModelScope.launch {
            _termuxInstalled.value = manager.isTermuxInstalled()
            _ollamaAvailable.value = manager.isOllamaAvailable()
            _event.emit(KnowledgeUiEvent.EnvironmentChecked(
                termuxOk = _termuxInstalled.value == true,
                ollamaOk = _ollamaAvailable.value == true
            ))
        }
    }

    /**
     * 打开 Termux 或跳转下载
     */
    fun openTermuxOrStore(): Boolean = manager.openTermuxOrStore()

    /** 获取 Ollama 安装引导文字 */
    fun getSetupGuide(): String = manager.getOllamaSetupGuide()
}

// ==================== UI 事件 ====================

sealed class KnowledgeUiEvent {
    data class ShowToast(val msg: String) : KnowledgeUiEvent()
    data class ShowError(val msg: String) : KnowledgeUiEvent()
    data class DocumentAddedUi(val docId: String, val name: String, val chunks: Int) : KnowledgeUiEvent()
    data class DocumentDeletedUi(val docId: String) : KnowledgeUiEvent()
    object AllCleared : KnowledgeUiEvent()
    data class RagAnswerFailedUi(val reason: String) : KnowledgeUiEvent()
    data class RagNoContextUi(val question: String) : KnowledgeUiEvent()
    data class EnvironmentChecked(val termuxOk: Boolean, val ollamaOk: Boolean) : KnowledgeUiEvent()
}
