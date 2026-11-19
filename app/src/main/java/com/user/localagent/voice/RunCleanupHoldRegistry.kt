package com.user.localagent.voice

import java.io.Closeable
import java.util.UUID

/**
 * Keeps an already acknowledged foreground run alive across its terminal-write/cleanup/child handoff.
 * This process-local lease neither starts a service nor grants authority to execute another run.
 */
internal class RunCleanupHoldRegistry(private val elapsedMillis:()->Long) {
    private data class Entry(val token:String,val deadlineAtMillis:Long)
    private val entries=linkedMapOf<String,Entry>()

    @Synchronized fun acquire(runId:String,deadlineAtMillis:Long):Closeable? {
        expire()
        val now=elapsedMillis()
        if(runId.isBlank()||runId.length>128||now<0||deadlineAtMillis<=now||
            deadlineAtMillis-now>MAX_HOLD_MILLIS||runId in entries||entries.size>=MAX_ENTRIES)return null
        val token=UUID.randomUUID().toString()
        entries[runId]=Entry(token,deadlineAtMillis)
        return Closeable {release(runId,token)}
    }

    @Synchronized fun isHeld(runId:String):Boolean {expire();return runId in entries}
    @Synchronized fun clear(runId:String) {entries.remove(runId)}
    @Synchronized fun clear() {entries.clear()}

    @Synchronized private fun release(runId:String,token:String) {
        if(entries[runId]?.token==token)entries.remove(runId)
    }

    private fun expire() {
        val now=elapsedMillis()
        entries.entries.removeAll {it.value.deadlineAtMillis<=now}
    }

    companion object {
        const val MAX_HOLD_MILLIS=30*60_000L
        private const val MAX_ENTRIES=64
    }
}
