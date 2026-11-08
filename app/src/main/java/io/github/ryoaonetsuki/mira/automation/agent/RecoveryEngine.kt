// Ported from Jarvis (github.com/AbuZar-Ansarii/Jarvis), MIT Licence, Copyright (c) 2026 AbuZar Ansarii — Dart → Kotlin port for Mira.
package io.github.ryoaonetsuki.mira.automation.agent

/**
 * Detects when the agent loop is stuck (same action repeating, or the
 * screen never changing) and proposes corrective fallback actions.
 */
class RecoveryEngine {
  private val recentSignatures = mutableListOf<String>()
  private var lastScreenFingerprint: String? = null
  private var unchangedScreens: Int = 0
  private var recoveryCursor: Int = 0

  fun reset() {
    recentSignatures.clear()
    lastScreenFingerprint = null
    unchangedScreens = 0
    recoveryCursor = 0
  }

  fun recordAction(step: TaskStep) {
    recentSignatures.add(step.signature)
    if (recentSignatures.size > MAX_SIGNATURES) {
      recentSignatures.removeAt(0)
    }
  }

  /**
   * Records a lightweight fingerprint of the latest screen dump.
   */
  fun recordScreen(formattedDump: String) {
    if (formattedDump == lastScreenFingerprint) {
      unchangedScreens++
    } else {
      unchangedScreens = 0
      lastScreenFingerprint = formattedDump
    }
  }

  /**
   * The exact same action (name + params) repeated >= 3 times in a row.
   */
  val isRepeatingActions: Boolean
    get() {
      if (recentSignatures.size < SIGNATURE_WINDOW) return false
      val tail = recentSignatures.takeLast(SIGNATURE_WINDOW)
      return tail.toSet().size == 1
    }

  val isScreenStuck: Boolean
    get() = unchangedScreens >= UNCHANGED_THRESHOLD

  val shouldRecover: Boolean
    get() = isRepeatingActions || isScreenStuck

  /**
   * Rotating recovery strategies: scroll for more content, wait for slow
   * screens, then back out of a dead end.
   */
  fun suggestRecovery(): TaskStep {
    val step = RECOVERY_STRATEGIES[recoveryCursor % RECOVERY_STRATEGIES.size]
    recoveryCursor++
    return step
  }

  companion object {
    const val SIGNATURE_WINDOW: Int = 3
    const val UNCHANGED_THRESHOLD: Int = 3
    const val MAX_SIGNATURES: Int = 10

    private val RECOVERY_STRATEGIES: List<TaskStep> = listOf(
      TaskStep(
        action = TaskStep.ACTION_SCROLL,
        params = mapOf("direction" to "down"),
        reasoning = "Recovery: screen appears stuck; scrolling for new content."
      ),
      TaskStep(
        action = TaskStep.ACTION_WAIT,
        params = mapOf("ms" to 2000),
        reasoning = "Recovery: waiting for the UI to settle."
      ),
      TaskStep(
        action = TaskStep.ACTION_PRESS_BACK,
        params = emptyMap(),
        reasoning = "Recovery: backing out of a possible dead end."
      )
    )
  }
}
