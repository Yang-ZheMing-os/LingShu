package com.lingshu.agent.feature.mod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// ================ Mod Manifest 数据结构（与实际代码对齐） ================

/**
 * Mod分类（对应ModCategory）
 */
enum class ModCategoryTest {
    PERSONA,    // 人格包
    SKILL,      // 技能包
    THEME,      // 主题包
    AUTOMATION, // 自动化包
    DATA        // 数据包
}

/**
 * .lspack的manifest.json对应的数据类
 */
data class ModManifest(
    val id: String,                        // 唯一ID（必填）
    val name: String,                      // 显示名称（必填）
    val version: String,                   // 语义化版本号（必填）如"1.2.3"
    val versionCode: Int,                  // 版本号整数（必填）如10203
    val author: String,                    // 作者（必填）
    val description: String,               // 简介（必填）
    val category: ModCategoryTest,         // 分类（必填）
    val minAppVersion: String,             // 最低灵枢App版本要求（必填）如"1.0.0"
    val maxAppVersion: String? = null,     // 最高灵枢App版本（可选）
    val dependencies: List<String> = emptyList(), // 依赖的其他Mod ID列表
    val tags: List<String> = emptyList(),
    val entryPoint: String? = null,        // 入口脚本/类（SKILL/AUTOMATION必填）
    val icon: String? = null,              // 图标文件路径
    val readme: String? = null             // README文件路径
)

// ================ 校验异常类 ================

/**
 * Manifest校验异常基类
 */
open class ModManifestValidationException(message: String) : Exception(message)

/** 缺少必填字段异常 */
class MissingRequiredFieldException(fieldName: String)
    : ModManifestValidationException("缺少必填字段: $fieldName")

/** 字段格式非法 */
class InvalidFieldFormatException(fieldName: String, detail: String)
    : ModManifestValidationException("字段格式非法: $fieldName - $detail")

/** 版本不兼容异常（灵枢App版本低于Mod要求的minAppVersion） */
class ModVersionIncompatibleException(modId: String, minRequired: String, current: String)
    : ModManifestValidationException("Mod $modId 要求灵枢版本 >= $minRequired，当前为 $current")

/** Mod ID冲突 */
class ModIdConflictException(id: String)
    : ModManifestValidationException("Mod ID冲突: $id 已存在")

// ================ 校验器 ================

/**
 * Mod Manifest校验器
 * 规则：
 * 1. 所有必填字段(id/name/version/versionCode/author/description/category/minAppVersion)非空
 * 2. id 合法: 非空，长度2-64，只允许小写字母数字和下划线(_)和点(.)
 * 3. version 符合语义化版本格式: "主.次.修"如1.2.3
 * 4. versionCode > 0
 * 5. minAppVersion 合法格式，且 <= 当前灵枢App版本
 * 6. category 在合法枚举范围内
 * 7. SKILL/AUTOMATION类型必须有entryPoint
 */
class ModManifestValidator(private val currentAppVersion: String = "1.0.0") {

    companion object {
        /** Mod ID格式正则：小写字母数字 + 下划线 + 点，长度2~64 */
        private val ID_REGEX = Regex("^[a-z0-9_.]{2,64}$")
        /** 语义化版本：主.次.修 三段数字 */
        private val SEMVER_REGEX = Regex("^\\d+\\.\\d+\\.\\d+$")
    }

    /**
     * 校验manifest完整性与合法性
     * @throws ModManifestValidationException 校验失败时抛出对应异常
     */
    fun validate(manifest: ModManifest) {
        // 1. 必填字段校验
        checkRequired("id", manifest.id)
        checkRequired("name", manifest.name)
        checkRequired("version", manifest.version)
        checkRequired("author", manifest.author)
        checkRequired("description", manifest.description)
        checkRequired("minAppVersion", manifest.minAppVersion)

        // versionCode特殊：必须>0
        if (manifest.versionCode <= 0) {
            throw MissingRequiredFieldException("versionCode (必须>0)")
        }

        // 2. ID格式校验
        if (!ID_REGEX.matches(manifest.id)) {
            throw InvalidFieldFormatException("id",
                "只允许小写字母、数字、下划线(_)和点(.)，长度2~64。实际='${manifest.id}'")
        }

        // 3. version格式校验
        if (!SEMVER_REGEX.matches(manifest.version)) {
            throw InvalidFieldFormatException("version",
                "必须为语义化版本格式X.Y.Z(例1.2.3)。实际='${manifest.version}'")
        }

        // 4. minAppVersion格式 + 兼容性
        if (!SEMVER_REGEX.matches(manifest.minAppVersion)) {
            throw InvalidFieldFormatException("minAppVersion",
                "必须为语义化版本格式X.Y.Z。实际='${manifest.minAppVersion}'")
        }
        if (!isVersionCompatible(currentAppVersion, manifest.minAppVersion)) {
            throw ModVersionIncompatibleException(
                manifest.id, manifest.minAppVersion, currentAppVersion
            )
        }

        // 5. category不为null（枚举默认不会null，有值就合法）
        // Kotlin枚举默认非空，无需额外校验，仅校验描述长度
        if (manifest.description.length < 5) {
            throw InvalidFieldFormatException("description", "描述长度至少5个字符")
        }

        // 6. SKILL/AUTOMATION必须有entryPoint
        if ((manifest.category == ModCategoryTest.SKILL
                    || manifest.category == ModCategoryTest.AUTOMATION)
            && manifest.entryPoint.isNullOrBlank()
        ) {
            throw MissingRequiredFieldException(
                "entryPoint（SKILL和AUTOMATION类型Mod必填）")
        }
    }

    /** 检查字段非空非空白 */
    private fun checkRequired(fieldName: String, value: String) {
        if (value.isBlank()) {
            throw MissingRequiredFieldException(fieldName)
        }
    }

    /**
     * 版本比较：current >= minRequired 才兼容
     * 语义化版本按段比较：先比主版本，相同比次版本，再相同比修订号
     */
    fun isVersionCompatible(current: String, minRequired: String): Boolean {
        val cur = parseVersion(current)
        val min = parseVersion(minRequired)
        if (cur[0] != min[0]) return cur[0] > min[0]
        if (cur[1] != min[1]) return cur[1] > min[1]
        return cur[2] >= min[2]
    }

    private fun parseVersion(v: String): IntArray {
        val parts = v.split(".").map { it.toIntOrNull() ?: 0 }
        return intArrayOf(
            parts.getOrNull(0) ?: 0,
            parts.getOrNull(1) ?: 0,
            parts.getOrNull(2) ?: 0
        )
    }
}

// ================ 测试类 ================

class ModManifestValidationTest {

    private lateinit var validator: ModManifestValidator
    // 当前App版本：1.0.0
    private val currentAppVer = "1.0.0"

    @Before
    fun setUp() {
        validator = ModManifestValidator(currentAppVersion = currentAppVer)
    }

    // ========== 1. 合法 manifest 通过校验 ==========

    @Test
    fun `测试合法manifest - PERSONA类型带全字段通过校验`() {
        val valid = ModManifest(
            id = "com.example.my_persona",
            name = "我的温柔人格",
            version = "1.0.0",
            versionCode = 10000,
            author = "测试作者",
            description = "一个温柔细腻的人格包，包含大量记忆和示例对话，共约50条设定",
            category = ModCategoryTest.PERSONA,
            minAppVersion = "1.0.0",
            tags = listOf("温柔", "治愈", "女生"),
            icon = "icon.png",
            readme = "README.md"
        )
        // 不抛异常 = 通过
        validator.validate(valid)
    }

    @Test
    fun `测试合法manifest - SKILL类型必须有entryPoint`() {
        val skill = ModManifest(
            id = "com.example.weather_skill",
            name = "天气预报技能",
            version = "2.3.4",
            versionCode = 20304,
            author = "weather_dev",
            description = "调用第三方天气API实现每日天气预报技能，支持多城市查询",
            category = ModCategoryTest.SKILL,
            minAppVersion = "1.0.0",
            entryPoint = "scripts/weather.js"
        )
        validator.validate(skill)
    }

    @Test
    fun `测试合法manifest - 带依赖的AUTOMATION类型`() {
        val auto = ModManifest(
            id = "com.example.workflow_goodmorning",
            name = "早安工作流",
            version = "1.2.5",
            versionCode = 10205,
            author = "guru",
            description = "每天7点自动播报天气+日程+打开音乐，完整的清晨自动化工作流脚本",
            category = ModCategoryTest.AUTOMATION,
            minAppVersion = "1.0.0",
            dependencies = listOf("com.example.weather_skill"),
            entryPoint = "scripts/goodmorning.js"
        )
        validator.validate(auto)
    }

    @Test
    fun `测试合法manifest - minAppVersion更低版本兼容`() {
        // 当前灵枢是1.0.0，Mod要求0.9.0，应该通过
        val manifest = ModManifest(
            id = "com.example.old_mod",
            name = "兼容性测试包",
            version = "1.0.0",
            versionCode = 1,
            author = "a",
            description = "仅用于兼容性测试的演示描述",
            category = ModCategoryTest.THEME,
            minAppVersion = "0.9.0"
        )
        validator.validate(manifest)
    }

    @Test
    fun `测试版本比较 - 1点0点0 >= 1点0点0应兼容`() {
        assertTrue("1.0.0 >= 1.0.0 应兼容",
            validator.isVersionCompatible("1.0.0", "1.0.0"))
    }

    @Test
    fun `测试版本比较 - 1点5点0 >= 1点0点0应兼容`() {
        assertTrue("1.5.0 >= 1.0.0 应兼容",
            validator.isVersionCompatible("1.5.0", "1.0.0"))
    }

    @Test
    fun `测试版本比较 - 2点0点0 >= 1点9点9应兼容`() {
        assertTrue("2.0.0 >= 1.9.9 应兼容",
            validator.isVersionCompatible("2.0.0", "1.9.9"))
    }

    @Test
    fun `测试版本比较 - 0点9点9 < 1点0点0不兼容`() {
        assertFalse("0.9.9 < 1.0.0 不兼容",
            validator.isVersionCompatible("0.9.9", "1.0.0"))
    }

    // ========== 2. 缺少必填字段 → 对应异常 ==========

    @Test(expected = MissingRequiredFieldException::class)
    fun `测试缺少id字段 - 抛MissingRequiredFieldException`() {
        val bad = validManifestCopy(id = "")
        validator.validate(bad)
    }

    @Test(expected = MissingRequiredFieldException::class)
    fun `测试缺少name字段 - 抛MissingRequiredFieldException`() {
        val bad = validManifestCopy(name = "")
        validator.validate(bad)
    }

    @Test(expected = MissingRequiredFieldException::class)
    fun `测试缺少version字段 - 抛MissingRequiredFieldException`() {
        val bad = validManifestCopy(version = "   ")
        validator.validate(bad)
    }

    @Test(expected = MissingRequiredFieldException::class)
    fun `测试versionCode为0 - 抛MissingRequiredFieldException`() {
        val bad = validManifestCopy(versionCode = 0)
        validator.validate(bad)
    }

    @Test(expected = MissingRequiredFieldException::class)
    fun `测试versionCode为负 - 抛MissingRequiredFieldException`() {
        val bad = validManifestCopy(versionCode = -1)
        validator.validate(bad)
    }

    @Test(expected = MissingRequiredFieldException::class)
    fun `测试缺少author - 抛MissingRequiredFieldException`() {
        val bad = validManifestCopy(author = "")
        validator.validate(bad)
    }

    @Test(expected = MissingRequiredFieldException::class)
    fun `测试缺少description - 抛MissingRequiredFieldException`() {
        val bad = validManifestCopy(description = "   ")
        validator.validate(bad)
    }

    @Test(expected = MissingRequiredFieldException::class)
    fun `测试缺少minAppVersion - 抛MissingRequiredFieldException`() {
        val bad = validManifestCopy(minAppVersion = "")
        validator.validate(bad)
    }

    @Test(expected = MissingRequiredFieldException::class)
    fun `测试SKILL类型缺少entryPoint - 抛MissingRequiredFieldException`() {
        val bad = validManifestCopy(
            category = ModCategoryTest.SKILL,
            entryPoint = null
        )
        validator.validate(bad)
    }

    @Test(expected = MissingRequiredFieldException::class)
    fun `测试AUTOMATION类型entryPoint为空 - 抛MissingRequiredFieldException`() {
        val bad = validManifestCopy(
            category = ModCategoryTest.AUTOMATION,
            entryPoint = "   "
        )
        validator.validate(bad)
    }

    // ========== 3. 字段格式非法 ==========

    @Test(expected = InvalidFieldFormatException::class)
    fun `测试id包含大写字母 - 抛InvalidFieldFormatException`() {
        val bad = validManifestCopy(id = "Com.Example.BadId")
        validator.validate(bad)
    }

    @Test(expected = InvalidFieldFormatException::class)
    fun `测试id包含特殊字符 - 抛InvalidFieldFormatException`() {
        val bad = validManifestCopy(id = "com.bad@id!")
        validator.validate(bad)
    }

    @Test(expected = InvalidFieldFormatException::class)
    fun `测试id只有1字符 - 抛InvalidFieldFormatException`() {
        val bad = validManifestCopy(id = "a")
        validator.validate(bad)
    }

    @Test(expected = InvalidFieldFormatException::class)
    fun `测试version只有一段 - 抛InvalidFieldFormatException`() {
        val bad = validManifestCopy(version = "1")
        validator.validate(bad)
    }

    @Test(expected = InvalidFieldFormatException::class)
    fun `测试version有字母 - 抛InvalidFieldFormatException`() {
        val bad = validManifestCopy(version = "1.0.beta")
        validator.validate(bad)
    }

    @Test(expected = InvalidFieldFormatException::class)
    fun `测试description过短 - 抛InvalidFieldFormatException`() {
        val bad = validManifestCopy(description = "短")  // 长度1<5
        validator.validate(bad)
    }

    // ========== 4. 版本不兼容 ==========

    @Test(expected = ModVersionIncompatibleException::class)
    fun `测试minAppVersion高于当前灵枢 - 抛ModVersionIncompatibleException`() {
        // Mod要求灵枢>=2.0.0，但当前是1.0.0 → 不兼容
        val bad = validManifestCopy(minAppVersion = "2.0.0")
        validator.validate(bad)
    }

    @Test(expected = ModVersionIncompatibleException::class)
    fun `测试minAppVersion次版本更高 - 抛ModVersionIncompatibleException`() {
        // 当前1.0.0，要求1.1.0 → 不兼容
        val bad = validManifestCopy(minAppVersion = "1.1.0")
        validator.validate(bad)
    }

    @Test
    fun `测试版本不兼容异常消息字段 - 包含三要素`() {
        val id = "com.example.requires_new"
        val minV = "2.0.0"
        val curV = currentAppVer
        val bad = validManifestCopy(id = id, minAppVersion = minV)
        try {
            validator.validate(bad)
            fail("应该抛出异常")
        } catch (e: ModVersionIncompatibleException) {
            val msg = e.message ?: ""
            assertTrue("异常消息应包含Mod ID", msg.contains(id))
            assertTrue("异常消息应包含要求版本minRequired", msg.contains(minV))
            assertTrue("异常消息应包含当前版本current", msg.contains(curV))
        }
    }

    // ========== 5. 异常类型层次正确 ==========

    @Test
    fun `测试所有校验异常都继承ModManifestValidationException`() {
        val exc1 = MissingRequiredFieldException("x")
        val exc2 = InvalidFieldFormatException("x", "y")
        val exc3 = ModVersionIncompatibleException("id", "1.0", "0.9")
        val exc4 = ModIdConflictException("id")
        assertTrue("MissingRequiredFieldException 是子类",
            exc1 is ModManifestValidationException)
        assertTrue("InvalidFieldFormatException 是子类",
            exc2 is ModManifestValidationException)
        assertTrue("ModVersionIncompatibleException 是子类",
            exc3 is ModManifestValidationException)
        assertTrue("ModIdConflictException 是子类",
            exc4 is ModManifestValidationException)
    }

    @Test
    fun `测试可以统一捕获基类异常`() {
        val bad = validManifestCopy(id = "")  // 缺少必填
        var caught: ModManifestValidationException? = null
        try {
            validator.validate(bad)
        } catch (e: ModManifestValidationException) {
            caught = e
        }
        assertNotNull("统一捕获基类应该能捕获到子类异常", caught)
    }

    // ============================================================
    // 辅助方法：快速复制合法manifest并按需修改个别字段
    // ============================================================

    /**
     * 创建一个合法的PERSONA类型manifest，允许通过命名参数覆盖个别字段用于构造坏例
     */
    private fun validManifestCopy(
        id: String = "com.example.test_persona",
        name: String = "测试人格Mod",
        version: String = "1.0.0",
        versionCode: Int = 10000,
        author: String = "TestAuthor",
        description: String = "这是一个用于单元测试的人格Mod包，包含完整的manifest字段。",
        category: ModCategoryTest = ModCategoryTest.PERSONA,
        minAppVersion: String = "1.0.0",
        maxAppVersion: String? = null,
        dependencies: List<String> = emptyList(),
        entryPoint: String? = null,
        tags: List<String> = emptyList()
    ): ModManifest = ModManifest(
        id = id,
        name = name,
        version = version,
        versionCode = versionCode,
        author = author,
        description = description,
        category = category,
        minAppVersion = minAppVersion,
        maxAppVersion = maxAppVersion,
        dependencies = dependencies,
        entryPoint = entryPoint,
        tags = tags
    )
}
