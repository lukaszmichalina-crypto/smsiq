package pl.surfiq.smsgateway.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import pl.surfiq.smsgateway.util.PrefsManager

/**
 * Second keep-alive lane (besides the AlarmManager watchdog).
 *
 * WorkManager keeps its schedule in a persistent DB and re-schedules itself after
 * process death, reboot and app update — it survives scenarios where the
 * AlarmManager chain breaks (one missed WatchdogReceiver.onReceive = no more
 * alarms until something re-arms it).
 *
 * Every ~15 min (WorkManager minimum period):
 *  - service dead  → restart it,
 *  - service alive → kick ACTION_FORCE_SYNC (unfreezes a stuck poll loop on MIUI),
 *  - always re-arm the AlarmManager watchdog in case its chain broke.
 */
class KeepAliveWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val TAG       = "KeepAliveWorker"
        private const val WORK_NAME = "smsiq-keepalive"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!PrefsManager(ctx).isConfigured) return Result.success()

        try {
            if (!GatewayService.isRunning) {
                Log.w(TAG, "GatewayService NOT running — restarting")
                startService(ctx, Intent(ctx, GatewayService::class.java))
            } else {
                Log.d(TAG, "GatewayService OK — kicking force sync")
                startService(ctx, Intent(ctx, GatewayService::class.java).apply {
                    action = GatewayService.ACTION_FORCE_SYNC
                })
            }
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException is possible on Android 12+
            // if the battery-optimization exemption was revoked. Never crash the
            // worker — the AlarmManager watchdog is the other lane.
            Log.e(TAG, "Keep-alive start failed: ${e.message}")
        }

        // Re-arm the AlarmManager watchdog in case its chain broke.
        runCatching { WatchdogReceiver.schedule(ctx) }

        return Result.success()
    }

    private fun startService(ctx: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }
}
