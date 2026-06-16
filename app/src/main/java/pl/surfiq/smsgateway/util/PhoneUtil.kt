package pl.surfiq.smsgateway.util

/** Best-effort phone normalisation + a stable per-message fingerprint shared by
 *  live receive and backfill so the same SMS is never stored twice. */
object PhoneUtil {

    /** Keeps a leading '+', strips everything non-digit. Not a full E.164 parser. */
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()
        val digits  = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }

    /** Identical for the live broadcast and the content://sms row of the same message,
     *  because both expose the provider timestamp (millis) and body. */
    fun dedupKey(phone: String, timestampMillis: Long, body: String): String =
        "${normalize(phone)}|$timestampMillis|${body.trim().hashCode()}"
}
