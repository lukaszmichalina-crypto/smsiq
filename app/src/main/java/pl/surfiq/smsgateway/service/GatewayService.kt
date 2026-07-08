package pl.surfiq.smsgateway.service

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.telephony.TelephonyManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import pl.surfiq.smsgateway.GatewayApp
import pl.surfiq.smsgateway.api.SupabaseGatewayClient
import pl.surfiq.smsgateway.model.GatewayConfig
import pl.surfiq.smsgateway.sms.SmsSender
import pl.surfiq.smsgateway.util.PrefsManager
import pl.surfiq.smsgateway.util.RateLimiter
import pl.surfiq.smsgateway.util.RetryManager
import pl.surfiq.smsgateway.service.WatchdogReceiver

class GatewayService : Service() {

    companion object {
        private const val TAG            = "GatewayService"
        private const val NOTIF_ID       = 1001
        private const val NOTIF_ALERT_ID = 1002
        /** Consecutive failed heartbeats (~1/min) before raising a local alert. */
        private const val HB_ALERT_THRESHOLD = 5
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
    private val rateLimiter by lazy { RateLimiter(this) }
    private var client:    SupabaseGatewayClient? = null
    private var pollJob:   Job? = null
    private var hbJob:     Job? = null

    /** Guard so the poll loop and ACTION_FORCE_SYNC never drain the queue concurrently. */
    private val draining = AtomicBoolean(false)

    /** Consecutive failed heartbeats — self-alert after HB_ALERT_THRESHOLD. */
    @Volatile private var hbFailStreak = 0

    // WakeLock — keeps CPU alive during SmsManager dispatch (critical on MIUI)
    private var wakeLock: PowerManager.WakeLock? = null

    // Battery state
    private var batteryLevel = 100
    private var isCharging   = false
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            // EXTRA_LEVEL is raw and must be scaled by EXTRA_SCALE — not always 0..100.
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) batteryLevel = level * 100 / scale
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
        WatchdogReceiver.schedule(this)
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
        releaseWakeLock()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}

        // Best-effort offline heartbeat — MUST NOT block the main thread (the old
        // runBlocking here could hang up to ~25 s of network timeouts = ANR risk).
        // Fired on a detached scope; if the process dies first, the server-side
        // stale-heartbeat detection covers it.
        if (prefs.isConfigured) {
            val offlineClient = buildClient()
            val level = batteryLevel
            val charging = isCharging
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    offlineClient.heartbeat(Build.MODEL, null, level, charging, "offline", "offline")
                }
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // MIUI may kill the service when user swipes the app from recents — schedule restart
        val restartIntent = Intent(applicationContext, GatewayService::class.java)
        val pi = PendingIntent.getService(
            applicationContext, 1,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 5_000, pi)
        Log.w(TAG, "onTaskRemoved — restart scheduled in 5s")
        super.onTaskRemoved(rootIntent)
    }

    // ── WakeLock ─────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SurfIQGateway:SmsSend"
        ).apply { acquire(30_000) } // 30s hard timeout — SMS must be dispatched by then
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
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
        reportPendingCrash()
        startPollLoop()
        startHeartbeatLoop()
        updateNotif("Active")
    }

    /** Sends a crash captured by GatewayApp's UncaughtExceptionHandler to gateway-log. */
    private fun reportPendingCrash() {
        val c = client ?: return
        scope.launch {
            try {
                val crash = GatewayApp.peekPendingCrash(this@GatewayService) ?: return@launch
                val sent = c.log("error", "CRASH: $crash")
                if (sent) {
                    GatewayApp.clearPendingCrash(this@GatewayService)
                    Log.i(TAG, "Pending crash report delivered")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Crash report failed: ${e.message}")
            }
        }
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

    /**
     * Drains the queue: keeps claiming and sending until it is empty.
     * (v1.1.0 claimed only ONE message per poll cycle → max 3 SMS/min at 20 s poll.)
     *
     * Rate limiting: when the per-minute window is full we suspend-delay until a
     * slot frees up instead of requeueing — claim_next_sms increments `attempts`
     * on every claim, so requeueing falsely escalated messages to manual_review.
     */
    private suspend fun processQueue() {
        val c = client ?: return
        if (!draining.compareAndSet(false, true)) return  // another drain in flight
        try {
            while (!isPaused) {
                if (rateLimiter.isDayLimitReached()) {
                    Log.w(TAG, "Daily limit ${RateLimiter.MAX_PER_DAY} reached — stopping drain")
                    updateNotif("Dzienny limit ${RateLimiter.MAX_PER_DAY} SMS osiągnięty")
                    return
                }

                // Wait for a per-minute slot BEFORE claiming, so a claimed message
                // is always immediately sendable.
                while (true) {
                    val waitMs = rateLimiter.millisUntilMinuteSlot()
                    if (waitMs <= 0L) break
                    Log.d(TAG, "Rate limit ${rateLimiter.countMinute}/min — waiting ${waitMs} ms")
                    delay(waitMs)
                }

                val msg = c.claimNext() ?: return  // queue empty
                Log.d(TAG, "Processing ${msg.id} → ${msg.toPhone}")

                // WakeLock per message: keep CPU alive for the full SmsManager dispatch
                // cycle. MIUI can freeze coroutine schedulers between updateStatus("sending")
                // and the SmsManager callback, leaving messages stuck as "sending".
                acquireWakeLock()
                try {
                    c.updateStatus(msg.id, "sending")

                    val dispatched = withContext(Dispatchers.Main) {
                        smsSender.send(msg, prefs.simSubscriptionId)
                    }

                    if (dispatched) {
                        rateLimiter.record()
                        sentToday = rateLimiter.countDay
                        lastStatus = "Last sent: ${msg.toPhone.takeLast(6)}"
                        updateNotif("Active — sent today: $sentToday")
                        // Final status arrives via SmsStatusReceiver callback
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
                } finally {
                    releaseWakeLock()
                }
            }
        } finally {
            draining.set(false)
        }
    }

    private fun sendHeartbeat() {
        val status = if (isPaused) "paused" else "online"
        // Read capacity directly each beat — robust even if the sticky battery
        // broadcast was missed (this is what made the panel show a stale 1%).
        val bm    = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 } ?: batteryLevel

        val ok = client?.heartbeat(
            Build.MODEL, null, level, isCharging,
            signalStatus = readSignalStatus(),
            status       = status,
            sentToday    = rateLimiter.countDay,
        ) ?: false
        lastHeartbeat = System.currentTimeMillis()

        // Self-alert: the phone itself warns when it cannot reach the server —
        // don't wait for someone to notice a frozen last_seen_at in the panel.
        if (ok) {
            if (hbFailStreak >= HB_ALERT_THRESHOLD) {
                getSystemService(NotificationManager::class.java).cancel(NOTIF_ALERT_ID)
            }
            hbFailStreak = 0
        } else {
            hbFailStreak++
            Log.w(TAG, "Heartbeat failed ($hbFailStreak in a row)")
            if (hbFailStreak == HB_ALERT_THRESHOLD) showConnectivityAlert()
        }
    }

    /** Cheap signal readout — no extra permissions (TelephonyManager.getSignalStrength, API 28+). */
    private fun readSignalStatus(): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.signalStrength?.level?.let { "ok ($it/4)" } ?: "ok"
        } else "ok"
    } catch (_: Exception) { "ok" }

    private fun showConnectivityAlert() {
        val notif = Notification.Builder(this, GatewayApp.CHANNEL_ALERTS)
            .setContentTitle("SMSIQ — brak łączności")
            .setContentText("Bramka SMS nie łączy się z serwerem od $HB_ALERT_THRESHOLD minut — sprawdź internet")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT_ID, notif)
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
            .setContentTitle("SMSIQ — bramka SMS")
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
