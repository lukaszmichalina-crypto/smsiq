package pl.surfiq.smsgateway

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class GatewayApp : Application() {

    companion object {
        const val CHANNEL_SERVICE = "gateway_service"
        const val CHANNEL_ALERTS  = "gateway_alerts"
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
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
