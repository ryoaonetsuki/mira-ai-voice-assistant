package io.github.ryoaonetsuki.mira.voice.pipecat

import org.junit.Assert.*
import org.junit.Test

class VoiceEchoDetectorTest {

    @Test
    fun testExactRepeatIsEcho() {
        assertTrue("Exact repeat of model speech is echo", VoiceEchoDetector.matchesEcho("hello world", "hello world"))
    }

    @Test
    fun testNearRepeatOneWordDifferentNotEcho() {
        // overlap = 3/4 = 0.75 < 0.85 -> NOT echo
        assertFalse("Near-repeat with one word different is NOT echo", VoiceEchoDetector.matchesEcho("hello world foo bar", "hello world foo baz"))
    }

    @Test
    fun testHighOverlapIsEcho() {
        // overlap = 6/7 ≈ 0.857 >= 0.85 -> echo
        assertTrue("High overlap ~0.86 is echo", VoiceEchoDetector.matchesEcho("a b c d e f g", "a b c d e f h"))
    }

    @Test
    fun testStopVsStopKorchiNotEcho() {
        // Single token user "stop" vs model "stop করছি" -> not exact match -> NOT echo
        assertFalse("User 'stop' vs model 'stop করছি' is NOT echo", VoiceEchoDetector.matchesEcho("stop", "stop করছি"))
    }

    @Test
    fun testLongUserSentenceContainingShortModelReplyNotEcho() {
        // Model "হ্যাঁ" is short (< 15 chars), substring rule requires both >= 15
        // Overlap is low (1 token match out of many) -> NOT echo
        assertFalse("Long user sentence containing model's short 'হ্যাঁ' is NOT echo", VoiceEchoDetector.matchesEcho("হ্যাঁ আমি যাচ্ছি বাজারে", "হ্যাঁ"))
    }

    @Test
    fun testLongNearIdenticalStringsContainmentIsEcho() {
        // Both >= 15 chars, one contained in the other -> echo
        assertTrue("Long near-identical strings with containment is echo", VoiceEchoDetector.matchesEcho("the quick brown fox jumps over", "the quick brown fox jumps over the lazy dog"))
    }
}
