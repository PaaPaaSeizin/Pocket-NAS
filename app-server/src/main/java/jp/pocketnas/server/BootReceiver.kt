package jp.pocketnas.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return

        val prefs = ServerPrefs(context)
        if (prefs.treeUri == null) return

        val svc = Intent(context, NasServerService::class.java).setAction("START")
        try { ContextCompat.startForegroundService(context, svc) } catch (_: Exception) {}
    }
}