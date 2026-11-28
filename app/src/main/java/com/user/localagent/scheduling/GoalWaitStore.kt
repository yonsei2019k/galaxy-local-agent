package com.user.localagent.scheduling

import com.user.localagent.core.GoalWaitRecord
import com.user.localagent.core.GoalWaitStopReason
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Atomic read/replace under a process-wide lock; the backing store encrypts the records. */
internal class GoalWaitStore(private val load: () -> String?, private val save: (String) -> Unit) {
    private val codec = ListSerializer(GoalWaitRecord.serializer())

    private fun read(): List<GoalWaitRecord> =
        load()?.let { Json.decodeFromString(codec, it) }.orEmpty()

    fun all(): List<GoalWaitRecord> = synchronized(lock) { read() }

    fun get(goalId: String): GoalWaitRecord? = all().singleOrNull { it.goalId == goalId }
    fun ownerStoppedParent(goalId:String,parentRunId:String,threadId:String,branchId:String):Boolean =
        get(goalId)?.let {it.stopReason==GoalWaitStopReason.OWNER_CANCELLED&&it.parentRunId==parentRunId&&
            it.threadId==threadId&&it.branchId==branchId}==true

    fun atCapacity(): Boolean = synchronized(lock) { read().size >= MAX_RECORDS }

    fun markOwnerStopped(expected:GoalWaitRecord,newId:String):Pair<GoalWaitRecord,GoalWaitRecord>? = synchronized(lock) {
        val records=read()
        val current=records.singleOrNull {it.goalId==expected.goalId}?:return null
        if(current.ordinaryBrowserAuth||current.threadId!=expected.threadId||current.branchId!=expected.branchId||
            current.parentRunId!=expected.parentRunId||current.resumedOnceFor==current.id)return null
        if(current.stopReason!=null)return current to current
        val stopping=current.copy(id=newId,stopReason=GoalWaitStopReason.OWNER_CANCELLED)
        save(Json.encodeToString(codec,records.map {if(it==current)stopping else it}))
        current to stopping
    }

    fun replace(record: GoalWaitRecord, expected: GoalWaitRecord?, reclaim: Set<GoalWaitRecord> = emptySet()): Boolean = synchronized(lock) {
        val records = read()
        if (records.singleOrNull { it.goalId == record.goalId } != expected) return false
        val next = records.filterNot { it.goalId == record.goalId || it in reclaim } + record
        if(next.size>MAX_RECORDS)return false
        save(Json.encodeToString(codec, next))
        true
    }

    fun remove(expected: GoalWaitRecord): Boolean = synchronized(lock) {
        val records=read()
        if(records.singleOrNull{it.goalId==expected.goalId}!=expected)return false
        save(Json.encodeToString(codec, records.filterNot{it.goalId==expected.goalId}))
        true
    }

    private companion object {
        const val MAX_RECORDS = 256
        val lock = Any()
    }
}
