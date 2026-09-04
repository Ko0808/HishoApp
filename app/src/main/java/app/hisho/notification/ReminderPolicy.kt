package app.hisho.notification

/** Pure timing rules shared by the worker and regression tests. */
object ReminderPolicy {
    fun shouldDeliver(now: Long, start: Long, end: Long, prepare: Boolean, deliveredStart: Long, snoozed: Boolean): Boolean =
        now < end && (!prepare || now < start) && (snoozed || deliveredStart != start)

    fun workName(captureId: Long, blockIndex: Int, phase: String, start: Long): String =
        "execution-reminder-$captureId-$blockIndex-$phase-$start"
}
