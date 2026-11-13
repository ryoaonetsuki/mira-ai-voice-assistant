package io.github.ryoaonetsuki.mira.automation.agent

import org.junit.Test
import org.junit.Assert.*
import kotlin.random.Random

class MikaWiringTest {

    @Test
    fun defaultPaceIsOff() {
        val pacing = MIRA_DEFAULT_OPERATING_PACE.appPacing()
        assertFalse(pacing.paced)
        val now = System.currentTimeMillis()
        assertEquals(0L, pacing.waitBefore(now))
        pacing.noteAction("com.app", now, newScreen = true)
        assertEquals(0L, pacing.waitBefore(System.currentTimeMillis()))
    }

    @Test
    fun fastTypingIsInstant() {
        val typing = OperatingPace.FAST.typing(PlacePace.NORMAL)
        assertSame(TypingPacing.Instant, typing)
    }

    @Test
    fun standardTypingIsNotInstant() {
        val typing = OperatingPace.STANDARD.typing(PlacePace.NORMAL)
        assertNotSame(TypingPacing.Instant, typing)
    }

    @Test
    fun humanTypingIsNotInstant() {
        val typing = OperatingPace.HUMAN.typing(PlacePace.NORMAL)
        assertNotSame(TypingPacing.Instant, typing)
    }

    @Test
    fun carefulTypingIsNotInstant() {
        val typing = OperatingPace.STANDARD.typing(PlacePace.CAREFUL)
        assertNotSame(TypingPacing.Instant, typing)
    }

    @Test
    fun typeAndVerifyBlockingMatches() {
        var backing = ""
        val field = object : TextField {
            override fun read(): String? = backing
            override fun write(text: String): Boolean {
                backing = text
                return true
            }
        }
        assertEquals(Verification.MATCH, typeAndVerifyBlocking(field, "hello"))
        assertEquals("hello", backing)
    }

    @Test
    fun typeAndVerifyBlockingMismatchWhenWriteRefused() {
        val field = object : TextField {
            override fun read(): String? = ""
            override fun write(text: String): Boolean = false
        }
        assertEquals(Verification.MISMATCH, typeAndVerifyBlocking(field, "hello"))
    }
}
