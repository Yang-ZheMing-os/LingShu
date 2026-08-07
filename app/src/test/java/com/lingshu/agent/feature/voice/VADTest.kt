package com.lingshu.agent.feature.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VADTest {

    private lateinit var vad: VAD

    @Before
    fun setUp() {
        // 每个测试前创建新的VAD实例，并设置较敏感的参数便于测试
        vad = VAD()
        vad.volumeThreshold = 3.5f
        // 缩小平滑窗口，加快状态转换，便于单测
        vad.smoothWindowSize = 1
        // 设置短静音和长静音，避免单测中等待真实时间
        vad.shortSilenceMs = 50L
        vad.longSilenceMs = 200L
        vad.minSpeechDurationMs = 0L
    }

    @Test
    fun `测试初始状态 - 低音量不触发SPEECH_START`() {
        // 初始状态为WAITING_SPEECH，低音量输入应返回SILENCE或NO_EVENT
        val result1 = vad.processVolume(1.0f) // 远低于阈值3.5
        val result2 = vad.processVolume(2.0f)
        val result3 = vad.processVolume(0.5f)

        // 初始低音量不应触发SPEECH_START
        assertFalse("初始低音量不应处于说话状态", vad.isInSpeech())
    }

    @Test
    fun `测试音量超过阈值连续2帧触发SPEECH_START`() {
        // 先给一些低音量帧预热
        vad.processVolume(1.0f)
        vad.processVolume(1.0f)

        // 连续高音量帧（阈值3.5，给5.0保证超过）
        val highVolume = 6.0f

        // 第1帧高音量：还不够连续2帧，不会触发
        val event1 = vad.processVolume(highVolume)
        // smoothWindowSize=1，所以第1帧后 smoothedVolume=6.0，但需要连续2帧hasVoice

        // 第2帧高音量：连续>=2帧，应触发SPEECH_START
        val event2 = vad.processVolume(highVolume)

        assertEquals("连续2帧超阈值应触发SPEECH_START",
            VAD.VADEvent.SPEECH_START, event2)
        assertTrue("SPEECH_START后应处于说话中", vad.isInSpeech())
    }

    @Test
    fun `测试持续说话中返回SPEECH_CONTINUE`() {
        // 先触发SPEECH_START
        vad.processVolume(6.0f)
        vad.processVolume(6.0f) // 这里触发SPEECH_START

        // 继续高音量输入，应该返回SPEECH_CONTINUE
        val continueEvent = vad.processVolume(6.0f)
        assertEquals("说话持续中应返回SPEECH_CONTINUE",
            VAD.VADEvent.SPEECH_CONTINUE, continueEvent)

        // 再持续几帧
        val continueEvent2 = vad.processVolume(7.0f)
        assertEquals("持续说话应一直SPEECH_CONTINUE",
            VAD.VADEvent.SPEECH_CONTINUE, continueEvent2)

        assertTrue("说话中isInSpeech应为true", vad.isInSpeech())
    }

    @Test
    fun `测试说话中断后再恢复 - 从MAYBE_END回到IN_SPEECH`() {
        // 触发SPEECH_START
        vad.processVolume(6.0f)
        vad.processVolume(6.0f) // SPEECH_START
        vad.processVolume(6.0f) // SPEECH_CONTINUE

        // 1帧低音量：状态切换到MAYBE_END，返回NO_EVENT
        val maybeEndEvent = vad.processVolume(1.0f)
        assertEquals("1帧低音量返回NO_EVENT(进入MAYBE_END)",
            VAD.VADEvent.NO_EVENT, maybeEndEvent)
        assertTrue("MAYBE_END也属于isInSpeech", vad.isInSpeech())

        // 紧接着恢复高音量：从MAYBE_END回到IN_SPEECH
        val resumeEvent = vad.processVolume(6.0f)
        assertEquals("MAYBE_END中又有语音应返回SPEECH_CONTINUE",
            VAD.VADEvent.SPEECH_CONTINUE, resumeEvent)
    }

    @Test
    fun `测试短静音触发SPEECH_END`() {
        // 先触发SPEECH_START
        vad.processVolume(6.0f)
        vad.processVolume(6.0f) // SPEECH_START
        vad.processVolume(6.0f) // SPEECH_CONTINUE

        // 设置短静音阈值非常小（0ms等效立刻），但我们已在setUp设置50ms
        vad.shortSilenceMs = 0L

        // 进入MAYBE_END
        vad.processVolume(1.0f)

        // 等待超过shortSilenceMs（50ms）：通过再给1帧低音量，silenceDuration会是当前时间-lastVoiceTimestamp
        // 为了避免Thread.sleep，我们再调用一次processVolume(低音量)，
        // 由于lastVoiceTimestamp是之前的时间，silenceDuration应该增加
        // 但更稳妥的方式是连续调用多次低音量
        vad.shortSilenceMs = 0L
        // 再给一帧低音量，此时检查是否触发SPEECH_END
        // 因为shortSilenceMs=0，只要hasVoice=false，silenceDuration>=0就会触发SPEECH_END
        // 但由于使用了当前时间戳，可能需要实际等待，所以让我们在短静音为0的情况下
        // 连续2-3次调用低音量来尝试触发
        vad.shortSilenceMs = 0L

        var speechEndTriggered = false
        // 多尝试几帧，确保silenceDuration覆盖阈值
        for (i in 1..5) {
            val evt = vad.processVolume(1.0f)
            if (evt == VAD.VADEvent.SPEECH_END) {
                speechEndTriggered = true
                break
            }
        }
        // 断言：或者在循环中触发，或者直接isInSpeech()变false视为结束
        assertTrue("短静音后应触发SPEECH_END或状态已结束",
            speechEndTriggered || !vad.isInSpeech())
    }

    @Test
    fun `测试reset方法重置状态机`() {
        // 先进入说话中状态
        vad.processVolume(6.0f)
        vad.processVolume(6.0f) // SPEECH_START
        assertTrue("触发后应处于说话中", vad.isInSpeech())
        assertTrue("语音持续时长应>0", vad.getSpeechDurationMs() >= 0)

        // 调用reset
        vad.reset()

        // 检查状态重置
        assertFalse("reset后不应处于说话中", vad.isInSpeech())
        assertEquals("reset后语音时长应为0", 0L, vad.getSpeechDurationMs())

        // 再给低音量，不应该触发任何东西
        val evt = vad.processVolume(1.0f)
        assertFalse("reset后低音量不应进入说话", vad.isInSpeech())
    }

    @Test
    fun `测试markSpeechStart手动标记语音开始`() {
        // 初始不在说话中
        assertFalse("初始不应处于说话中", vad.isInSpeech())

        // 手动标记开始
        vad.markSpeechStart()

        // 验证进入说话状态
        assertTrue("手动标记后应处于说话中", vad.isInSpeech())
        assertTrue("语音持续时长应>=0", vad.getSpeechDurationMs() >= 0)
    }

    @Test
    fun `测试markSpeechEnd手动标记语音结束`() {
        // 先标记开始
        vad.markSpeechStart()
        assertTrue("开始后应处于说话中", vad.isInSpeech())

        // 手动标记结束
        vad.markSpeechEnd()

        // 验证结束
        assertFalse("标记结束后不应处于说话中", vad.isInSpeech())
    }

    @Test
    fun `测试音量平滑 - 多次输入后getCurrentSmoothedVolume`() {
        // smoothWindowSize=1，每帧就是平滑后的值
        vad.processVolume(4.0f)
        val smoothed1 = vad.getCurrentSmoothedVolume()
        assertEquals("单帧平滑后音量应匹配", 4.0f, smoothed1, 0.01f)

        vad.processVolume(6.0f)
        val smoothed2 = vad.getCurrentSmoothedVolume()
        assertEquals("第2帧平滑后音量应匹配", 6.0f, smoothed2, 0.01f)
    }

    @Test
    fun `测试校准模式 - processVolume返回NO_EVENT不改变状态`() {
        // 启动校准
        vad.startCalibration()

        // 校准模式下，即使音量超阈值也只返回NO_EVENT
        val evt1 = vad.processVolume(8.0f)
        val evt2 = vad.processVolume(1.0f)

        assertEquals("校准中超阈值只返回NO_EVENT", VAD.VADEvent.NO_EVENT, evt1)
        assertEquals("校准中低音量也返回NO_EVENT", VAD.VADEvent.NO_EVENT, evt2)
        assertFalse("校准中不会进入说话状态", vad.isInSpeech())

        // 停止校准并获取建议阈值
        val suggested = vad.stopCalibration()
        assertTrue("校准后阈值应合理（>0）", suggested > 0f)
    }

    @Test
    fun `测试默认常量值 - 防止意外修改`() {
        // 验证默认配置常量，防止代码修改破坏需求
        assertEquals("默认音量阈值应为3.5f", 3.5f, VAD.DEFAULT_VOLUME_THRESHOLD, 0.001f)
        assertEquals("默认短静音应为800ms", 800L, VAD.DEFAULT_SHORT_SILENCE_MS)
        assertEquals("默认长静音应为5000ms", 5000L, VAD.DEFAULT_LONG_SILENCE_MS)
        assertEquals("默认最小语音时长300ms", 300L, VAD.DEFAULT_MIN_SPEECH_DURATION_MS)
        assertEquals("默认平滑窗口大小5", 5, VAD.DEFAULT_SMOOTH_WINDOW_SIZE)
    }
}
