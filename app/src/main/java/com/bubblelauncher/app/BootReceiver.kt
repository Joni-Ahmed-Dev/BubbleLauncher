package com.bubblelauncher.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.bubblelauncher.app.data.BubbleStore
import com.bubblelauncher.app.service.BubbleService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        BubbleStore.init(context)
        if (BubbleStore.settings.value.autoStart && Settings.canDrawOverlays(context)) {
            ContextCompat.startForegroundService(context, Intent(context, BubbleService::class.java))
        }
    }
}
