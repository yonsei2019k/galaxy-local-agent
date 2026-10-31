package com.user.localagent

/** Room ordering is independent of model routing. Resource leases separately limit PC/phone to one each. */
internal object ChatWorkerAdmission {
    const val MAX_ACTIVE = 2
    fun nextRunnableIndex(queuedThreadIds: List<String>, activeThreadIds: Collection<String>): Int? {
        if (activeThreadIds.size >= MAX_ACTIVE) return null
        return queuedThreadIds.indexOfFirst { it !in activeThreadIds }.takeIf { it >= 0 }
    }
}
