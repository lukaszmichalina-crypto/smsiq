package pl.surfiq.smsgateway.service

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import kotlinx.coroutines.*
import pl.surfiq.smsgateway.GatewayApp
import pl.surfiq.smsgateway.api.SupabaseGatewayClient
import pl.surfiq.smsgateway.model.GatewayConfig
import pl.surfiq.smsgateway.sms.SmsSender
import pl.surfiq.smsgateway.util.PrefsManager
import pl.surfiq.smsgateway.util.RateLimiter
import pl.surfiq.smsgateway.util.RetryManager

class GatewayService : Service() {

    companion object {
        private const val TAG           = "GatewayService"
        private const val NOTIF_ID      = 1001
        const val  ACTION_PAUSE         = "pl.surfiq.smsgateway.PAUSE"
        const val  ACTION_RESUME        = "pl.surfiq.smsgateway.RESUME"
        const val  ACTION_FORCE_SYNC    = "pl.surfiq.smsgateway.FORCE_SYNC"
        const val  ACTION_RECONFIGURE   = "pl.surfiq.smsgateway.RECONFIGURE"

        // Shared state read by MainActivity diagnostics screen
        @Volatile var isRunning:     Boolean = false
        @Volatile var isPaused:      Boolean = false
        @Volatile var lastHeartbeat: Long    = 0L
        @Volatile var sentToday:     Int     = 0
        @Volatile var lastStatus:    String  = "Initializing…"
    }

    private lateinit var prefs:     PrefsManager
    private lateinit var smsSender: SmsSender
    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rateLimiter = RateLimiter()
    private var client:    SupabaseGatewayClient? = null
    private var pollJob:   Job? = null
    private var hbJob:     Job? = null

    // Battery state
    private var batteryLevel = 100
    private var isCharging   = false
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            batteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
            isCharging   = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        prefs     = PrefsManager(this)
        smsSender = SmsSender(this)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        startForegroundCompat("Starting…")
        isRunning = true
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                isPaused = true
                lastStatus = "Paused"
                updateNotif("Paused")
                scope.launch { client?.heartbeat(Build.MODEL, null, batteryLevel, isCharging, "paused", "paused") }
            }
            ACTION_RESUME -> {
                isPaused = false
                initClientAndStartLoops()
            }
            ACTION_FORCE_SYNC -> {
                if (!isPaused) scope.launch { processQueue() }
            }
            ACTION_RECONFIGURE -> {
                initClientAndStartLoops()
            }
            else -> {
                if (prefs.isConfigured) initClientAndStartLoops()
                else updateNotif("Not configured — open app")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}

        // Best-effort offline heartbeat
        if (prefs.isConfigured) {
            runBlocking(Dispatchers.IO) {
                runCatching {
                    buildClient().heartbeat(Build.MODEL, null, batteryLevel, isCharging, "offline", "offline")
                }
            }
        }
        super.onDestroy()
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun buildClient() = SupabaseGatewayClient(
        GatewayConfig(
            supabaseUrl      = prefs.supabaseUrl,
            gatewayToken     = prefs.gatewayToken,
            tenantId         = prefs.tenantId,
            gatewayId        = prefs.gatewayId,
            simSubscriptionId = prefs.simSubscriptionId,
            pollIntervalSec  = prefs.pollIntervalSec,
        )
    )

    private fun initClientAndStartLoops() {
        client = buildClient()
        startPollLoop()
        startHeartbeatLoop()
        updateNotif("Active")
    }

    private fun startPollLoop() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                if (!isPaused) {
                    try { processQueue() } catch (e: Exception) {
                        Log.e(TAG, "Poll error: ${e.message}")
                    }
                }
                delay(prefs.pollIntervalSec * 1_000L)
            }
        }
    }

    private fun startHeartbeatLoop() {
        hbJob?.cancel()
        hbJob = scope.launch {
            while (isActive) {
                delay(60_000)
                try { sendHeartbeat() } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat error: ${e.message}")
                }
            }
        }
        // Send immediately on (re)start
        scope.launch { sendHeartbeat() }
    }

    private suspend fun processQueue() {
        val c = client ?: return
        val msg = c.claimNext() ?: return  // nothing queued

        Log.d(TAG, "Processing ${msg.id} → ${msg.toPhone}")

        if (!rateLimiter.canSend()) {
            Log.w(TAG, "Rate limit hit — requeueing ${msg.id}")
            c.updateStatus(msg.id, "queued", "Rate limit: ${rateLimiter.countMinute}/min ${rateLimiter.countDay}/day")
            return
        }

        // Mark sending
        c.updateStatus(msg.id, "sending")

        val dispatched = withContext(Dispatchers.Main) {
            smsSender.send(msg, prefs.simSubscriptionId)
        }

        if (dispatched) {
            rateLimiter.record()
            sentToday++
            lastStatus = "Last sent: ${msg.toPhone.takeLast(6)}"
            updateNotif("Active — sent today: $sentToday")
            // Status will be updated by SmsStatusReceiver callback
        } else {
            val attempt = msg.attempts + 1
            if (attempt >= msg.maxAttempts) {
                c.updateStatus(msg.id, "manual_review", "Dispatch failed after ${msg.maxAttempts} attempts")
                c.log("error", "SMS ${msg.id} moved to manual_review", msg.id)
            } else {
                val nextAt = RetryManager.nextScheduledIso(attempt)
                c.updateStatus(msg.id, "retrying", "SmsManager dispatch failed", nextAt)
                c.log("warning", "SMS ${msg.id} dispatch failed, attempt $attempt/${msg.maxAttempts}", msg.id)
            }
        }
    }

    private fun sendHeartbeat() {
        val status = if (isPaused) "paused" else "online"
        client?.heartbeat(Build.MODEL, null, batteryLevel, isCharging, "unknown", status)
        lastHeartbeat = System.currentTimeMillis()
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun startForegroundCompat(status: String) {
        val notif = buildNotif(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotif(status: String) {
        lastStatus = status
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotif(status))
    }

    private fun buildNotif(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleAction = if (isPaused) {
            Notification.Action.Builder(null, "▶ Resume",
                pendingServiceAction(ACTION_RESUME, 1)).build()
        } else {
            Notification.Action.Builder(null, "⏸ Pause",
                pendingServiceAction(ACTION_PAUSE, 2)).build()
        }

        return Notification.Builder(this, GatewayApp.CHANNEL_SERVICE)
            .setContentTitle("SurfIQ SMS Gateway")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(toggleAction)
            .build()
    }

    private fun pendingServiceAction(action: String, reqCode: Int): PendingIntent =
        PendingIntent.getService(
            this, reqCode,
            Intent(this, GatewayService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
