package com.user.localagent.scheduling

import com.user.localagent.core.GoalWaitRecord
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Atomic read/replace under a process-wide lock; the backing store encrypts the records. */
internal class GoalWaitStore(private val load: () -> String?, private val save: (String) -> Unit) {
    private val codec = ListSerializer(GoalWaitRecord.serializer())

    private fun read(): List<GoalWaitRecord> =
        load()?.let { Json.decodeFromString(codec, it) }.orEmpty()

    fun all(): List<GoalWaitRecord> = synchronized(lock) { read() }

    fun get(goalId: String): GoalWaitRecord? = all().singleOrNull { it.goalId == goalId }

    fun replace(record: GoalWaitRecord, expected: GoalWaitRecord?): Boolean = synchronized(lock) {
        val records = read()
        if (records.singleOrNull { it.goalId == record.goalId } != expected) return false
        val next = records.filterNot { it.goalId == record.goalId } + record
        require(next.size <= 256)
        save(Json.encodeToString(codec, next))
        true
    }

    fun remove(expected: GoalWaitRecord): Boolean = synchronized(lock) {
        val records=read()
        if(records.singleOrNull{it.goalId==expected.goalId}!=expected)return false
        save(Json.encodeToString(codec, records.filterNot{it.goalId==expected.goalId}))
        true
    }

    private companion object { val lock = Any() }
}
