package com.lingshu.agent.feature.persona

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.agent.core.model.BigFiveTraits
import com.lingshu.agent.core.model.Message
import com.lingshu.agent.core.model.Persona
import com.lingshu.agent.core.model.PersonaRules
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 人格系统 ViewModel
 *
 * 为以下UI场景提供数据和操作：
 * 1. 人格列表页（展示所有人格、切换激活、搜索筛选）
 * 2. 人格详情/编辑页（编辑人格属性、维度、记忆、规则等）
 * 3. 人格导入导出页（JSON导入/导出、分享）
 * 4. 对话页（获取激活人格、构建Prompt预览）
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PersonaViewModel @Inject constructor(
    private val repository: PersonaRepository,
    private val manager: PersonaManager
) : ViewModel() {

    companion object {
        private const val TAG = "PersonaViewModel"
    }

    // ==================== UI 事件 Flow（一次性事件） ====================

    private val _event = MutableSharedFlow<PersonaEvent>()
    val event: Flow<PersonaEvent> = _event.asSharedFlow()

    // ==================== UI 状态 ====================

    /** 搜索关键字 */
    private val _searchKeyword = MutableStateFlow("")
    val searchKeyword: StateFlow<String> = _searchKeyword.asStateFlow()

    /** 当前筛选标签 */
    private val _filterTag = MutableStateFlow<String?>(null)
    val filterTag: StateFlow<String?> = _filterTag.asStateFlow()

    /** 筛选模式：全部/系统/自定义 */
    private val _filterMode = MutableStateFlow(PersonaFilterMode.ALL)
    val filterMode: StateFlow<PersonaFilterMode> = _filterMode.asStateFlow()

    /** 当前正在编辑的人格ID（null表示列表页） */
    private val _editingPersonaId = MutableStateFlow<String?>(null)
    val editingPersonaId: StateFlow<String?> = _editingPersonaId.asStateFlow()

    /** 人格列表主UI状态 */
    val listUiState: StateFlow<PersonaListUiState> = combine(
        repository.observeAll(),
        _searchKeyword,
        _filterTag,
        _filterMode,
        manager.activePersona
    ) { allPersonas, keyword, tag, mode, active ->

        // 应用筛选
        var filtered = allPersonas

        // 筛选模式
        filtered = when (mode) {
            PersonaFilterMode.ALL -> filtered
            PersonaFilterMode.SYSTEM -> filtered.filter { it.isSystem }
            PersonaFilterMode.CUSTOM -> filtered.filter { !it.isSystem }
        }

        // 标签筛选
        if (!tag.isNullOrBlank()) {
            filtered = filtered.filter { it.tags.contains(tag) }
        }

        // 关键字搜索
        if (keyword.isNotBlank()) {
            val kw = keyword.lowercase()
            filtered = filtered.filter { p ->
                p.name.lowercase().contains(kw) ||
                        p.tags.any { it.lowercase().contains(kw) } ||
                        p.toneTags.any { it.lowercase().contains(kw) }
            }
        }

        PersonaListUiState(
            personas = filtered,
            activePersonaId = active?.personaId,
            searchKeyword = keyword,
            filterTag = tag,
            filterMode = mode,
            isEmpty = filtered.isEmpty(),
            totalCount = allPersonas.size,
            systemCount = allPersonas.count { it.isSystem },
            customCount = allPersonas.count { !it.isSystem }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PersonaListUiState()
    )

    /** 当前编辑人格的详细UI状态 */
    val editorUiState: StateFlow<PersonaEditorUiState?> = combine(
        _editingPersonaId,
        repository.observeAll()
    ) { editingId, allPersonas ->
        editingId?.let { id ->
            val persona = allPersonas.find { it.personaId == id }
            persona?.let { PersonaEditorUiState(persona = it) }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
    )

    /** 当前激活人格的UI状态（对话页使用） */
    val activeUiState: StateFlow<ActivePersonaUiState> = manager.activePersona
        .map { persona ->
            ActivePersonaUiState(
                persona = persona,
                traitPercentages = persona?.let { manager.getTraitPercentages(it.traits) } ?: emptyMap()
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ActivePersonaUiState()
        )

    // ==================== 列表页操作 ====================

    /** 设置搜索关键字 */
    fun setSearchKeyword(keyword: String) {
        _searchKeyword.value = keyword
    }

    /** 设置筛选标签 */
    fun setFilterTag(tag: String?) {
        _filterTag.value = tag
    }

    /** 设置筛选模式 */
    fun setFilterMode(mode: PersonaFilterMode) {
        _filterMode.value = mode
    }

    /** 切换激活人格 */
    fun switchActivePersona(personaId: String) {
        viewModelScope.launch {
            val success = manager.setActivePersonaSuspend(personaId)
            if (success) {
                _event.emit(PersonaEvent.PersonaActivated(personaId))
            } else {
                _event.emit(PersonaEvent.OperationFailed("切换人格失败"))
            }
        }
    }

    /** 进入编辑模式（新建或编辑） */
    fun startEditing(personaId: String?) {
        _editingPersonaId.value = personaId
    }

    /** 退出编辑模式 */
    fun stopEditing() {
        _editingPersonaId.value = null
    }

    /** 删除人格 */
    fun deletePersona(personaId: String) {
        viewModelScope.launch {
            val persona = repository.getById(personaId)
            val success = repository.delete(personaId)
            if (success) {
                if (_editingPersonaId.value == personaId) {
                    _editingPersonaId.value = null
                }
                _event.emit(PersonaEvent.PersonaDeleted(personaId))
            } else {
                val reason = if (persona?.isSystem == true) "系统人格不可删除" else "删除失败"
                _event.emit(PersonaEvent.OperationFailed(reason))
            }
        }
    }

    // ==================== 编辑器操作：人格基础属性编辑 ====================

    /** 创建新人格（内置默认值） */
    fun createNewPersona(): Persona {
        return Persona(
            personaId = "new_${System.currentTimeMillis()}",
            name = "新人设",
            traits = BigFiveTraits.neutral(),
            rules = PersonaRules()
        )
    }

    /** 保存人格（新增或更新） */
    fun savePersona(persona: Persona) {
        viewModelScope.launch {
            repository.upsert(persona)
            _event.emit(PersonaEvent.PersonaSaved(persona.personaId))
        }
    }

    /** 更新人格名称 */
    fun updateName(name: String) {
        updateEditingPersona { it.copy(name = name) }
    }

    /** 更新头像 */
    fun updateAvatar(avatar: String?) {
        updateEditingPersona { it.copy(avatar = avatar) }
    }

    /** 更新开场白 */
    fun updateOpeningLine(line: String?) {
        updateEditingPersona { it.copy(openingLine = line) }
    }

    /** 更新自定义 System Prompt */
    fun updateSystemPrompt(prompt: String) {
        updateEditingPersona { it.copy(systemPrompt = prompt) }
    }

    /** 更新音色ID */
    fun updateVoiceId(voiceId: String?) {
        updateEditingPersona { it.copy(voiceId = voiceId) }
    }

    /** 更新 Temperature（0-2，步长 0.01） */
    fun updateTemperature(temperature: Double) {
        updateEditingPersona { it.copy(temperature = temperature.coerceIn(0.0, 2.0)) }
    }

    /** 更新大五人格维度（整体替换） */
    fun updateTraits(traits: BigFiveTraits) {
        updateEditingPersona { it.copy(traits = traits.clamp()) }
    }

    /** 更新单个维度值 */
    fun updateSingleTrait(traitType: TraitType, value: Double) {
        updateEditingPersona { persona ->
            val clamped = value.coerceIn(0.0, 1.0)
            val newTraits = when (traitType) {
                TraitType.OPENNESS -> persona.traits.copy(openness = clamped)
                TraitType.CONSCIENTIOUSNESS -> persona.traits.copy(conscientiousness = clamped)
                TraitType.EXTRAVERSION -> persona.traits.copy(extraversion = clamped)
                TraitType.AGREEABLENESS -> persona.traits.copy(agreeableness = clamped)
                TraitType.NEUROTICISM -> persona.traits.copy(neuroticism = clamped)
            }
            persona.copy(traits = newTraits)
        }
    }

    /** 更新人格规则 */
    fun updateRules(rules: PersonaRules) {
        updateEditingPersona { it.copy(rules = rules) }
    }

    /** 更新单个规则 */
    fun updateSingleRule(ruleType: RuleType, enabled: Boolean) {
        updateEditingPersona { persona ->
            val rules = persona.rules
            val newRules = when (ruleType) {
                RuleType.CAN_INITIATE -> rules.copy(canInitiateConversation = enabled)
                RuleType.CONFIRM_BEFORE_EXECUTE -> rules.copy(confirmBeforeExecute = enabled)
                RuleType.CAN_USE_SENSITIVE -> rules.copy(canUseSensitiveOperations = enabled)
                RuleType.CAN_ACCESS_INTERNET -> rules.copy(canAccessInternet = enabled)
            }
            persona.copy(rules = newRules)
        }
    }

    /** 添加标签 */
    fun addTag(tag: String) {
        updateEditingPersona { persona ->
            val trimmed = tag.trim()
            if (trimmed.isBlank() || persona.tags.contains(trimmed)) return@updateEditingPersona persona
            persona.copy(tags = persona.tags + trimmed)
        }
    }

    /** 移除标签 */
    fun removeTag(tag: String) {
        updateEditingPersona { persona ->
            persona.copy(tags = persona.tags - tag)
        }
    }

    /** 添加语气标签 */
    fun addToneTag(tone: String) {
        updateEditingPersona { persona ->
            val trimmed = tone.trim()
            if (trimmed.isBlank() || persona.toneTags.contains(trimmed)) return@updateEditingPersona persona
            persona.copy(toneTags = persona.toneTags + trimmed)
        }
    }

    /** 移除语气标签 */
    fun removeToneTag(tone: String) {
        updateEditingPersona { persona ->
            persona.copy(toneTags = persona.toneTags - tone)
        }
    }

    /** 添加示例对话 */
    fun addExampleDialogue(user: String, assistant: String) {
        updateEditingPersona { persona ->
            persona.copy(exampleDialogues = persona.exampleDialogues + (user to assistant))
        }
    }

    /** 移除指定索引的示例对话 */
    fun removeExampleDialogue(index: Int) {
        updateEditingPersona { persona ->
            if (index < 0 || index >= persona.exampleDialogues.size) return@updateEditingPersona persona
            val list = persona.exampleDialogues.toMutableList().apply { removeAt(index) }
            persona.copy(exampleDialogues = list)
        }
    }

    /** 更新编辑中的人格修改写入数据库 */
    private fun updateEditingPersona(transform: (Persona) -> Persona) {
        viewModelScope.launch {
            val id = _editingPersonaId.value ?: return@launch
            val current = repository.getById(id) ?: return@launch
            val updated = transform(current).copy(updatedAt = System.currentTimeMillis())
            repository.upsert(updated)
        }
    }

    // ==================== 编辑器操作：记忆管理 ====================

    /** 向正在编辑的人格添加一条记忆 */
    fun addMemoryToEditing(memory: String) {
        viewModelScope.launch {
            val id = _editingPersonaId.value ?: return@launch
            repository.addMemory(id, memory)
        }
    }

    /** 删除编辑中的人格移除指定索引的记忆 */
    fun removeMemoryFromEditing(index: Int) {
        viewModelScope.launch {
            val id = _editingPersonaId.value ?: return@launch
            repository.removeMemory(id, index)
        }
    }

    /** 清空编辑中人格的所有记忆 */
    fun clearMemoriesOfEditing() {
        viewModelScope.launch {
            val id = _editingPersonaId.value ?: return@launch
            repository.clearMemories(id)
        }
    }

    // ==================== 导入导出 ====================

    /** 导出指定人格为JSON */
    fun exportPersonaToJson(persona: Persona): String {
        return repository.exportToJson(persona)
    }

    /** 导出所有人格为JSON */
    suspend fun exportAllPersonasToJson(): String {
        // 从数据库读取一次获取所有
        val all = listUiState.value.personas
        return repository.exportAllToJson(all)
    }

    /** 从JSON导入人格 */
    fun importPersonasFromJson(json: String): Int {
        var count = 0
        viewModelScope.launch {
            val personas = repository.importListFromJson(json, overwriteId = false)
            if (personas.isNotEmpty()) {
                repository.upsertAll(personas)
                count = personas.size
                _event.emit(PersonaEvent.PersonasImported(personas.size))
            } else {
                _event.emit(PersonaEvent.OperationFailed("导入失败：JSON格式不正确"))
            }
        }
        return count
    }

    // ==================== 人格演化（供对话页调用 ====================

    /**
     * 触发人格演化（对话反馈后调用）
     */
    fun evolveActivePersona(feedback: Message.Feedback, emotion: String?) {
        viewModelScope.launch {
            val active = manager.getActivePersona() ?: return@launch
            repository.evolvePersona(active.personaId, feedback, emotion)
        }
    }

    /** 从消息列表提取记忆注入激活人格 */
    fun injectMemoriesFromMessages(messages: List<Message>) {
        viewModelScope.launch {
            val active = manager.getActivePersona() ?: return@launch
            val count = repository.injectMemoriesFromDialogue(active.personaId, messages)
            if (count > 0) {
                _event.emit(PersonaEvent.MemoriesInjected(count))
            }
        }
    }

    /** 预览 System Prompt */
    suspend fun previewSystemPrompt(
        persona: Persona,
        userContext: Map<String, Any> = emptyMap()
    ): String {
        return manager.buildSystemPrompt(persona, userContext)
    }
}

// ==================== UI State 数据类 ====================

/** 人格列表筛选模式 */
enum class PersonaFilterMode(val displayName: String) {
    ALL("全部"),
    SYSTEM("系统人格"),
    CUSTOM("自定义人格")
}

/** 大五人格维度类型 */
enum class TraitType(val displayName: String) {
    OPENNESS("开放性"),
    CONSCIENTIOUSNESS("尽责性"),
    EXTRAVERSION("外向性"),
    AGREEABLENESS("宜人性"),
    NEUROTICISM("神经质")
}

/** 人格规则类型 */
enum class RuleType(val displayName: String) {
    CAN_INITIATE("可主动发起对话"),
    CONFIRM_BEFORE_EXECUTE("操作前需确认"),
    CAN_USE_SENSITIVE("允许敏感操作"),
    CAN_ACCESS_INTERNET("可访问互联网")
}

/** 一次性事件 */
sealed class PersonaEvent {
    data class PersonaSaved(val personaId: String) : PersonaEvent()
    data class PersonaDeleted(val personaId: String) : PersonaEvent()
    data class PersonaActivated(val personaId: String) : PersonaEvent()
    data class PersonasImported(val count: Int) : PersonaEvent()
    data class MemoriesInjected(val count: Int) : PersonaEvent()
    data class OperationFailed(val reason: String) : PersonaEvent()
}

/** 人格列表页UI状态 */
data class PersonaListUiState(
    val personas: List<Persona> = emptyList(),
    val activePersonaId: String? = null,
    val searchKeyword: String = "",
    val filterTag: String? = null,
    val filterMode: PersonaFilterMode = PersonaFilterMode.ALL,
    val isEmpty: Boolean = true,
    val totalCount: Int = 0,
    val systemCount: Int = 0,
    val customCount: Int = 0
) {
    /** 所有人格标签集合（用于标签筛选Chip） */
    val allTags: Set<String> get() = personas.flatMap { it.tags }.toSet()
}

/** 人格编辑器页UI状态 */
data class PersonaEditorUiState(
    val persona: Persona
)

/** 激活人格UI状态 */
data class ActivePersonaUiState(
    val persona: Persona? = null,
    val traitPercentages: Map<String, Int> = emptyMap()
)
