package pl.surfiq.smsgateway

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import pl.surfiq.smsgateway.service.KeepAliveWorker

class GatewayApp : Application() {

    companion object {
        const val CHANNEL_SERVICE = "gateway_service"
        const val CHANNEL_ALERTS  = "gateway_alerts"

        private const val TAG               = "GatewayApp"
        private const val CRASH_PREFS       = "smsiq_crash"
        private const val KEY_PENDING_CRASH = "pending_crash"
        private const val MAX_STACK_CHARS   = 4000

        /** Returns the stored crash report (or null). Does NOT clear it. */
        fun peekPendingCrash(context: Context): String? =
            context.getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PENDING_CRASH, null)

        /** Clears the stored crash report after it was successfully delivered. */
        fun clearPendingCrash(context: Context) {
            context.getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_PENDING_CRASH).apply()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        installCrashHandler()
        // Second keep-alive lane besides the AlarmManager watchdog: WorkManager has
        // its own persistent scheduler and survives scenarios where the alarm chain
        // breaks (a single missed onReceive kills the whole AlarmManager chain).
        KeepAliveWorker.schedule(this)
    }

    /**
     * Captures any uncaught exception to SharedPreferences (synchronous commit —
     * the process is about to die). GatewayService sends it to gateway-log with a
     * CRASH prefix on next start and clears it.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .format(Date())
                val report = buildString {
                    append(ts)
                    append(" thread=").append(thread.name).append('\n')
                    append(e.stackTraceToString().take(MAX_STACK_CHARS))
                }
                getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_PENDING_CRASH, report).commit()
            } catch (inner: Exception) {
                Log.e(TAG, "Failed to persist crash: ${inner.message}")
            }
            previous?.uncaughtException(thread, e)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                "SMS Gateway",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SMSIQ — status działania bramki SMS"
                setShowBadge(false)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                "Gateway Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Errors and important gateway alerts"
            }
        )
    }
}
