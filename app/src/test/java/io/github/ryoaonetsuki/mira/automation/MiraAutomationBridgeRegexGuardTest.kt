package io.github.ryoaonetsuki.mira.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 25 Fix Turn D guard:
 * 1. Plain-JVM source scan across app/src/main/java ensuring (?U) and other Android ICU-rejected
 *    regex flags/options never appear anywhere in production code.
 * 2. Runtime reflection + compilation check across every Regex table and dynamic pattern builder
 *    in MiraAutomationBridge, validating Android ICU inline-flag compatibility (ICU only permits
 *    [idmsuxw] in inline flag blocks; (?U) crashes PatternNative.compileImpl with PatternSyntaxException).
 * 3. Matching-behavior verification across accented Latin (podrías, können, você, peux-tu),
 *    Cyrillic/Greek (ты можешь, μπορείς), Turkish (yapabilir misin), and CJK ("打开YouTube").
 */
class MiraAutomationBridgeRegexGuardTest {

    companion object {
        // Android ICU (regexcmp.cpp) only accepts i, d, m, s, u, x, w in inline flag modifiers.
        // Uppercase U (UNICODE_CHARACTER_CLASS) is an OpenJDK-only flag that throws
        // PatternSyntaxException at runtime on Android (PatternNative.compileImpl).
        private val ICU_UNSUPPORTED_INLINE_FLAG =
            Regex("""\(\?[idmsuxw-]*U[idmsuxwU-]*[:)]""")

        fun assertAndroidIcuCompatiblePattern(sourceLabel: String, pattern: String) {
            assertFalse(
                "Pattern in $sourceLabel contains '(?U)' which crashes Android ICU PatternNative: $pattern",
                pattern.contains("(?U)")
            )
            assertFalse(
                "Pattern in $sourceLabel contains Android ICU-unsupported inline flag: $pattern",
                ICU_UNSUPPORTED_INLINE_FLAG.containsMatchIn(pattern)
            )
            // Ensure pattern compiles cleanly
            Regex(pattern)
        }

        @Suppress("UNCHECKED_CAST")
        fun verifyAllBridgePatternsCompileAndPassIcuCheck() {
            fun getPrivateBridgeField(name: String): Any {
                val field = try {
                    MiraAutomationBridge.Companion::class.java.getDeclaredField(name).also { it.isAccessible = true }
                } catch (_: NoSuchFieldException) {
                    MiraAutomationBridge::class.java.getDeclaredField(name).also { it.isAccessible = true }
                }
                return if (java.lang.reflect.Modifier.isStatic(field.modifiers)) {
                    field.get(null)
                } else {
                    field.get(MiraAutomationBridge.Companion)
                } ?: throw AssertionError("Field $name evaluated to null")
            }

            val intentPatterns = getPrivateBridgeField("INTENT_PATTERNS") as List<Regex>
            val capabilityQuestions = getPrivateBridgeField("CAPABILITY_QUESTION") as List<Regex>
            val strongActions = getPrivateBridgeField("STRONG_ACTION") as List<Regex>
            val latinVerbs = getPrivateBridgeField("LATIN_VERBS") as Set<String>
            val knownApps = getPrivateBridgeField("KNOWN_APPS") as Set<String>
            val canonicalMap =
                getPrivateBridgeField("TARGET_APP_CANONICAL_MAP") as List<Pair<List<String>, String>>

            assertTrue("INTENT_PATTERNS must not be empty", intentPatterns.isNotEmpty())
            assertTrue("CAPABILITY_QUESTION must not be empty", capabilityQuestions.isNotEmpty())
            assertTrue("STRONG_ACTION must not be empty", strongActions.isNotEmpty())
            assertTrue("LATIN_VERBS must not be empty", latinVerbs.isNotEmpty())
            assertTrue("KNOWN_APPS must not be empty", knownApps.isNotEmpty())
            assertTrue("TARGET_APP_CANONICAL_MAP must not be empty", canonicalMap.isNotEmpty())

            for ((idx, regex) in intentPatterns.withIndex()) {
                assertAndroidIcuCompatiblePattern("INTENT_PATTERNS[$idx]", regex.pattern)
            }
            for ((idx, regex) in capabilityQuestions.withIndex()) {
                assertAndroidIcuCompatiblePattern("CAPABILITY_QUESTION[$idx]", regex.pattern)
            }
            for ((idx, regex) in strongActions.withIndex()) {
                assertAndroidIcuCompatiblePattern("STRONG_ACTION[$idx]", regex.pattern)
            }

            // Exercise dynamic regex compilation across ALL entries in LATIN_VERBS, KNOWN_APPS, and TARGET_APP_CANONICAL_MAP
            for (verb in latinVerbs) {
                MiraAutomationBridge.isAutomationIntent("$verb youtube")
            }
            for (app in knownApps) {
                MiraAutomationBridge.isAutomationIntent("open $app")
            }
            for ((keywords, _) in canonicalMap) {
                for (keyword in keywords) {
                    MiraAutomationBridge.extractTargetApp("open $keyword")
                }
            }
            MiraAutomationBridge.isCapabilityQuestion("can you control my phone")
        }

        fun verifyMultilingualMatchingBehavior() {
            // 1. Accented Latin capability-question verbs (podrías, können, você, peux-tu)
            assertTrue(MiraAutomationBridge.isCapabilityQuestion("¿podrías controlar mi teléfono?"))
            assertFalse(MiraAutomationBridge.isAutomationIntent("¿podrías abrir YouTube?"))

            assertTrue(MiraAutomationBridge.isCapabilityQuestion("können Sie mein Telefon steuern?"))
            assertFalse(MiraAutomationBridge.isAutomationIntent("können Sie YouTube öffnen?"))

            assertTrue(MiraAutomationBridge.isCapabilityQuestion("você pode controlar meu telefone?"))
            assertFalse(MiraAutomationBridge.isAutomationIntent("você pode abrir o YouTube?"))

            assertTrue(MiraAutomationBridge.isCapabilityQuestion("peux-tu contrôler mon téléphone ?"))
            assertFalse(MiraAutomationBridge.isAutomationIntent("peux-tu ouvrir YouTube ?"))

            // 2. Cyrillic and Greek capability-question verbs (ты можешь, μπορείς)
            assertTrue(MiraAutomationBridge.isCapabilityQuestion("ты можешь управлять телефоном?"))
            assertFalse(MiraAutomationBridge.isAutomationIntent("ты можешь открыть ютуб?"))

            assertTrue(MiraAutomationBridge.isCapabilityQuestion("μπορείς να ελέγξεις το τηλέφωνο;"))
            assertFalse(MiraAutomationBridge.isAutomationIntent("μπορείς να ανοίξεις το YouTube;"))

            // 3. Turkish capability-question verbs (yapabilir misin)
            assertTrue(MiraAutomationBridge.isCapabilityQuestion("bunu yapabilir misin?"))
            assertTrue(MiraAutomationBridge.isCapabilityQuestion("telefonu kontrol edebilir misin?"))
            assertFalse(MiraAutomationBridge.isAutomationIntent("youtube açabilir misin?"))

            // 4. Accented Latin command verbs in LATIN_VERBS (must still match as automation intents)
            assertTrue(MiraAutomationBridge.isAutomationIntent("öffne YouTube"))
            assertTrue(MiraAutomationBridge.isAutomationIntent("YouTube aç"))
            assertTrue(MiraAutomationBridge.isAutomationIntent("müzik çal"))
            assertTrue(MiraAutomationBridge.isAutomationIntent("mở YouTube"))
            assertTrue(MiraAutomationBridge.isAutomationIntent("öppna YouTube"))
            assertTrue(MiraAutomationBridge.isAutomationIntent("włącz muzykę"))

            // 5. CJK case from Turn C ("打开YouTube" — no \b between CJK char '开' and Latin 'Y')
            assertTrue(MiraAutomationBridge.isAutomationIntent("打开YouTube"))
            assertEquals("com.google.android.youtube", MiraAutomationBridge.extractTargetApp("打开YouTube"))
        }
    }

    @Test
    fun mainSourceTree_containsNoAndroidUnsupportedRegexFlags() {
        val candidates = listOf(
            File("app/src/main/java"),
            File("../app/src/main/java"),
            File("/home/hatch/workspace/mira-final/app/src/main/java")
        )
        val srcRoot = candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError("Could not locate app/src/main/java from ${File(".").absolutePath}")

        val violations = mutableListOf<String>()
        var scannedFiles = 0

        srcRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { file ->
                scannedFiles++
                file.readLines(Charsets.UTF_8).forEachIndexed { idx, line ->
                    val lineNum = idx + 1
                    if (line.contains("(?U)") || ICU_UNSUPPORTED_INLINE_FLAG.containsMatchIn(line)) {
                        violations.add("${file.relativeTo(srcRoot)}:$lineNum -> ${line.trim()}")
                    }
                    if (line.contains("UNICODE_CHARACTER_CLASS") || line.contains("CANON_EQ")) {
                        violations.add("${file.relativeTo(srcRoot)}:$lineNum (unsupported Pattern flag) -> ${line.trim()}")
                    }
                }
            }

        assertTrue("Expected to scan Kotlin/Java source files under app/src/main/java", scannedFiles > 0)
        assertTrue(
            "Found Android ICU-unsupported regex construct(s) in main source tree:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun allBridgeRegexPatterns_compileAndHaveNoIcuRejectedFlags() {
        verifyAllBridgePatternsCompileAndPassIcuCheck()
    }

    @Test
    fun replacementPatterns_preserveMultilingualAndCjkMatchingBehavior() {
        verifyMultilingualMatchingBehavior()
    }
}
