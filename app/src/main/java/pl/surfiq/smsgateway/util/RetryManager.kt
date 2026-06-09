package pl.surfiq.smsgateway.util

object RetryManager {
    // Backoff delays in seconds: 1min, 3min, 5min, 10min, 30min
    private val BACKOFF_SECONDS = longArrayOf(60, 180, 300, 600, 1800)

    /** Returns delay in seconds for the given attempt number (1-based). */
    fun delaySeconds(attempt: Int): Long {
        val idx = (attempt - 1).coerceIn(0, BACKOFF_SECONDS.size - 1)
        return BACKOFF_SECONDS[idx]
    }

    /** Returns the absolute timestamp (ms) when the next retry should be attempted. */
    fun nextScheduledMs(attempt: Int): Long =
        System.currentTimeMillis() + delaySeconds(attempt) * 1_000

    /** Returns ISO-8601 string for scheduled_at field. */
    fun nextScheduledIso(attempt: Int): String {
        val ms = nextScheduledMs(attempt)
        return java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .also { it.timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(ms))
    }
}
