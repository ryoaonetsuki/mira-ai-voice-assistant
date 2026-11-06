package io.github.ryoaonetsuki.mira.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class MacroStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        MacroRunner.cancel("Stopped from notification")
        if (context != null) {
            Toast.makeText(context, "Mira stopped screen automation", Toast.LENGTH_SHORT).show()
        }
    }
}
