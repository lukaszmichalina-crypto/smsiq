package pl.surfiq.smsgateway

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import pl.surfiq.smsgateway.api.SupabaseGatewayClient
import pl.surfiq.smsgateway.model.GatewayConfig
import pl.surfiq.smsgateway.model.SmsMessage
import pl.surfiq.smsgateway.service.GatewayService
import pl.surfiq.smsgateway.sms.InboxBackfill
import pl.surfiq.smsgateway.sms.SmsSender
import pl.surfiq.smsgateway.util.ContactsHelper
import pl.surfiq.smsgateway.util.PrefsManager
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var prefs:     PrefsManager
    private lateinit var smsSender: SmsSender
    private val scope  = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val logs   = ArrayDeque<String>(200)
    private var refreshJob: Job? = null

    // ── Views ─────────────────────────────────────────────────────────────────
    // Login panel
    private lateinit var layoutLogin:      View
    private lateinit var etUrl:            EditText
    private lateinit var etToken:          EditText
    private lateinit var etTenant:         EditText
    private lateinit var etGatewayId:      EditText
    private lateinit var spinnerSim:       Spinner
    private lateinit var etPollInterval:   EditText
    private lateinit var btnSave:          Button
    private lateinit var btnTestConn:      Button
    private lateinit var tvLoginStatus:    TextView

    // Diagnostics panel
    private lateinit var layoutDiag:       View
    private lateinit var tvSvcStatus:      TextView
    private lateinit var tvGwInfo:         TextView
    private lateinit var tvSimInfo:        TextView
    private lateinit var tvHeartbeat:      TextView
    private lateinit var tvStats:          TextView
    private lateinit var tvLogs:           TextView
    private lateinit var btnToggle:        Button
    private lateinit var btnForceSync:     Button
    private lateinit var btnTestSms:       Button
    private lateinit var btnBackfill:      Button
    private lateinit var btnSyncContacts:  Button
    private lateinit var btnLogout:        Button
    private lateinit var etTestPhone:      EditText

    // ── Permissions ───────────────────────────────────────────────────────────
    private val requiredPermissions: Array<String> = buildList {
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs     = PrefsManager(this)
        smsSender = SmsSender(this)

        bindViews()

        if (prefs.consentAccepted) startApp() else showConsentDialog()
    }

    /** First-launch consent for sensitive SMS/contacts access (sideload-only product). */
    private fun showConsentDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.consent_title)
            .setMessage(R.string.consent_body)
            .setCancelable(false)
            .setPositiveButton(R.string.consent_accept) { _, _ ->
                prefs.consentAccepted = true
                startApp()
            }
            .setNegativeButton(R.string.consent_decline) { _, _ -> finish() }
            .show()
    }

    private fun startApp() {
        requestMissingPermissions()
        askBatteryOptimizationExemption()

        if (prefs.isConfigured) {
            showDiagnostics()
            ensureServiceRunning()
        } else {
            showLogin()
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.isConfigured) {
            startAutoRefresh()
        }
    }

    override fun onPause() {
        super.onPause()
        refreshJob?.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private fun bindViews() {
        layoutLogin   = findViewById(R.id.layout_login)
        etUrl         = findViewById(R.id.et_supabase_url)
        etToken       = findViewById(R.id.et_gateway_token)
        etTenant      = findViewById(R.id.et_tenant_id)
        etGatewayId   = findViewById(R.id.et_gateway_id)
        spinnerSim    = findViewById(R.id.spinner_sim)
        etPollInterval= findViewById(R.id.et_poll_interval)
        btnSave       = findViewById(R.id.btn_save)
        btnTestConn   = findViewById(R.id.btn_test_connection)
        tvLoginStatus = findViewById(R.id.tv_login_status)

        layoutDiag    = findViewById(R.id.layout_diagnostics)
        tvSvcStatus   = findViewById(R.id.tv_service_status)
        tvGwInfo      = findViewById(R.id.tv_gateway_info)
        tvSimInfo     = findViewById(R.id.tv_sim_info)
        tvHeartbeat   = findViewById(R.id.tv_heartbeat)
        tvStats       = findViewById(R.id.tv_stats)
        tvLogs        = findViewById(R.id.tv_logs)
        btnToggle     = findViewById(R.id.btn_pause_resume)
        btnForceSync  = findViewById(R.id.btn_force_sync)
        btnTestSms    = findViewById(R.id.btn_test_sms)
        btnBackfill     = findViewById(R.id.btn_backfill)
        btnSyncContacts = findViewById(R.id.btn_sync_contacts)
        btnLogout     = findViewById(R.id.btn_logout)
        etTestPhone   = findViewById(R.id.et_test_phone)

        populateSimSpinner()

        btnSave.setOnClickListener      { saveConfig() }
        btnTestConn.setOnClickListener  { testConnection() }
        btnToggle.setOnClickListener    { togglePause() }
        btnForceSync.setOnClickListener { forceSync() }
        btnTestSms.setOnClickListener   { sendTestSms() }
        btnBackfill.setOnClickListener  { runBackfill() }
        btnSyncContacts.setOnClickListener { syncContacts() }
        btnLogout.setOnClickListener    { confirmLogout() }
    }

    // ── Config / Login ────────────────────────────────────────────────────────

    private fun showLogin() {
        layoutLogin.visibility = View.VISIBLE
        layoutDiag.visibility  = View.GONE
        etUrl.setText(prefs.supabaseUrl)
        etTenant.setText(prefs.tenantId)
        etGatewayId.setText(prefs.gatewayId)
        etPollInterval.setText(prefs.pollIntervalSec.toString())
    }

    private fun saveConfig() {
        val url      = etUrl.text.toString().trim().trimEnd('/')
        val token    = etToken.text.toString().trim()
        val tenant   = etTenant.text.toString().trim()
        val gwId     = etGatewayId.text.toString().trim()
        val interval = etPollInterval.text.toString().toIntOrNull() ?: 20

        if (url.isEmpty() || token.isEmpty() || tenant.isEmpty() || gwId.isEmpty()) {
            tvLoginStatus.text = "All fields are required"
            return
        }

        prefs.supabaseUrl    = url
        prefs.gatewayToken   = token
        prefs.tenantId       = tenant
        prefs.gatewayId      = gwId
        prefs.pollIntervalSec = interval.coerceIn(10, 300)

        val sims = smsSender.availableSims()
        val sel  = spinnerSim.selectedItemPosition
        prefs.simSubscriptionId = if (sel == 0 || sel > sims.size) -1 else sims[sel - 1].first

        addLog("Config saved. Starting gateway…")
        showDiagnostics()
        ensureServiceRunning()
        sendServiceAction(GatewayService.ACTION_RECONFIGURE)
    }

    private fun testConnection() {
        val url   = etUrl.text.toString().trim()
        val token = etToken.text.toString().trim()
        val tenant= etTenant.text.toString().trim()
        val gwId  = etGatewayId.text.toString().trim()

        if (url.isEmpty() || token.isEmpty() || tenant.isEmpty() || gwId.isEmpty()) {
            tvLoginStatus.text = "Fill all fields first"
            return
        }
        tvLoginStatus.text = "Testing…"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    SupabaseGatewayClient(
                        GatewayConfig(url.trimEnd('/'), token, tenant, gwId)
                    ).heartbeat(Build.MODEL, null, -1, false, "test", "online")
                }.getOrDefault(false)
            }
            tvLoginStatus.text = if (ok) "✅ Connection OK" else "❌ Failed — check URL and token"
        }
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    private fun showDiagnostics() {
        layoutLogin.visibility = View.GONE
        layoutDiag.visibility  = View.VISIBLE
        updateDiagUI()
        startAutoRefresh()
    }

    private fun startAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) {
                updateDiagUI()
                delay(3_000)
            }
        }
    }

    private fun updateDiagUI() {
        val running = GatewayService.isRunning
        val paused  = GatewayService.isPaused
        val hb      = GatewayService.lastHeartbeat

        tvSvcStatus.text = when {
            paused  -> "⏸  PAUSED"
            running -> "✅  ACTIVE"
            else    -> "❌  STOPPED"
        }
        tvSvcStatus.setTextColor(
            when {
                paused  -> 0xFFFF9800.toInt()
                running -> 0xFF4CAF50.toInt()
                else    -> 0xFFF44336.toInt()
            }
        )
        tvGwInfo.text    = "Gateway: ${prefs.gatewayId}   Tenant: ${prefs.tenantId.take(8)}…"
        tvSimInfo.text   = "SIM: ${if (prefs.simSubscriptionId == -1) "Default" else "Sub-ID ${prefs.simSubscriptionId}"}"
        tvHeartbeat.text = if (hb > 0) "Heartbeat: ${(System.currentTimeMillis() - hb) / 1000}s ago" else "Heartbeat: never"
        tvStats.text     = "Sent today: ${GatewayService.sentToday}  |  Poll: ${prefs.pollIntervalSec}s  |  Status: ${GatewayService.lastStatus}"
        btnToggle.text   = if (paused) "▶  Resume" else "⏸  Pause"
        tvLogs.text      = if (logs.isEmpty()) "(no logs)" else logs.toList().takeLast(50).joinToString("\n")
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun togglePause() {
        if (GatewayService.isPaused) {
            sendServiceAction(GatewayService.ACTION_RESUME)
            addLog("Gateway resumed")
        } else {
            sendServiceAction(GatewayService.ACTION_PAUSE)
            addLog("Gateway paused")
        }
    }

    private fun forceSync() {
        sendServiceAction(GatewayService.ACTION_FORCE_SYNC)
        addLog("Force sync triggered")
    }

    private fun sendTestSms() {
        val phone = etTestPhone.text.toString().trim()
        if (phone.isEmpty()) {
            Toast.makeText(this, "Enter a phone number", Toast.LENGTH_SHORT).show()
            return
        }
        val fakeMsg = SmsMessage(
            id          = "test-${System.currentTimeMillis()}",
            tenantId    = prefs.tenantId,
            gatewayId   = prefs.gatewayId,
            toPhone     = phone,
            body        = "SMSIQ test — ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}",
        )
        val ok = smsSender.send(fakeMsg, prefs.simSubscriptionId)
        addLog(if (ok) "✅ Test SMS dispatched → $phone" else "❌ Test SMS dispatch failed")
    }

    // ── Inbox backfill / contacts sync ──────────────────────────────────────────

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun gwClient() = SupabaseGatewayClient(
        GatewayConfig(prefs.supabaseUrl, prefs.gatewayToken, prefs.tenantId, prefs.gatewayId)
    )

    private fun runBackfill() {
        if (!prefs.isConfigured) {
            Toast.makeText(this, "Najpierw skonfiguruj bramkę", Toast.LENGTH_SHORT).show(); return
        }
        if (!hasPerm(Manifest.permission.READ_SMS)) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS), 101
            )
            Toast.makeText(this, "Przyznaj dostęp do SMS i ponów import", Toast.LENGTH_LONG).show()
            return
        }
        addLog("📥 Import historii SMS — start…")
        scope.launch {
            val res = withContext(Dispatchers.IO) { InboxBackfill.run(applicationContext, gwClient()) }
            addLog("📥 Import: ${res.inserted} nowych / ${res.scanned} przejrzanych")
            Toast.makeText(
                this@MainActivity,
                "Import: ${res.inserted} nowych z ${res.scanned}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun syncContacts() {
        if (!prefs.isConfigured) {
            Toast.makeText(this, "Najpierw skonfiguruj bramkę", Toast.LENGTH_SHORT).show(); return
        }
        if (!hasPerm(Manifest.permission.READ_CONTACTS)) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.READ_CONTACTS), 102
            )
            Toast.makeText(this, "Przyznaj dostęp do kontaktów i ponów", Toast.LENGTH_LONG).show()
            return
        }
        addLog("👥 Synchronizacja kontaktów…")
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                val contacts = ContactsHelper.readAll(applicationContext).map {
                    mapOf<String, Any?>("phone_e164" to it.first, "display_name" to it.second)
                }
                gwClient().syncContacts(contacts)
            }
            addLog("👥 Kontakty zsynchronizowane: $n")
            Toast.makeText(this@MainActivity, "Kontakty: $n", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Disconnect gateway?")
            .setMessage("This will stop the service and clear all saved credentials.")
            .setPositiveButton("Disconnect") { _, _ -> logout() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun logout() {
        stopService(Intent(this, GatewayService::class.java))
        prefs.clear()
        addLog("Gateway disconnected")
        showLogin()
    }

    // ── Service helpers ───────────────────────────────────────────────────────

    private fun ensureServiceRunning() {
        val intent = Intent(this, GatewayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, GatewayService::class.java).apply { this.action = action }
        startService(intent)
    }

    // ── SIM spinner ───────────────────────────────────────────────────────────

    private fun populateSimSpinner() {
        val sims  = smsSender.availableSims()
        val items = mutableListOf("Default SIM") + sims.map { it.second }
        spinnerSim.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun requestMissingPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    private fun askBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
        }
    }

    // ── Log helper ────────────────────────────────────────────────────────────

    private fun addLog(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs.addLast("[$ts] $msg")
        if (logs.size > 200) logs.removeFirst()
    }
}
