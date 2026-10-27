package com.example.g2glasses.glassesui

/**
 * Decides when a [GlassesPage] actually goes to the glasses.
 *
 * Every page costs a Bluetooth write to the G2, and the display can take a text update only every
 * few hundred milliseconds, while live speech (partials) changes many times a second. So:
 *
 * - a page identical to the last one sent is dropped;
 * - urgent pages (a finished caption, a clear) are sent at once and replace anything waiting;
 * - other pages (live partials) are sent at most once per [minIntervalMs]. While one waits, a newer
 *   page replaces it, and the newest is sent when the interval is over.
 *
 * No Android or Bluetooth code: time and timers come from [clock] and [scheduler], so it is unit
 * tested with a fake clock. Not thread-safe; call it from one thread (the main thread in the app).
 */
class RenderGovernor(
    private val clock: () -> Long,
    private val scheduler: Scheduler,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val display: (GlassesPage) -> Unit,
) {
    /** Runs [action] once after [delayMs]. */
    fun interface Scheduler {
        fun schedule(delayMs: Long, action: () -> Unit): Cancellable
    }

    fun interface Cancellable {
        fun cancel()
    }

    data class Stats(
        val submitted: Int = 0,
        /** Pages handed to the glasses. */
        val sent: Int = 0,
        /** Pages dropped because the glasses already show them. */
        val unchanged: Int = 0,
        /** Pages replaced by a newer one before their turn came. */
        val merged: Int = 0,
    )

    var stats = Stats()
        private set

    /** Called after every change to [stats], e.g. to show them on the Glasses tab. */
    var onStats: ((Stats) -> Unit)? = null

    private var lastSent: GlassesPage? = null
    private var lastSentAt: Long? = null
    private var pending: GlassesPage? = null
    private var timer: Cancellable? = null

    fun submit(page: GlassesPage, urgent: Boolean = false) {
        update { it.copy(submitted = it.submitted + 1) }
        if (urgent) {
            if (pending != null) update { it.copy(merged = it.merged + 1) }
            cancelPending()
            send(page)
            return
        }
        if (pending == null && page == lastSent) {
            update { it.copy(unchanged = it.unchanged + 1) }
            return
        }
        val wait = lastSentAt?.let { it + minIntervalMs - clock() } ?: 0L
        if (pending == null && wait <= 0) {
            send(page)
            return
        }
        if (pending != null) update { it.copy(merged = it.merged + 1) }
        pending = page
        if (timer == null) {
            timer = scheduler.schedule(wait.coerceAtLeast(0)) {
                timer = null
                pending?.let { pending = null; send(it) }
            }
        }
    }

    /**
     * Forgets what the glasses show, for when something else drew on them (a test message): the
     * next page is sent even if it equals the last one sent here.
     */
    fun forgetShown() {
        lastSent = null
    }

    /** Drops anything waiting; for teardown. */
    fun cancelPending() {
        timer?.cancel()
        timer = null
        pending = null
    }

    private fun send(page: GlassesPage) {
        if (page == lastSent) {
            update { it.copy(unchanged = it.unchanged + 1) }
            return
        }
        display(page)
        lastSent = page
        lastSentAt = clock()
        update { it.copy(sent = it.sent + 1) }
    }

    private inline fun update(change: (Stats) -> Stats) {
        stats = change(stats)
        onStats?.invoke(stats)
    }

    companion object {
        /** Live partials at most ~3 times a second: what the G2 keeps up with over BLE. */
        const val DEFAULT_MIN_INTERVAL_MS = 300L
    }
}
