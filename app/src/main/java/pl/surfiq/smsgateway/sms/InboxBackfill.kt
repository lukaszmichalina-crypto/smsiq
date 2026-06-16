package pl.surfiq.smsgateway.sms

import android.content.Context
import android.provider.Telephony
import android.util.Log
import pl.surfiq.smsgateway.api.SupabaseGatewayClient
import pl.surfiq.smsgateway.util.ContactsHelper
import pl.surfiq.smsgateway.util.PhoneUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One-shot import of the phone's received-SMS history (content://sms/inbox) into
 * sms_inbox. Paginated and idempotent — dedup_key matches live receipts, so the
 * server upsert (ignoreDuplicates) drops anything already present. Requires READ_SMS.
 */
object InboxBackfill {

    private const val TAG       = "InboxBackfill"
    private const val PAGE_SIZE = 200

    data class Result(val scanned: Int, val inserted: Int, val failedBatches: Int)

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun run(
        context:    Context,
        client:     SupabaseGatewayClient,
        onProgress: (scanned: Int, inserted: Int) -> Unit = { _, _ -> },
    ): Result {
        var scanned  = 0
        var inserted = 0
        var failed   = 0

        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
        )

        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, projection, null, null,
                "${Telephony.Sms.DATE} DESC",
            )?.use { c ->
                val addrIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)

                val batch = ArrayList<Map<String, Any?>>(PAGE_SIZE)
                while (c.moveToNext()) {
                    val phone = c.getString(addrIdx) ?: continue
                    val body  = c.getString(bodyIdx) ?: continue
                    val date  = c.getLong(dateIdx)
                    scanned++

                    batch.add(
                        mapOf(
                            "from_phone"   to PhoneUtil.normalize(phone),
                            "body"         to body,
                            "received_at"  to iso.format(Date(date)),
                            "dedup_key"    to PhoneUtil.dedupKey(phone, date, body),
                            "source"       to "backfill",
                            "contact_name" to ContactsHelper.nameFor(context, phone),
                        )
                    )

                    if (batch.size >= PAGE_SIZE) {
                        val n = client.pushInbox(batch)
                        if (n < 0) failed++ else inserted += n
                        batch.clear()
                        onProgress(scanned, inserted)
                    }
                }
                if (batch.isNotEmpty()) {
                    val n = client.pushInbox(batch)
                    if (n < 0) failed++ else inserted += n
                    onProgress(scanned, inserted)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "backfill error: ${e.message}")
        }

        Log.i(TAG, "backfill done: scanned=$scanned inserted=$inserted failedBatches=$failed")
        return Result(scanned, inserted, failed)
    }
}
