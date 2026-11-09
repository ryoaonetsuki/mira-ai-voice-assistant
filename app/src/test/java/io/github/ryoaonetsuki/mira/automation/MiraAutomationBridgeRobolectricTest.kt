package io.github.ryoaonetsuki.mira.automation

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Round 25 Fix Turn D Robolectric guard:
 * Instantiates MiraAutomationBridge (triggering companion-object static initialization)
 * under Robolectric, compiles every regex in the bridge tables, and verifies multilingual
 * capability-question and automation-intent matching (accented Latin, Cyrillic/Greek,
 * Turkish, and CJK "打开YouTube").
 */
@RunWith(RobolectricTestRunner::class)
class MiraAutomationBridgeRobolectricTest {

    @Test
    fun bridgeClassInit_andAllRegexPatterns_succeedUnderRobolectric() {
        val context: Context = RuntimeEnvironment.getApplication()
        val bridge = MiraAutomationBridge.getInstance(context)
        assertNotNull("MiraAutomationBridge.getInstance(context) must succeed", bridge)
        assertFalse("New bridge instance should not be running a task", bridge.isRunning)

        MiraAutomationBridgeRegexGuardTest.verifyAllBridgePatternsCompileAndPassIcuCheck()
        MiraAutomationBridgeRegexGuardTest.verifyMultilingualMatchingBehavior()
    }
}
