package io.github.ryoaonetsuki.mira

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import io.github.ryoaonetsuki.mira.automation.MacroSafetyBanner
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.ui.navigation.MiraNavGraph
import io.github.ryoaonetsuki.mira.ui.theme.MiraTheme

class MainActivity : FragmentActivity() {

    private val pendingNavigationIntent = mutableStateOf<Intent?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingNavigationIntent.value = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val userPrefs = UserPreferences(applicationContext)

        setContent {
            val paletteName by userPrefs.themePaletteFlow.collectAsState(initial = "Midnight")

            MiraTheme(paletteName = paletteName) {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        MiraNavGraph(
                            navigationIntent = pendingNavigationIntent.value,
                            onNavigationIntentHandled = {
                                pendingNavigationIntent.value = null
                            }
                        )
                        MacroSafetyBanner()
                    }
                }
            }
        }
    }
}
