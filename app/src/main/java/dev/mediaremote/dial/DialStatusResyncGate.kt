package dev.mediaremote.dial

/** A DIAL status poll only signals a sender wake after polling has gone quiet. */
internal class DialStatusResyncGate {
    private val lastPollByAddressMs = mutableMapOf<String, Long>()

    @Synchronized
    fun shouldResync(address: String, nowMs: Long): Boolean {
        val previous = lastPollByAddressMs.put(address, nowMs)
        return previous == null || nowMs - previous >= IDLE_GAP_MS
    }

    companion object {
        private const val IDLE_GAP_MS = 30_000L
    }
}
