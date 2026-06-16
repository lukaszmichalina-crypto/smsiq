package pl.surfiq.smsgateway.util

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

/** Reads the device address book (requires READ_CONTACTS). All calls are
 *  defensive: a missing permission throws SecurityException which we swallow. */
object ContactsHelper {

    private const val TAG = "ContactsHelper"

    /** Full address book as (phone_e164, display_name), de-duplicated by number. */
    fun readAll(context: Context): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ),
                null, null, null,
            )?.use { c ->
                val numIdx  = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val num  = PhoneUtil.normalize(c.getString(numIdx))
                    val name = c.getString(nameIdx)
                    if (num.isNotEmpty() && !name.isNullOrBlank()) out.add(num to name)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "readAll failed: ${e.message}")
        }
        return out.distinctBy { it.first }
    }

    /** Resolves a single number to a contact name, or null if unknown / no permission. */
    fun nameFor(context: Context, phone: String): String? {
        if (phone.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone)
            )
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }
}
