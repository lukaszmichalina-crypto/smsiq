package pl.surfiq.smsgateway.util

import java.util.LinkedList

class RateLimiter(
    private val maxPerMinute: Int = 30,
    private val maxPerDay:    Int = 200,
) {
    private val minuteWindow = LinkedList<Long>()
    private val dayWindow    = LinkedList<Long>()

    @Synchronized
    fun canSend(): Boolean {
        val now = System.currentTimeMillis()
        minuteWindow.removeAll { now - it > 60_000L }
        dayWindow.removeAll    { now - it > 86_400_000L }
        return minuteWindow.size < maxPerMinute && dayWindow.size < maxPerDay
    }

    @Synchronized
    fun record() {
        val now = System.currentTimeMillis()
        minuteWindow.add(now)
        dayWindow.add(now)
    }

    val countMinute: Int get() = minuteWindow.size
    val countDay:    Int get() = dayWindow.size
}
