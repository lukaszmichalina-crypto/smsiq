package pl.surfiq.smsgateway.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import pl.surfiq.smsgateway.api.SupabaseGatewayClient
import pl.surfiq.smsgateway.model.GatewayConfig
import pl.surfiq.smsgateway.util.PrefsManager
import pl.surfiq.smsgateway.util.RetryManager

/**
 * Receives SENT and DELIVERED callbacks from Android SmsManager.
 * Updates Supabase via Edge Function on IO thread.
 */
class SmsStatusReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsStatusReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val smsId       = intent.getStringExtra(SmsSender.EXTRA_SMS_ID) ?: return
        val partIndex   = intent.getIntExtra(SmsSender.EXTRA_PART_INDEX, 0)
        val totalParts  = intent.getIntExtra(SmsSender.EXTRA_TOTAL_PARTS, 1)
        val attempts    = intent.getIntExtra(SmsSender.EXTRA_ATTEMPTS, 1)
        val maxAttempts = intent.getIntExtra(SmsSender.EXTRA_MAX_ATTEMPTS, 5)

        when (intent.action) {
            SmsSender.ACTION_SMS_SENT      -> handleSent(context, smsId, partIndex, totalParts, resultCode, attempts, maxAttempts)
            SmsSender.ACTION_SMS_DELIVERED -> handleDelivered(context, smsId)
        }
    }

    private fun handleSent(
        context:     Context,
        smsId:       String,
        partIndex:   Int,
        totalParts:  Int,
        resultCode:  Int,
        attempts:    Int,
        maxAttempts: Int,
    ) {
        // For multipart: only update status on last part confirmation
        if (partIndex < totalParts - 1) {
            Log.d(TAG, "Part $partIndex/$totalParts OK for $smsId")
            return
        }

        val (status, errorMsg) = when (resultCode) {
            Activity.RESULT_OK                       -> Pair("sent",          null)
            SmsManager.RESULT_ERROR_GENERIC_FAILURE  -> Pair("retrying",      "Generic failure")
            SmsManager.RESULT_ERROR_NO_SERVICE       -> Pair("no_signal",     "No service")
            SmsManager.RESULT_ERROR_NULL_PDU          -> Pair("failed",        "Null PDU")
            SmsManager.RESULT_ERROR_RADIO_OFF        -> Pair("no_signal",     "Radio off")
            else                                     -> Pair("retrying",      "Error code $resultCode")
        }

        // Escalate to manual_review if attempts exhausted
        val finalStatus = if (status != "sent" && attempts >= maxAttempts) "manual_review" else status
        val scheduledAt = if (finalStatus == "retrying" || finalStatus == "no_signal") {
            RetryManager.nextScheduledIso(attempts)
        } else null

        Log.d(TAG, "SMS $smsId result: $finalStatus (code=$resultCode, attempt=$attempts/$maxAttempts)")
        reportToSupabase(context, smsId, finalStatus, errorMsg, scheduledAt)
    }

    private fun handleDelivered(context: Context, smsId: String) {
        Log.d(TAG, "SMS $smsId delivered")
        reportToSupabase(context, smsId, "delivered", null, null)
    }

    private fun reportToSupabase(
        context:     Context,
        smsId:       String,
        status:      String,
        errorMsg:    String?,
        scheduledAt: String?,
    ) {
        val prefs = PrefsManager(context)
        if (!prefs.isConfigured) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = SupabaseGatewayClient(
                    GatewayConfig(
                        supabaseUrl  = prefs.supabaseUrl,
                        gatewayToken = prefs.gatewayToken,
                        tenantId     = prefs.tenantId,
                        gatewayId    = prefs.gatewayId,
                    )
                )
                client.updateStatus(smsId, status, errorMsg, scheduledAt)
                if (status != "sent" && status != "delivered") {
                    client.log("warning", "SMS $smsId → $status${errorMsg?.let { ": $it" } ?: ""}", smsId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "reportToSupabase failed: ${e.message}")
            }
        }
    }
}
