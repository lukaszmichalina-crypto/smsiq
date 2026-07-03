package pl.surfiq.smsgateway.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * AlarmManager-based watchdog for MIUI/Doze.
 * Fired every INTERVAL_MS — if GatewayService is not running, restarts it.
 * Works even when MIUI freezes the normal coroutine scheduler.
 * If the service is running, sends ACTION_FORCE_SYNC to ensure pollJob is alive.
 */
class WatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG          = "WatchdogReceiver"
        private const val INTERVAL_MS  = 5 * 60 * 1000L // 5 minutes
        private const val REQUEST_CODE = 9901

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingIntent(context)
            val triggerAt = System.currentTimeMillis() + INTERVAL_MS
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms() ->
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                else ->
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            Log.d(TAG, "Watchdog scheduled in ${INTERVAL_MS / 60_000} min")
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pendingIntent(context))
            Log.d(TAG, "Watchdog cancelled")
        }

        private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, WatchdogReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Watchdog fired — checking service health")

        if (!GatewayService.isRunning) {
            Log.w(TAG, "GatewayService NOT running — restarting!")
            val svcIntent = Intent(context, GatewayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svcIntent)
            } else {
                context.startService(svcIntent)
            }
        } else {
            Log.d(TAG, "GatewayService OK — forcing sync check")
            // Kick the poll loop in case it's stuck (coroutine scheduler frozen by MIUI)
            val syncIntent = Intent(context, GatewayService::class.java).apply {
                action = GatewayService.ACTION_FORCE_SYNC
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(syncIntent)
            } else {
                context.startService(syncIntent)
            }
        }

        // Always re-arm the next watchdog tick
        schedule(context)
    }
}
