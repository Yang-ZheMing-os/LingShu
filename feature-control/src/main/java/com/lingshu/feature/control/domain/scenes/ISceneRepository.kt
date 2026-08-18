package com.lingshu.feature.control.domain.scenes

/**
 * 场景仓库：内置三大场景 + 用户自定义场景（持久化）。
 *
 * 设计目标：
 *  - 不再硬编码「发消息 / 打车 / 导航 / 点外卖」每个业务的解析；
 *  - 新增业务 = 新增一个 GenericScene，用户也可以自己在设置里（或对话里说"帮我新增一个寄快递的场景"）新增；
 *  - 所有已注册场景都能被 [SceneResolver] 匹配 + [SceneExecutor] 执行。
 */
interface ISceneRepository {

    suspend fun allScenes(): List<GenericScene>

    suspend fun builtInScenes(): List<GenericScene>

    suspend fun customScenes(): List<GenericScene>

    /** 新增/覆盖自定义场景 */
    suspend fun upsertCustom(scene: GenericScene)

    suspend fun deleteCustom(sceneId: String)

    /** 用户说"新增一个 X 场景"时，AI 产出 JSON 草稿，这里持久化并参与匹配 */
    suspend fun importFromJson(json: String): Result<GenericScene>
}
