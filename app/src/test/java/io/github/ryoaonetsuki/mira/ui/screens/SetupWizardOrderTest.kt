package io.github.ryoaonetsuki.mira.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetupWizardOrderTest {

    @Test
    fun allRequiredRowsPrecedeAllOptionalRows() {
        val requiredEndIndex = WIZARD_ROW_ORDER.lastIndexOf { it.isRequired }
        val firstOptionalIndex = WIZARD_ROW_ORDER.firstIndexOf { !it.isRequired }
        assertTrue(requiredEndIndex < firstOptionalIndex || firstOptionalIndex == -1)
    }

    @Test
    fun rowSetIsExactlyTheSameEightAsBefore() {
        assertEquals(8, WIZARD_ROW_ORDER.size)
        val ids = WIZARD_ROW_ORDER.map { it.id }.toSet()
        assertEquals(
            setOf(
                "microphone",
                "battery",
                "file",
                "accessibility",
                "overlay",
                "notifications",
                "notificationAccess",
                "standardIntegrations"
            ),
            ids
        )
    }

    @Test
    fun accessibilityAndOverlayComeBeforeNotifications() {
        val accessibilityIndex = WIZARD_ROW_ORDER.indexOfFirst { it.id == "accessibility" }
        val overlayIndex = WIZARD_ROW_ORDER.indexOfFirst { it.id == "overlay" }
        val notificationsIndex = WIZARD_ROW_ORDER.indexOfFirst { it.id == "notifications" }
        assertTrue(accessibilityIndex >= 0)
        assertTrue(overlayIndex >= 0)
        assertTrue(notificationsIndex >= 0)
        assertTrue(accessibilityIndex < notificationsIndex)
        assertTrue(overlayIndex < notificationsIndex)
    }
}
