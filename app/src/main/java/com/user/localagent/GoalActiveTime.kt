package com.user.localagent

import kotlinx.serialization.json.*

/** Monotonic active time of one goal segment. Owner-approval waits are excluded; this never renews a budget. */
internal class GoalActiveClock(private val elapsed:()->Long={android.os.SystemClock.elapsedRealtime()},baselineMs:Long=0) {
    private val baseline=baselineMs.coerceIn(0,GoalSegmentBinding.MAX_DURATION_MS)
    private val start=elapsed()
    private var excluded=0L
    private var waitingSince:Long?=null
    fun activeMs():Long {
        val now=elapsed()
        val waiting=waitingSince?.let {(now-it).coerceAtLeast(0)}?:0L
        return (baseline+(now-start)-excluded-waiting).coerceIn(0,GoalSegmentBinding.MAX_DURATION_MS)
    }
    suspend fun <T> excluding(block:suspend()->T):T {
        if(waitingSince!=null)return block()
        val since=elapsed();waitingSince=since
        try {return block()}
        finally {excluded+=(elapsed()-since).coerceAtLeast(0);waitingSince=null}
    }
}

/**
 * Active ms one finished run spent: its host-written goalActiveMs, otherwise its wall span, otherwise the whole
 * remaining limit. A malformed value charges the whole limit rather than nothing.
 */
internal fun goalRunActiveMs(contextJson:String,startedAt:Long?,finishedAt:Long?):Long {
    val max=GoalSegmentBinding.MAX_DURATION_MS
    val recorded=runCatching {Json.parseToJsonElement(contextJson).jsonObject["goalActiveMs"]}.getOrNull()
    if(recorded!=null) {
        val value=(recorded as? JsonPrimitive)?.takeIf {!it.isString}?.longOrNull
        return if(value!=null&&value in 0..max)value else max
    }
    if(startedAt!=null&&finishedAt!=null&&finishedAt>=startedAt)return (finishedAt-startedAt).coerceAtMost(max)
    return max
}
