package io.github.ryoaonetsuki.mira.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class MiraAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "MiraAccessibilityService connected and ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Active window and content change events available for automation
    }

    override fun onInterrupt() {
        Log.w(TAG, "MiraAccessibilityService interrupted")
        if (instance === this) {
            instance = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "MiraAccessibilityService destroyed")
        if (instance === this) {
            instance = null
        }
    }

    companion object {
        private const val TAG = "MiraAccessibility"

        @Volatile
        var instance: MiraAccessibilityService? = null
            internal set

        /**
         * Checks whether Mira's AccessibilityService is enabled in Android Settings.
         */
        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true

            val expectedComponent = ComponentName(context, MiraAccessibilityService::class.java).flattenToString()
            val expectedShort = "${context.packageName}/.service.MiraAccessibilityService"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledServices)
            while (splitter.hasNext()) {
                val service = splitter.next()
                if (service.equals(expectedComponent, ignoreCase = true) ||
                    service.equals(expectedShort, ignoreCase = true)
                ) {
                    val accessibilityEnabled = Settings.Secure.getInt(
                        context.contentResolver,
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        0
                    )
                    return accessibilityEnabled == 1
                }
            }
            return false
        }
    }
}
