package jp.pocketnas.server

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat

class WatchdogReceiver : BroadcastReceiver() {
    companion object {
        private const val REQ = 1001
        const val INTERVAL_MS = 30 * 60 * 1000L

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingIntent(context)
            val trigger = SystemClock.elapsedRealtime() + INTERVAL_MS
            try {
                if (Build.VERSION.SDK_INT >= 23) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
                } else {
                    am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
                }
            } catch (_: Exception) {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            }
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pendingIntent(context))
        }

        private fun pendingIntent(context: Context): PendingIntent {
            val i = Intent(context, WatchdogReceiver::class.java)
            return PendingIntent.getBroadcast(
                context, REQ, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = ServerPrefs(context)
        if (prefs.treeUri != null) {
            if (!NasServerService.running) {
                val svc = Intent(context, NasServerService::class.java).setAction("START")
                try { ContextCompat.startForegroundService(context, svc) } catch (_: Exception) {}
            } else {
                val svc = Intent(context, NasServerService::class.java).setAction("REFRESH_ADVERTISE")
                try { context.startService(svc) } catch (_: Exception) {}
            }
            schedule(context)
        }
    }
}