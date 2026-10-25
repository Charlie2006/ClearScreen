package com.qingping.app

/** Uses elapsed time, never wall time. Same-app page changes must not extend the deadline. */
class SplashWindow {
    companion object { const val DURATION_MS = 10_000L }
    var packageName: String? = null
        private set
    var startedAt = 0L
        private set
    var generation = 0
        private set

    fun enter(pkg: String, now: Long): Boolean {
        if (packageName == pkg) return false
        packageName = pkg
        startedAt = now
        generation++
        return true
    }

    fun active(now: Long) = packageName != null && now - startedAt in 0 until DURATION_MS

    // Content refreshes/IME events never grant a fresh scan window.
    fun acceptsEvent(pkg: String?, entryEvent: Boolean, now: Long): Boolean =
        (entryEvent && pkg != null && pkg != packageName) ||
            (active(now) && (pkg == null || pkg == packageName))

    fun reset() {
        packageName = null
        generation++
    }
}
