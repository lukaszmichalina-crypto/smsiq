package pl.surfiq.smsgateway.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import pl.surfiq.smsgateway.model.SmsMessage

class SmsSender(private val context: Context) {

    companion object {
        private const val TAG = "SmsSender"
        const val ACTION_SMS_SENT      = "pl.surfiq.smsgateway.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "pl.surfiq.smsgateway.SMS_DELIVERED"
        const val EXTRA_SMS_ID         = "sms_id"
        const val EXTRA_PART_INDEX     = "part_index"
        const val EXTRA_TOTAL_PARTS    = "total_parts"
        const val EXTRA_ATTEMPTS       = "attempts"
        const val EXTRA_MAX_ATTEMPTS   = "max_attempts"
    }

    /**
     * Dispatches an SMS to Android SmsManager.
     * Returns true if dispatched (not necessarily sent — delivery status arrives via callback).
     */
    fun send(msg: SmsMessage, subscriptionId: Int = -1): Boolean {
        return try {
            val mgr = getSmsManager(subscriptionId)
            val parts = mgr.divideMessage(msg.body)
            if (parts.size == 1) {
                sendSingle(mgr, msg)
            } else {
                sendMultipart(mgr, msg, parts)
            }
            Log.d(TAG, "Dispatched ${msg.id} to ${msg.toPhone} (${parts.size} part(s))")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Dispatch failed for ${msg.id}: ${e.message}")
            false
        }
    }

    private fun getSmsManager(subscriptionId: Int): SmsManager {
        return if (subscriptionId != -1) {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        } else {
            SmsManager.getDefault()
        }
    }

    private fun sentIntent(msg: SmsMessage, partIndex: Int, totalParts: Int): PendingIntent {
        val intent = Intent(ACTION_SMS_SENT).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_SMS_ID,       msg.id)
            putExtra(EXTRA_PART_INDEX,   partIndex)
            putExtra(EXTRA_TOTAL_PARTS,  totalParts)
            putExtra(EXTRA_ATTEMPTS,     msg.attempts)
            putExtra(EXTRA_MAX_ATTEMPTS, msg.maxAttempts)
        }
        // Unique request code per SMS+part so intents don't collide
        val reqCode = (msg.id.hashCode() xor (partIndex shl 16)) and Int.MAX_VALUE
        return PendingIntent.getBroadcast(
            context, reqCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun deliveryIntent(msg: SmsMessage): PendingIntent {
        val intent = Intent(ACTION_SMS_DELIVERED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_SMS_ID, msg.id)
        }
        val reqCode = (msg.id.hashCode() xor 0x80000000.toInt()) and Int.MAX_VALUE
        return PendingIntent.getBroadcast(
            context, reqCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun sendSingle(mgr: SmsManager, msg: SmsMessage) {
        mgr.sendTextMessage(
            msg.toPhone, null, msg.body,
            sentIntent(msg, 0, 1),
            deliveryIntent(msg)
        )
    }

    private fun sendMultipart(mgr: SmsManager, msg: SmsMessage, parts: ArrayList<String>) {
        val sentIntents     = ArrayList<PendingIntent>()
        val deliveryIntents = ArrayList<PendingIntent>()
        for (i in parts.indices) {
            sentIntents.add(sentIntent(msg, i, parts.size))
            deliveryIntents.add(deliveryIntent(msg))
        }
        mgr.sendMultipartTextMessage(msg.toPhone, null, parts, sentIntents, deliveryIntents)
    }

    /** Returns list of (subscriptionId, label) for all active SIMs. */
    @Suppress("MissingPermission")
    fun availableSims(): List<Pair<Int, String>> {
        val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as? android.telephony.SubscriptionManager ?: return emptyList()
        return try {
            sm.activeSubscriptionInfoList?.map { info ->
                Pair(info.subscriptionId, "SIM ${info.simSlotIndex + 1}: ${info.displayName}")
            } ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }
}
