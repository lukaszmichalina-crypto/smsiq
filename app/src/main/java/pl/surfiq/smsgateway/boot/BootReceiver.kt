package pl.surfiq.smsgateway.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import pl.surfiq.smsgateway.service.GatewayService
import pl.surfiq.smsgateway.util.PrefsManager

/**
 * Auto-starts the gateway service after device reboot or app update.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = PrefsManager(context)
        if (!prefs.isConfigured) {
            Log.d("BootReceiver", "Not configured — skipping auto-start")
            return
        }

        Log.d("BootReceiver", "Auto-starting gateway service after $action")
        val svcIntent = Intent(context, GatewayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svcIntent)
        } else {
            context.startService(svcIntent)
        }
    }
}
