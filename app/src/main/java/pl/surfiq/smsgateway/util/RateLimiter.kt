package pl.surfiq.smsgateway.util

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale

/**
 * Rate limiter with a persisted daily counter.
 *
 * - Per-minute window is in-memory only (a process death resets it, which is fine —
 *   the window is 60 s anyway).
 * - Daily counter is persisted in SharedPreferences so it survives process kills
 *   (MIUI) and service restarts. It resets at local midnight (keyed by local date,
 *   not by uptime).
 */
class RateLimiter(
    context: Context,
    private val maxPerMinute: Int = MAX_PER_MINUTE,
    private val maxPerDay:    Int = MAX_PER_DAY,
) {
    companion object {
        const val MAX_PER_MINUTE = 30
        const val MAX_PER_DAY    = 1000

        private const val PREFS_NAME    = "smsiq_ratelimit"
        private const val KEY_DAY_DATE  = "day_date"   // "yyyy-MM-dd" (local)
        private const val KEY_DAY_COUNT = "day_count"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val minuteWindow = LinkedList<Long>()

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Returns today's persisted count, resetting it if the local date rolled over. */
    @Synchronized
    private fun dayCount(): Int {
        val today = today()
        if (prefs.getString(KEY_DAY_DATE, null) != today) {
            prefs.edit().putString(KEY_DAY_DATE, today).putInt(KEY_DAY_COUNT, 0).apply()
            return 0
        }
        return prefs.getInt(KEY_DAY_COUNT, 0)
    }

    @Synchronized
    fun isDayLimitReached(): Boolean = dayCount() >= maxPerDay

    /**
     * Milliseconds until a per-minute slot frees up. 0 = a slot is available now.
     * Callers should suspend-delay for this long instead of requeueing the message
     * (requeueing eats an attempt, which falsely escalates to manual_review).
     */
    @Synchronized
    fun millisUntilMinuteSlot(): Long {
        val now = System.currentTimeMillis()
        minuteWindow.removeAll { now - it > 60_000L }
        if (minuteWindow.size < maxPerMinute) return 0L
        return (minuteWindow.peekFirst() + 60_000L - now).coerceAtLeast(50L)
    }

    @Synchronized
    fun canSend(): Boolean = millisUntilMinuteSlot() == 0L && !isDayLimitReached()

    @Synchronized
    fun record() {
        minuteWindow.add(System.currentTimeMillis())
        val count = dayCount() + 1
        prefs.edit().putString(KEY_DAY_DATE, today()).putInt(KEY_DAY_COUNT, count).apply()
    }

    val countMinute: Int get() = synchronized(this) {
        val now = System.currentTimeMillis()
        minuteWindow.removeAll { now - it > 60_000L }
        minuteWindow.size
    }

    val countDay: Int get() = dayCount()
}
