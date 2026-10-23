package app.inkdown.ui.common

import android.text.format.DateUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object Dates {
    private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

    /** "Just now", "5 min ago", "14:05" (today), "Yesterday", "Oct 3" (this year) or "Oct 3, 2025". */
    fun relative(millis: Long, now: Long = System.currentTimeMillis()): String {
        val zone = ZoneId.systemDefault()
        val then = Instant.ofEpochMilli(millis).atZone(zone)
        val today = LocalDate.now(zone)
        val diff = now - millis
        return when {
            diff in 0 until DateUtils.MINUTE_IN_MILLIS -> "Just now"
            diff in 0 until DateUtils.HOUR_IN_MILLIS -> "${diff / DateUtils.MINUTE_IN_MILLIS} min ago"
            then.toLocalDate() == today -> then.format(time)
            then.toLocalDate() == today.minusDays(1) -> "Yesterday"
            then.year == today.year -> then.format(DateTimeFormatter.ofPattern("MMM d"))
            else -> then.format(date)
        }
    }

    fun full(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dateTime)
}
