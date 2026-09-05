package ph.bulig.mesh

/**
 * Injectable time source.
 *
 * Age and TTL rules are time-dependent, so tests need to control the clock
 * rather than sleep. It also makes the engine's dependence on wall-clock time
 * explicit — which matters here, because an offline phone's clock is not
 * trustworthy (see docs/07-offline-sync.md 7.6).
 */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }

        /** A clock the caller advances by hand. */
        fun fixed(startMs: Long = 0L): MutableClock = MutableClock(startMs)
    }
}

class MutableClock(private var nowMs: Long = 0L) : Clock {
    override fun nowMs(): Long = nowMs

    fun advanceBy(millis: Long) {
        nowMs += millis
    }

    fun set(millis: Long) {
        nowMs = millis
    }
}
