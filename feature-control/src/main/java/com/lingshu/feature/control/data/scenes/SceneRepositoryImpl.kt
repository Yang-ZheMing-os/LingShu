package com.lingshu.feature.control.data.scenes

import android.content.Context
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.control.domain.scenes.BuiltInScenes
import com.lingshu.feature.control.domain.scenes.GenericScene
import com.lingshu.feature.control.domain.scenes.ISceneRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SceneRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val builtInScenes: BuiltInScenes
) : ISceneRepository {

    companion object {
        private const val TAG = "SceneRepo"
        private const val PREFS_NAME = "generic_scenes_store"
        private const val CUSTOM_KEY = "custom_scenes_json_array"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override suspend fun allScenes(): List<GenericScene> =
        builtInScenes() + customScenes()

    override suspend fun builtInScenes(): List<GenericScene> =
        builtInScenes.builtIns

    override suspend fun customScenes(): List<GenericScene> = withContext(Dispatchers.IO) {
        val raw = prefs.getString(CUSTOM_KEY, null)
            ?: return@withContext emptyList()
        runCatching { SceneModelsKtGsonBridge.parseScenes(raw) }
            .onFailure { LingShuLog.w(TAG, "自定义场景解析失败，按空列表", it) }
            .getOrDefault(emptyList())
    }

    override suspend fun upsertCustom(scene: GenericScene) = withContext(Dispatchers.IO) {
        require(!scene.builtIn) { "不能覆盖内置场景" }
        val current = customScenes().toMutableList()
        val idx = current.indexOfFirst { it.sceneId == scene.sceneId }
        if (idx >= 0) current[idx] = scene else current.add(scene)
        persistCustom(current)
        LingShuLog.i(TAG, "自定义场景已保存：${scene.sceneId}（共${current.size}个）")
    }

    override suspend fun deleteCustom(sceneId: String) = withContext(Dispatchers.IO) {
        val current = customScenes().filterNot { it.sceneId == sceneId }
        persistCustom(current)
    }

    override suspend fun importFromJson(json: String): Result<GenericScene> {
        return runCatching {
            val scene = SceneModelsKtGsonBridge.parseScene(json)
            upsertCustom(scene.copy(builtIn = false))
            scene
        }
    }

    private fun persistCustom(custom: List<GenericScene>) {
        val json = SceneModelsKtGsonBridge.serializeScenes(custom)
        prefs.edit().putString(CUSTOM_KEY, json).apply()
    }
}

/**
 * 不引入第三方 JSON 库，避免加依赖。
 * 用 Android SDK 自带 org.json 做 GenericScene 的序列化 / 反序列化。
 */
object SceneModelsKtGsonBridge {
    private const val FLD_SCENE_ID = "sceneId"
    private const val FLD_NAME = "displayName"
    private const val FLD_BUILTIN = "builtIn"
    private const val FLD_KW = "intentKeywords"
    private const val FLD_PRIO = "priority"
    private const val FLD_SLOTS = "slots"
    private const val FLD_STEPS = "steps"
    private const val FLD_COMPLETION = "completionText"

    private const val FLD_STEP_ID = "stepId"
    private const val FLD_STEP_ACTION = "action"
    private const val FLD_STEP_LABEL = "humanLabel"
    private const val FLD_STEP_BINDINGS = "slotBindings"
    private const val FLD_STEP_HINTS = "extractHints"
    private const val FLD_STEP_OK = "successLabel"
    private const val FLD_STEP_CONFIRM = "needUserConfirm"
    private const val FLD_STEP_NEXT = "nextStepId"

    private const val FLD_SLOT_NAME = "name"
    private const val FLD_SLOT_ASK = "askPrompt"
    private const val FLD_SLOT_REGEX = "extractionRegex"
    private const val FLD_SLOT_OPT = "optional"
    private const val FLD_SLOT_DEF = "defaultValue"

    fun serializeScenes(scenes: List<com.lingshu.feature.control.domain.scenes.GenericScene>): String {
        val arr = org.json.JSONArray()
        scenes.forEach { arr.put(encode(it)) }
        return arr.toString()
    }

    fun parseScenes(json: String): List<com.lingshu.feature.control.domain.scenes.GenericScene> {
        val arr = org.json.JSONArray(json)
        return (0 until arr.length()).mapNotNull { i ->
            runCatching { decode(arr.getJSONObject(i)) }.getOrNull()
        }
    }

    fun parseScene(json: String): com.lingshu.feature.control.domain.scenes.GenericScene =
        decode(JSONObject(json))

    private fun encode(s: com.lingshu.feature.control.domain.scenes.GenericScene): JSONObject {
        val obj = JSONObject()
        obj.put(FLD_SCENE_ID, s.sceneId)
        obj.put(FLD_NAME, s.displayName)
        obj.put(FLD_BUILTIN, s.builtIn)
        obj.put(FLD_PRIO, s.priority)
        obj.put(FLD_COMPLETION, s.completionText)
        obj.put(FLD_KW, org.json.JSONArray(s.intentKeywords))
        obj.put(
            FLD_SLOTS,
            org.json.JSONArray(s.slots.map { sl ->
                JSONObject().apply {
                    put(FLD_SLOT_NAME, sl.name)
                    put(FLD_SLOT_ASK, sl.askPrompt)
                    put(FLD_SLOT_REGEX, sl.extractionRegex ?: "")
                    put(FLD_SLOT_OPT, sl.optional)
                    put(FLD_SLOT_DEF, sl.defaultValue ?: "")
                }
            })
        )
        obj.put(
            FLD_STEPS,
            org.json.JSONArray(s.steps.map { st ->
                JSONObject().apply {
                    put(FLD_STEP_ID, st.stepId)
                    put(FLD_STEP_ACTION, st.action.name)
                    put(FLD_STEP_LABEL, st.humanLabel)
                    put(FLD_STEP_OK, st.successLabel ?: "")
                    put(FLD_STEP_CONFIRM, st.needUserConfirm)
                    put(FLD_STEP_NEXT, st.nextStepId ?: "")
                    put(FLD_STEP_BINDINGS, JSONObject(st.slotBindings as Map<String, String>))
                    put(
                        FLD_STEP_HINTS,
                        JSONObject(st.extractHints.mapValues { (_, v) -> org.json.JSONArray(v) })
                    )
                }
            })
        )
        return obj
    }

    private fun decode(o: JSONObject): com.lingshu.feature.control.domain.scenes.GenericScene {
        val slotsArr = o.optJSONArray(FLD_SLOTS) ?: org.json.JSONArray()
        val stepsArr = o.optJSONArray(FLD_STEPS) ?: org.json.JSONArray()
        val kwArr = o.optJSONArray(FLD_KW) ?: org.json.JSONArray()
        val slots = (0 until slotsArr.length()).map { i ->
            val s = slotsArr.getJSONObject(i)
            com.lingshu.feature.control.domain.scenes.SlotSpec(
                name = s.getString(FLD_SLOT_NAME),
                askPrompt = s.optString(FLD_SLOT_ASK, ""),
                extractionRegex = s.optString(FLD_SLOT_REGEX, "").ifBlank { null },
                optional = s.optBoolean(FLD_SLOT_OPT, false),
                defaultValue = s.optString(FLD_SLOT_DEF, "").ifBlank { null }
            )
        }
        val steps = (0 until stepsArr.length()).map { i ->
            val st = stepsArr.getJSONObject(i)
            val binds = st.optJSONObject(FLD_STEP_BINDINGS) ?: JSONObject()
            val bindsMap = buildMap { for (k in binds.keys()) put(k, binds.getString(k)) }
            val hints = st.optJSONObject(FLD_STEP_HINTS) ?: JSONObject()
            val hintsMap = buildMap {
                for (k in hints.keys()) {
                    val arr = hints.getJSONArray(k)
                    put(k, (0 until arr.length()).map { arr.getString(it) })
                }
            }
            com.lingshu.feature.control.domain.scenes.SceneStep(
                stepId = st.getString(FLD_STEP_ID),
                action = com.lingshu.feature.control.domain.scenes.StepActionType.valueOf(st.getString(FLD_STEP_ACTION)),
                humanLabel = st.optString(FLD_STEP_LABEL, ""),
                slotBindings = bindsMap,
                extractHints = hintsMap,
                successLabel = st.optString(FLD_STEP_OK, "").ifBlank { null },
                needUserConfirm = st.optBoolean(FLD_STEP_CONFIRM, false),
                nextStepId = st.optString(FLD_STEP_NEXT, "").ifBlank { null }
            )
        }
        val keywords = (0 until kwArr.length()).map { kwArr.getString(it) }
        return com.lingshu.feature.control.domain.scenes.GenericScene(
            sceneId = o.getString(FLD_SCENE_ID),
            displayName = o.optString(FLD_NAME, o.getString(FLD_SCENE_ID)),
            builtIn = o.optBoolean(FLD_BUILTIN, false),
            priority = o.optInt(FLD_PRIO, 0),
            intentKeywords = keywords,
            slots = slots,
            steps = steps,
            completionText = o.optString(FLD_COMPLETION, "搞定 ✅")
        )
    }
}
