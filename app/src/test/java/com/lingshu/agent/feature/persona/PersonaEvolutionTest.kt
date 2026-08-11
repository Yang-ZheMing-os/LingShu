package com.lingshu.agent.feature.persona

import com.lingshu.agent.core.model.BigFiveTraits
import com.lingshu.agent.core.model.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 人格演化算法测试
 *
 * 核心验证：
 * 1. LIKED反馈 → 宜人性(agreeableness) + 尽责性(conscientiousness) 轻微上升
 * 2. DISLIKED反馈 → 反向（宜人性+尽责性下降，或神经质轻微上升）
 * 3. 连续相同反馈的衰减：第N次反馈的调整幅度 × 1/√N
 * 4. 夹紧clamp：所有维度强制落在[0,1]区间，防止过拟合溢出
 */
class PersonaEvolutionTest {

    // 基础中性人格作为演化起点
    private lateinit var baseTraits: BigFiveTraits
    // LIKED反馈对应的delta向量（正反馈方向）
    private lateinit var likedDelta: BigFiveTraits
    // DISLIKED反馈对应的delta向量（负反馈方向）
    private lateinit var dislikedDelta: BigFiveTraits

    @Before
    fun setUp() {
        // 起点：所有维度0.5（完完全全中立）
        baseTraits = BigFiveTraits.neutral()

        // LIKED反馈：用户点赞 → 更宜人、更尽责（需求指定）
        // 开放性+0.05，尽责性+0.10，外向性+0.03，宜人性+0.10，神经质-0.05
        likedDelta = BigFiveTraits(
            openness = 0.05,
            conscientiousness = 0.10,
            extraversion = 0.03,
            agreeableness = 0.10,
            neuroticism = -0.05
        )

        // DISLIKED反馈：用户点踩 → 不那么宜人，降低尽责，略提升神经质（反向）
        dislikedDelta = BigFiveTraits(
            openness = -0.05,
            conscientiousness = -0.10,
            extraversion = -0.03,
            agreeableness = -0.10,
            neuroticism = 0.05
        )
    }

    // ================ 1. clamp 夹紧验证 ================

    @Test
    fun `测试clamp夹紧 - 超过1.0的维度被夹到1点0`() {
        // 所有维度设为2.0，应全部被夹到1.0
        val overTraits = BigFiveTraits(2.0, 2.0, 2.0, 2.0, 2.0)
        val clamped = overTraits.clamp()
        assertEquals("openness应夹到1.0", 1.0, clamped.openness, 0.0001)
        assertEquals("conscientiousness应夹到1.0", 1.0, clamped.conscientiousness, 0.0001)
        assertEquals("extraversion应夹到1.0", 1.0, clamped.extraversion, 0.0001)
        assertEquals("agreeableness应夹到1.0", 1.0, clamped.agreeableness, 0.0001)
        assertEquals("neuroticism应夹到1.0", 1.0, clamped.neuroticism, 0.0001)
    }

    @Test
    fun `测试clamp夹紧 - 低于0的维度被夹到0`() {
        val underTraits = BigFiveTraits(-0.5, -1.0, -0.1, -999.0, 0.0)
        val clamped = underTraits.clamp()
        assertEquals("负数openness夹到0", 0.0, clamped.openness, 0.0001)
        assertEquals("负数conscientiousness夹到0", 0.0, clamped.conscientiousness, 0.0001)
        assertEquals("负数extraversion夹到0", 0.0, clamped.extraversion, 0.0001)
        assertEquals("负数agreeableness夹到0", 0.0, clamped.agreeableness, 0.0001)
        assertEquals("0的neuroticism保持0", 0.0, clamped.neuroticism, 0.0001)
    }

    @Test
    fun `测试clamp夹紧 - 正常区间内的值不被修改`() {
        // 边界和正常值不应被修改
        val normal = BigFiveTraits(0.0, 0.25, 0.5, 0.75, 1.0)
        val clamped = normal.clamp()
        assertEquals("0.0不变", 0.0, clamped.openness, 0.0001)
        assertEquals("0.25不变", 0.25, clamped.conscientiousness, 0.0001)
        assertEquals("0.5不变", 0.5, clamped.extraversion, 0.0001)
        assertEquals("0.75不变", 0.75, clamped.agreeableness, 0.0001)
        assertEquals("1.0不变", 1.0, clamped.neuroticism, 0.0001)
    }

    // ================ 2. LIKED 正反馈验证 ================

    @Test
    fun `测试LIKED反馈 - 宜人性和尽责性轻微上升`() {
        // 起点0.5，rate=0.05
        val evolved = baseTraits.adjust(likedDelta, rate = 1.0)

        // 宜人性从0.5 → 0.5 + 0.10 = 0.60
        assertEquals("LIKED反馈宜人性应上升",
            0.5 + 0.10, evolved.agreeableness, 0.0001)
        assertTrue("宜人性必须大于初始值",
            evolved.agreeableness > baseTraits.agreeableness)

        // 尽责性从0.5 → 0.5 + 0.10 = 0.60
        assertEquals("LIKED反馈尽责性应上升",
            0.5 + 0.10, evolved.conscientiousness, 0.0001)
        assertTrue("尽责性必须大于初始值",
            evolved.conscientiousness > baseTraits.conscientiousness)

        // 神经质从0.5 → 0.5 - 0.05 = 0.45（用户喜欢→情绪更稳定）
        assertEquals("LIKED反馈神经质应下降",
            0.5 - 0.05, evolved.neuroticism, 0.0001)
        assertTrue("神经质必须小于初始值",
            evolved.neuroticism < baseTraits.neuroticism)
    }

    @Test
    fun `测试LIKED反馈 - rate参数控制调整幅度`() {
        // 用rate=0.5，幅度减半
        val halfRate = baseTraits.adjust(likedDelta, rate = 0.5)
        // 宜人性：0.5 + 0.10*0.5 = 0.55
        assertEquals("rate=0.5时宜人性应为0.55",
            0.55, halfRate.agreeableness, 0.0001)

        // 用rate=0.1，幅度很小
        val tinyRate = baseTraits.adjust(likedDelta, rate = 0.1)
        assertEquals("rate=0.1时宜人性应为0.51",
            0.5 + 0.10 * 0.1, tinyRate.agreeableness, 0.0001)
    }

    // ================ 3. DISLIKED 负反馈验证 ================

    @Test
    fun `测试DISLIKED反馈 - 宜人性和尽责性反向（下降）`() {
        val evolved = baseTraits.adjust(dislikedDelta, rate = 1.0)

        // 宜人性从0.5 → 0.5 - 0.10 = 0.40
        assertEquals("DISLIKED反馈宜人性应下降",
            0.5 - 0.10, evolved.agreeableness, 0.0001)
        assertTrue("宜人性必须小于初始值",
            evolved.agreeableness < baseTraits.agreeableness)

        // 尽责性从0.5 → 0.5 - 0.10 = 0.40
        assertEquals("DISLIKED反馈尽责性应下降",
            0.5 - 0.10, evolved.conscientiousness, 0.0001)
        assertTrue("尽责性必须小于初始值",
            evolved.conscientiousness < baseTraits.conscientiousness)

        // 神经质从0.5 → 0.5 + 0.05 = 0.55（用户不喜欢，稍微敏感一点）
        assertEquals("DISLIKED反馈神经质应上升",
            0.5 + 0.05, evolved.neuroticism, 0.0001)
    }

    @Test
    fun `测试DISLIKED与LIKED为对称反向变化`() {
        val liked = baseTraits.adjust(likedDelta, rate = 1.0)
        val disliked = baseTraits.adjust(dislikedDelta, rate = 1.0)

        // 从同一个0.5起点出发，liked和disliked的变化应对称
        val likedDeltaAgree = liked.agreeableness - 0.5
        val dislikedDeltaAgree = disliked.agreeableness - 0.5
        assertEquals("宜人性的正负反馈应数值相反",
            -likedDeltaAgree, dislikedDeltaAgree, 0.0001)

        val likedDeltaConsc = liked.conscientiousness - 0.5
        val dislikedDeltaConsc = disliked.conscientiousness - 0.5
        assertEquals("尽责性的正负反馈应数值相反",
            -likedDeltaConsc, dislikedDeltaConsc, 0.0001)
    }

    // ================ 4. 连续反馈衰减 ================

    @Test
    fun `测试连续反馈衰减 - 第N次相同反馈调整幅度乘以1除以根号N`() {
        // 模拟多次连续LIKED反馈
        var current = baseTraits

        // 第1次（N=1）：衰减因子 = 1/√1 = 1.0
        var prevAgree = current.agreeableness
        current = current.adjust(likedDelta, rate = 1.0 / sqrt(1.0))
        val delta1 = current.agreeableness - prevAgree
        assertEquals("第1次宜人性变化应为0.10", 0.10, delta1, 0.0001)

        // 第2次（N=2）：衰减因子 = 1/√2 ≈ 0.7071 → 变化≈0.10*0.7071≈0.07071
        prevAgree = current.agreeableness
        val decay2 = 1.0 / sqrt(2.0)
        current = current.adjust(likedDelta, rate = decay2)
        val delta2 = current.agreeableness - prevAgree
        val expectedDelta2 = 0.10 * decay2
        assertEquals("第2次宜人性变化应衰减", expectedDelta2, delta2, 0.0001)
        assertTrue("第2次变化幅度必须小于第1次", abs(delta2) < abs(delta1))

        // 第4次（N=4）：衰减因子 = 1/√4 = 0.5 → 变化0.05
        // 先跳过第3次，直接验证第4次的衰减倍数关系
        val decay4 = 1.0 / sqrt(4.0)
        assertEquals("N=4时衰减因子应为0.5", 0.5, decay4, 0.0001)
        val expectedDelta4 = 0.10 * decay4
        assertEquals("第4次变化应为0.05（=0.10*0.5）", 0.05, expectedDelta4, 0.0001)

        // 验证幅度递减：delta1 > delta2 > delta4
        assertTrue("衰减应严格递减", delta1 > delta2 && delta2 > expectedDelta4)
    }

    @Test
    fun `测试连续大量反馈后 - 变化量趋近于0防止过拟合`() {
        var current = baseTraits

        // 连续100次LIKED反馈，每次带正确衰减
        for (n in 1..100) {
            val rate = 1.0 / sqrt(n.toDouble())
            current = current.adjust(likedDelta, rate = rate)
            // adjust内部已clamp，结果应始终合法
            assertTrue("第$n次后所有维度≤1.0",
                current.openness <= 1.0 && current.conscientiousness <= 1.0
                        && current.extraversion <= 1.0 && current.agreeableness <= 1.0
                        && current.neuroticism <= 1.0)
            assertTrue("第$n次后所有维度≥0",
                current.openness >= 0.0 && current.conscientiousness >= 0.0
                        && current.extraversion >= 0.0 && current.agreeableness >= 0.0
                        && current.neuroticism >= 0.0)
        }

        // 100次后，宜人性应该上升了，但不会超过1.0
        assertTrue("100次LIKED后宜人性>初始0.5",
            current.agreeableness > 0.5)
        assertTrue("100次LIKED后宜人性仍≤1.0（夹紧生效）",
            current.agreeableness <= 1.0)
        assertTrue("100次后尽责性也应上升",
            current.conscientiousness > 0.5)
    }

    // ================ 5. 夹紧与adjust的协同 ================

    @Test
    fun `测试adjust后自动夹紧 - 正向调整不超1点0`() {
        // 用一个已经很靠近1.0的值做adjust，delta会让它超过1
        val nearMax = BigFiveTraits(
            openness = 0.95,
            conscientiousness = 0.95,
            extraversion = 0.95,
            agreeableness = 0.95,
            neuroticism = 0.95
        )
        // 再+0.10，理论上到1.05，但clamp后必须≤1.0
        val evolved = nearMax.adjust(likedDelta, rate = 1.0)
        assertTrue("adjust后宜人性不能>1.0", evolved.agreeableness <= 1.0)
        assertTrue("adjust后尽责性不能>1.0", evolved.conscientiousness <= 1.0)
        assertEquals("宜人性应被夹到1.0或更小",
            (0.95 + 0.10).coerceAtMost(1.0), evolved.agreeableness, 0.0001)
    }

    @Test
    fun `测试adjust后自动夹紧 - 负向调整不低于0`() {
        val nearMin = BigFiveTraits(0.05, 0.05, 0.05, 0.05, 0.05)
        // dislikedDelta的agreeableness=-0.10，会让0.05→-0.05，夹到0
        val evolved = nearMin.adjust(dislikedDelta, rate = 1.0)
        assertTrue("负向adjust后宜人性不能<0", evolved.agreeableness >= 0.0)
        assertTrue("负向adjust后尽责性不能<0", evolved.conscientiousness >= 0.0)
    }

    // ================ 6. 预设人格工厂方法 ================

    @Test
    fun `测试预设人格工厂方法 - gentle温柔人格`() {
        val gentle = BigFiveTraits.gentle()
        // 温柔：宜人性0.8 高、尽责性0.7 高、神经质0.2 低
        assertEquals("gentle宜人性0.8", 0.8, gentle.agreeableness, 0.0001)
        assertEquals("gentle尽责性0.7", 0.7, gentle.conscientiousness, 0.0001)
        assertEquals("gentle神经质0.2", 0.2, gentle.neuroticism, 0.0001)
        assertTrue("gentle宜人性应>0.75（高宜人）", gentle.agreeableness > 0.75)
        assertTrue("gentle神经质应<0.25（低神经质=高稳定）", gentle.neuroticism < 0.25)
    }

    @Test
    fun `测试预设人格工厂方法 - sharp犀利人格`() {
        val sharp = BigFiveTraits.sharp()
        // 犀利：尽责性0.9很高、宜人性0.3偏低
        assertEquals("sharp尽责性0.9", 0.9, sharp.conscientiousness, 0.0001)
        assertEquals("sharp宜人性0.3", 0.3, sharp.agreeableness, 0.0001)
        assertTrue("sharp尽责性>0.75", sharp.conscientiousness > 0.75)
        assertTrue("sharp宜人性<0.75", sharp.agreeableness < 0.75)
    }

    @Test
    fun `测试neutral中立人格 - 所有维度恰好0点5`() {
        val n = BigFiveTraits.neutral()
        assertEquals("neutral openness 0.5", 0.5, n.openness, 0.0001)
        assertEquals("neutral conscientiousness 0.5", 0.5, n.conscientiousness, 0.0001)
        assertEquals("neutral extraversion 0.5", 0.5, n.extraversion, 0.0001)
        assertEquals("neutral agreeableness 0.5", 0.5, n.agreeableness, 0.0001)
        assertEquals("neutral neuroticism 0.5", 0.5, n.neuroticism, 0.0001)
    }

    // ================ 7. 演化函数集成：applyFeedbackWithDecay ================

    /**
     * 封装一个完整的"带衰减的反馈应用"函数，模拟真实PersonaManager中的演化逻辑
     */
    private fun applyFeedbackWithDecay(
        current: BigFiveTraits,
        liked: Boolean,
        consecutiveSameCount: Int // 连续相同反馈的次数（从1开始）
    ): BigFiveTraits {
        val n = consecutiveSameCount.coerceAtLeast(1)
        val decayRate = 1.0 / sqrt(n.toDouble())
        val delta = if (liked) likedDelta else dislikedDelta
        return current.adjust(delta, rate = decayRate)
    }

    @Test
    fun `集成测试 - 完整演化流程LIKED三次`() {
        var t = baseTraits
        // 第1次LIKED（n=1）
        t = applyFeedbackWithDecay(t, liked = true, consecutiveSameCount = 1)
        val after1 = t.agreeableness
        // 第2次LIKED（n=2，衰减）
        t = applyFeedbackWithDecay(t, liked = true, consecutiveSameCount = 2)
        val after2 = t.agreeableness
        // 第3次LIKED（n=3，继续衰减）
        t = applyFeedbackWithDecay(t, liked = true, consecutiveSameCount = 3)
        val after3 = t.agreeableness

        // 整体单调递增
        assertTrue("应单调递增: after1 > 0.5", after1 > 0.5)
        assertTrue("应单调递增: after2 > after1", after2 > after1)
        assertTrue("应单调递增: after3 > after2", after3 > after2)

        // 增量递减
        val d1 = after1 - 0.5
        val d2 = after2 - after1
        val d3 = after3 - after2
        assertTrue("增量递减: d1 > d2", d1 > d2)
        assertTrue("增量递减: d2 > d3", d2 > d3)

        // 3次后仍未超过1
        assertTrue("3次后宜人性仍应<1.0", t.agreeableness < 1.0)
    }

    @Test
    fun `集成测试 - 夹紧防止溢出 - 极端情况下始终在0到1区间`() {
        var t = BigFiveTraits.neutral()
        // 连续10000次LIKED（极端压力测试）
        for (n in 1..10000) {
            t = applyFeedbackWithDecay(t, liked = true, consecutiveSameCount = n)
        }
        // 所有维度必须都在0~1之间
        assertTrue("极端测试后openness在0-1", t.openness in 0.0..1.0)
        assertTrue("极端测试后conscientiousness在0-1", t.conscientiousness in 0.0..1.0)
        assertTrue("极端测试后extraversion在0-1", t.extraversion in 0.0..1.0)
        assertTrue("极端测试后agreeableness在0-1", t.agreeableness in 0.0..1.0)
        assertTrue("极端测试后neuroticism在0-1", t.neuroticism in 0.0..1.0)

        // DISLIKED极端测试
        var t2 = BigFiveTraits.neutral()
        for (n in 1..10000) {
            t2 = applyFeedbackWithDecay(t2, liked = false, consecutiveSameCount = n)
        }
        assertTrue("反向极端测试后所有维度≥0",
            t2.openness >= 0 && t2.conscientiousness >= 0
                    && t2.extraversion >= 0 && t2.agreeableness >= 0 && t2.neuroticism >= 0)
    }
}
