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
import pl.surfiq.smsgateway.util.PrefsManager

/**
 * Listens for incoming SMS. If body is "STOP" (case-insensitive, trimmed),
 * reports the sender's number to VPS which sets sms_opt_out = TRUE in Supabase.
 */
class SmsIncomingReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsIncomingReceiver"
        private val STOP_KEYWORDS = setOf("stop", "zatrzymaj", "rezygnuje", "rezygnuję", "wypisz")
    }

    private val http = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Group multi-part SMS by sender
        val bySender = mutableMapOf<String, StringBuilder>()
        for (msg in messages) {
            val sender = msg.originatingAddress ?: continue
            bySender.getOrPut(sender) { StringBuilder() }.append(msg.messageBody)
        }

        val prefs = PrefsManager(context)
        if (!prefs.isConfigured) return

        for ((sender, bodyBuilder) in bySender) {
            val body = bodyBuilder.toString().trim().lowercase()
            if (STOP_KEYWORDS.any { body == it || body.startsWith("$it ") || body.startsWith("$it\n") }) {
                Log.i(TAG, "STOP received from $sender — reporting opt-out")
                reportOptOut(sender, prefs)
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
                val resp = http.newCall(req).execute()
                if (resp.isSuccessful) {
                    Log.i(TAG, "Opt-out recorded for $phone")
                } else {
                    Log.w(TAG, "Opt-out failed for $phone: ${resp.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Opt-out error for $phone: ${e.message}")
            }
        }
    }
}
