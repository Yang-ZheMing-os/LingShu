package com.lingshu.agent.feature.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

// ================ 向量条目 + 内存向量存储实现 ================

/**
 * 向量条目: id + 原始文本内容 + embedding向量(float数组)
 * @param id 文档/切片唯一ID
 * @param content 原始文本内容
 * @param embedding 向量表示，非空且长度>0
 */
data class VectorEntry(
    val id: String,
    val content: String,
    val embedding: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VectorEntry) return false
        if (id != other.id) return false
        if (!embedding.contentEquals(other.embedding)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}

/**
 * 搜索结果: 条目 + 相似度分值（0~1之间，越大越相似）
 */
data class SearchResult(
    val entry: VectorEntry,
    val similarity: Float
)

/**
 * 内存向量存储（InMemoryVectorStore）
 * 功能：
 * 1. 支持add/remove/clear条目（用于单元测试隔离环境）
 * 2. 基于余弦相似度进行K近邻搜索
 * 3. 所有计算在本地JVM完成，无外部依赖，便于单测
 *
 * 余弦相似度公式：
 *   cos(a, b) = dot(a, b) / (||a|| * ||b||)
 * 其中 dot为点积，||x||为L2范数
 */
class InMemoryVectorStore {

    private val entries: MutableMap<String, VectorEntry> = LinkedHashMap()

    /** 条目总数 */
    val size: Int get() = entries.size

    /** 是否为空 */
    fun isEmpty(): Boolean = entries.isEmpty()

    /** 添加或覆盖一个条目（按id） */
    fun add(entry: VectorEntry) {
        require(entry.embedding.isNotEmpty()) { "向量不能为空" }
        entries[entry.id] = entry
    }

    /** 批量添加 */
    fun addAll(list: Iterable<VectorEntry>) {
        list.forEach { add(it) }
    }

    /** 是否存在该id */
    fun contains(id: String): Boolean = entries.containsKey(id)

    /** 按id获取条目（不存在返回null） */
    fun get(id: String): VectorEntry? = entries[id]

    /** 按id移除，返回被移除条目（不存在返回null） */
    fun remove(id: String): VectorEntry? = entries.remove(id)

    /** 清空所有条目 */
    fun clear() {
        entries.clear()
    }

    /** 返回所有条目（只读快照） */
    fun allEntries(): List<VectorEntry> = entries.values.toList()

    // ============= 相似度计算 =============

    /**
     * 计算两个向量的余弦相似度，范围[-1, 1]
     * 单位向量点积即等于余弦相似度
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.isNotEmpty() && b.isNotEmpty()) { "向量不能为空" }
        require(a.size == b.size) { "向量维度必须一致，a=${a.size} b=${b.size}" }

        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val normProd = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        if (normProd == 0f) return 0f   // 零向量相似度为0
        return (dot / normProd).coerceIn(-1f, 1f)
    }

    /**
     * 搜索与query向量最相似的topK个条目
     * @param queryEmbedding 查询向量
     * @param topK 返回结果数（>0），超过总条目数返回全部
     * @param threshold 相似度门槛（默认0，即不过滤）
     * @return 按相似度从高到低排序的列表
     */
    fun search(
        queryEmbedding: FloatArray,
        topK: Int = 5,
        threshold: Float = 0f
    ): List<SearchResult> {
        require(topK > 0) { "topK必须>0" }
        require(queryEmbedding.isNotEmpty()) { "查询向量不能为空" }

        val results = ArrayList<SearchResult>(entries.size)
        for (entry in entries.values) {
            // 维度不一致跳过
            if (entry.embedding.size != queryEmbedding.size) continue
            val sim = cosineSimilarity(entry.embedding, queryEmbedding)
            if (sim >= threshold) {
                results.add(SearchResult(entry, sim))
            }
        }
        // 降序排序（相似度高→低）
        results.sortByDescending { it.similarity }
        return if (results.size <= topK) results else results.subList(0, topK)
    }
}

// ================ 测试类 ================

class VectorStoreTest {

    private lateinit var store: InMemoryVectorStore

    @Before
    fun setUp() {
        store = InMemoryVectorStore()
    }

    // ========== 1. 基础CRUD ==========

    @Test
    fun `测试初始化 - 新store应为空`() {
        assertTrue("新创建应为空", store.isEmpty())
        assertEquals("size应为0", 0, store.size)
    }

    @Test
    fun `测试add和get - 按id取回内容一致`() {
        val e = VectorEntry("id1", "你好世界", floatArrayOf(1f, 0f))
        store.add(e)
        assertEquals("add后size=1", 1, store.size)
        assertTrue("contains应返回true", store.contains("id1"))
        val got = store.get("id1")
        assertNotNull("get非空", got)
        assertEquals("内容一致", "你好世界", got!!.content)
        assertEquals("id一致", "id1", got.id)
    }

    @Test
    fun `测试get不存在的id - 返回null`() {
        store.add(VectorEntry("a", "x", floatArrayOf(1f)))
        assertEquals("不存在返回null", null, store.get("b"))
    }

    @Test
    fun `测试add覆盖同id - 后写入覆盖前写入`() {
        store.add(VectorEntry("k", "v1", floatArrayOf(1f, 2f)))
        store.add(VectorEntry("k", "v2", floatArrayOf(3f, 4f)))
        assertEquals("同id覆盖，size仍为1", 1, store.size)
        assertEquals("应返回新值", "v2", store.get("k")!!.content)
    }

    @Test
    fun `测试remove和clear`() {
        store.addAll(listOf(
            VectorEntry("a", "1", floatArrayOf(1f)),
            VectorEntry("b", "2", floatArrayOf(1f)),
            VectorEntry("c", "3", floatArrayOf(1f))
        ))
        assertEquals("addAll后size=3", 3, store.size)
        val removed = store.remove("b")
        assertNotNull("remove返回被移除条目", removed)
        assertEquals("被移除id=b", "b", removed!!.id)
        assertFalse("contains b=false", store.contains("b"))
        assertEquals("移除后size=2", 2, store.size)

        store.clear()
        assertTrue("clear后isEmpty", store.isEmpty())
        assertEquals("clear后size=0", 0, store.size)
    }

    @Test
    fun `测试add空向量 - 抛IllegalArgumentException`() {
        try {
            store.add(VectorEntry("bad", "empty vec", floatArrayOf()))
            fail("应抛异常")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ========== 2. 余弦相似度计算 ==========

    @Test
    fun `测试余弦相似度 - 相同向量相似度约等于1`() {
        val v = floatArrayOf(3f, 4f)   // 长度5
        val sim = store.cosineSimilarity(v, v)
        assertEquals("相同向量相似度≈1", 1f, sim, 1e-5f)
    }

    @Test
    fun `测试余弦相似度 - 单位正交向量相似度等于0`() {
        val x = floatArrayOf(1f, 0f, 0f)
        val y = floatArrayOf(0f, 1f, 0f)
        val sim = store.cosineSimilarity(x, y)
        assertEquals("正交向量相似度≈0", 0f, sim, 1e-5f)
    }

    @Test
    fun `测试余弦相似度 - 相反向量等于-1`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(-1f, 0f)
        val sim = store.cosineSimilarity(a, b)
        assertEquals("相反向量=-1", -1f, sim, 1e-5f)
    }

    @Test
    fun `测试余弦相似度 - 非单位正交也为0`() {
        // 依然正交，缩放不影响余弦相似度
        val a = floatArrayOf(3f, 0f)
        val b = floatArrayOf(0f, 999f)
        val sim = store.cosineSimilarity(a, b)
        assertEquals("0,90度夹角余弦=0", 0f, sim, 1e-5f)
    }

    @Test
    fun `测试余弦相似度 - 同向不同长度=1`() {
        val a = floatArrayOf(1f, 1f)
        val b = floatArrayOf(100f, 100f)
        val sim = store.cosineSimilarity(a, b)
        assertEquals("共线同向向量相似度=1", 1f, sim, 1e-5f)
    }

    @Test
    fun `测试余弦相似度 - 已知夹角45度约等于0点7071`() {
        // (1,1) 和 (1,0) 夹角45°，cos(45°)=√2/2≈0.7071
        val a = floatArrayOf(1f, 1f)
        val b = floatArrayOf(1f, 0f)
        val sim = store.cosineSimilarity(a, b)
        assertEquals("45度≈0.7071", 0.7071f, sim, 0.001f)
    }

    @Test
    fun `测试余弦相似度 - 零向量结果为0不崩溃`() {
        val zero = floatArrayOf(0f, 0f, 0f)
        val normal = floatArrayOf(1f, 2f, 3f)
        val sim1 = store.cosineSimilarity(zero, normal)
        val sim2 = store.cosineSimilarity(normal, zero)
        val sim3 = store.cosineSimilarity(zero, zero)
        assertEquals("零向量参与→0", 0f, sim1, 1e-6f)
        assertEquals("零向量参与→0", 0f, sim2, 1e-6f)
        assertEquals("两零向量→0", 0f, sim3, 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `测试余弦相似度 - 维度不一致抛异常`() {
        store.cosineSimilarity(floatArrayOf(1f, 2f), floatArrayOf(1f, 2f, 3f))
    }

    // ========== 3. search搜索返回topK正确 ==========

    /** 构建一个2维空间的测试向量库 */
    private fun build2DTestStore() {
        store.addAll(listOf(
            VectorEntry("d1", "正x方向",     floatArrayOf(1f, 0f)),
            VectorEntry("d2", "正y方向",     floatArrayOf(0f, 1f)),
            VectorEntry("d3", "负x方向",     floatArrayOf(-1f, 0f)),
            VectorEntry("d4", "负y方向",     floatArrayOf(0f, -1f)),
            VectorEntry("d5", "东北45度",    floatArrayOf(1f, 1f)),
            VectorEntry("d6", "西北45度",    floatArrayOf(-1f, 1f))
        ))
    }

    @Test
    fun `测试search - 正x方向查询应返回d1第1，d3最后`() {
        build2DTestStore()
        val query = floatArrayOf(1f, 0f)    // 正x方向
        val top6 = store.search(query, topK = 6)
        assertEquals("topK=6返回6条", 6, top6.size)
        assertEquals("第1个应该是正x d1", "d1", top6[0].entry.id)
        assertEquals("正x与自身相似度=1", 1f, top6[0].similarity, 1e-5f)
        assertEquals("最后1个应该是负x d3", "d3", top6.last().entry.id)
        assertEquals("负x相似度=-1", -1f, top6.last().similarity, 1e-5f)
    }

    @Test
    fun `测试search - topK=1仅返回最相似`() {
        build2DTestStore()
        val query = floatArrayOf(0f, 1f)
        val top1 = store.search(query, topK = 1)
        assertEquals("topK=1只返回1条", 1, top1.size)
        assertEquals("应为正y d2", "d2", top1[0].entry.id)
    }

    @Test
    fun `测试search - topK超库大小返回全部`() {
        build2DTestStore()
        val r = store.search(floatArrayOf(1f, 1f), topK = 999)
        assertEquals("topK很大返回全部6条", 6, r.size)
    }

    @Test
    fun `测试search - 相似度门槛过滤`() {
        build2DTestStore()
        // threshold=0.5: 只保留相似度>=0.5
        // 以正x方向为查询:
        //   d1(1,0)=1   d5(1,1)=0.707   d2(0,1)=0   d6(-1,1)=-0.707   d4(0,-1)=0   d3(-1,0)=-1
        // >=0.5的应只有d1(1), d5(0.707)
        val r = store.search(floatArrayOf(1f, 0f), topK = 6, threshold = 0.5f)
        assertEquals("门槛0.5过滤后剩2条", 2, r.size)
        assertTrue("包含d1", r.any { it.entry.id == "d1" })
        assertTrue("包含d5", r.any { it.entry.id == "d5" })
        // 且相似度都>=0.5
        assertTrue("所有结果sim>=0.5", r.all { it.similarity >= 0.5f })
    }

    @Test
    fun `测试search - 按相似度严格降序排列`() {
        build2DTestStore()
        val r = store.search(floatArrayOf(1f, 0.5f), topK = 6)
        for (i in 1 until r.size) {
            assertTrue("第${i-1}条相似度 >= 第${i}条", r[i-1].similarity >= r[i].similarity)
        }
    }

    @Test
    fun `测试search - 空库返回空列表`() {
        val r = store.search(floatArrayOf(1f, 0f), topK = 5)
        assertTrue("空库search返回emptyList", r.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `测试search - topK为0抛异常`() {
        store.search(floatArrayOf(1f), topK = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `测试search - topK为负抛异常`() {
        store.search(floatArrayOf(1f), topK = -1)
    }

    // ========== 4. 高维度实际场景测试 ==========

    @Test
    fun `测试高维余弦相似度 - 相同随机向量约等于1`() {
        val rnd = java.util.Random(42)   // 固定种子可复现
        val dim = 384  // 典型嵌入维度
        val vec = FloatArray(dim) { rnd.nextGaussian().toFloat() }
        val sim = store.cosineSimilarity(vec, vec.copyOf())
        assertEquals("同向量相似度=1", 1f, sim, 1e-5f)
    }

    @Test
    fun `测试search高维 - topK=3正确`() {
        val dim = 128
        val rnd = java.util.Random(7)
        // 准备10条随机向量
        val list = (1..10).map {
            VectorEntry(
                id = "v$it",
                content = "doc_$it",
                embedding = FloatArray(dim) { rnd.nextFloat() - 0.5f }
            )
        }
        store.addAll(list)

        val query = list[2].embedding  // 直接用v3的embedding作为查询
        val top3 = store.search(query, topK = 3)
        assertEquals("top3返回3条", 3, top3.size)
        assertEquals("最佳应为v3自身", "v3", top3[0].entry.id)
        assertEquals("v3和自身相似度≈1", 1f, top3[0].similarity, 1e-4f)
        // 严格递减
        assertTrue(top3[0].similarity >= top3[1].similarity)
        assertTrue(top3[1].similarity >= top3[2].similarity)
    }

    // ========== 5. 边界：维度不一致被search静默跳过 ==========

    @Test
    fun `测试search - 维度不一致条目被跳过`() {
        store.add(VectorEntry("a", "2d", floatArrayOf(1f, 0f)))
        store.add(VectorEntry("b", "3d", floatArrayOf(0f, 1f, 0f)))  // 维度不同
        // 用3维查询
        val r3 = store.search(floatArrayOf(0f, 1f, 0f), topK = 5)
        assertEquals("3维查询只匹配b", 1, r3.size)
        assertEquals("匹配的是b", "b", r3[0].entry.id)
        // 用2维查询
        val r2 = store.search(floatArrayOf(1f, 0f), topK = 5)
        assertEquals("2维查询只匹配a", 1, r2.size)
        assertEquals("匹配的是a", "a", r2[0].entry.id)
    }
}
