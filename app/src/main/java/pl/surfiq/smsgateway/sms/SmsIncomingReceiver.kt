package pl.surfiq.smsgateway.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import pl.surfiq.smsgateway.api.SupabaseGatewayClient
import pl.surfiq.smsgateway.model.GatewayConfig
import pl.surfiq.smsgateway.util.ContactsHelper
import pl.surfiq.smsgateway.util.PhoneUtil
import pl.surfiq.smsgateway.util.PrefsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Listens for incoming SMS and:
 *   1) syncs every message to sms_inbox (two-way messaging — replies show up in the panel),
 *   2) keeps the legacy STOP/opt-out report to the VPS.
 */
class SmsIncomingReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsIncomingReceiver"
        private val STOP_KEYWORDS = setOf("stop", "zatrzymaj", "rezygnuje", "rezygnuję", "wypisz")
        private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    private val http  = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Group multi-part SMS by sender, keeping the earliest timestamp.
        val bodies = mutableMapOf<String, StringBuilder>()
        val stamps = mutableMapOf<String, Long>()
        for (msg in messages) {
            val sender = msg.originatingAddress ?: continue
            bodies.getOrPut(sender) { StringBuilder() }.append(msg.messageBody)
            val ts = msg.timestampMillis
            stamps[sender] = minOf(stamps[sender] ?: ts, ts)
        }

        val prefs = PrefsManager(context)
        if (!prefs.isConfigured) return
        val appContext = context.applicationContext

        for ((sender, sb) in bodies) {
            val fullBody = sb.toString()
            val ts       = stamps[sender] ?: System.currentTimeMillis()
            val lower    = fullBody.trim().lowercase()

            // 1) Inbox sync (every message)
            syncToInbox(appContext, prefs, sender, fullBody, ts)

            // 2) STOP / opt-out (legacy VPS report)
            if (STOP_KEYWORDS.any { lower == it || lower.startsWith("$it ") || lower.startsWith("$it\n") }) {
                Log.i(TAG, "STOP received from $sender — reporting opt-out")
                reportOptOut(sender, prefs)
            }
        }
    }

    private fun syncToInbox(context: Context, prefs: PrefsManager, sender: String, body: String, ts: Long) {
        scope.launch {
            try {
                val client = SupabaseGatewayClient(
                    GatewayConfig(
                        supabaseUrl  = prefs.supabaseUrl,
                        gatewayToken = prefs.gatewayToken,
                        tenantId     = prefs.tenantId,
                        gatewayId    = prefs.gatewayId,
                    )
                )
                val n = client.pushInbox(
                    listOf(
                        mapOf(
                            "from_phone"   to PhoneUtil.normalize(sender),
                            "body"         to body,
                            "received_at"  to ISO.format(Date(ts)),
                            "dedup_key"    to PhoneUtil.dedupKey(sender, ts, body),
                            "source"       to "live",
                            "contact_name" to ContactsHelper.nameFor(context, sender),
                        )
                    )
                )
                Log.i(TAG, "Inbox sync from $sender → inserted=$n")
            } catch (e: Exception) {
                Log.e(TAG, "Inbox sync error for $sender: ${e.message}")
            }
        }
    }

    private fun reportOptOut(phone: String, prefs: PrefsManager) {
        scope.launch {
            try {
                val payload = JSONObject().apply {
                    put("phoneNumber", phone)
                    put("message", "STOP")
                }
                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("${prefs.vpsBaseUrl}/sms/incoming")
                    .post(body)
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) Log.i(TAG, "Opt-out recorded for $phone")
                    else Log.w(TAG, "Opt-out failed for $phone: ${resp.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Opt-out error for $phone: ${e.message}")
            }
        }
    }
}
