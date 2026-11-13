package com.dd3boh.xenowave.backup

/**
 * Single decision point for components that may open Room/DataStore.
 *
 * Services and media controllers can be started by the system or another app
 * without going through [com.dd3boh.xenowave.MainActivity]. They must consult
 * this guard before touching data, not only during their `onCreate`.
 */
object RestoreConsumerGuard {
    fun canOpenData(): Boolean = !RestoreRecoveryGate.isBlocked()

    /**
     * Evaluates [block] only when the gate allows data access. When blocked,
     * returns [defaultValue] without running the block, so a late property
     * initializer cannot open DataStore by accident.
     */
    inline fun <T> ifAllowed(defaultValue: T, block: () -> T): T =
        if (canOpenData()) block() else defaultValue
}
