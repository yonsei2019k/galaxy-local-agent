package com.user.localagent.externalagent

import java.util.UUID
import uniffi.ziphap_android.nativeContextPartner
import uniffi.ziphap_android.nativeOlderTurnByteLimits
import kotlinx.serialization.json.*

/**
 * Bounded historical data, never owner instructions, approval, tool authority or proof of a new effect.
 * Callers obtain DTOs from the current database branch and explicitly confirm every source binding.
 * This helper does not fetch records, classify natural language, modify the current request or execute work.
 */
object CloudConversationContext {
    const val MAX_TURNS=8
    const val MAX_TURN_CHARS=1500
    const val MAX_OWNER_CHARS=3000
    const val MAX_GOAL_CHARS=1500
    const val MAX_UTF8_BYTES=12_000
    private const val OMITTED="\n…(이전 문맥 일부 생략)…\n"
    private const val MIN_TURN_CHARS=160
    private const val MIN_OWNER_CHARS=128
    private const val MIN_GOAL_CHARS=64
    private val statuses=setOf("completed","paused","failed","cancelled","interrupted")

    /** Run identity differs between turns; all remaining fields must equal the current capture exactly. */
    data class Binding(
        val runId:String,val threadId:String,val branchId:String,val projectId:String?,
        val authorizationRevision:Long?,val pcRevision:Long?,val modelId:String,
        val allowExternalAgent:Boolean,val mode:String,
    )
    data class Turn(
        val binding:Binding,val role:String,val text:String,
        val sourceConfirmed:Boolean,val deleted:Boolean=false,
    )
    data class PreviousTask(
        val binding:Binding,val status:String,val ownerRequest:String,val goal:String,
        /** A missing/uncertain host answer is kept as true, never converted into completion. */
        val unresolvedEffects:Boolean?,val sourceConfirmed:Boolean,val deleted:Boolean=false,
    )

    /** Latest eligible dialogue precedes old task prose. No semantic summary or new memory is invented. */
    fun build(current:Binding,recentTurns:List<Turn>,previousTask:PreviousTask?=null):JsonObject? {
        if(!cloudCapture(current))return null
        val task=previousTask?.takeIf {
            it.sourceConfirmed&&!it.deleted&&it.status in statuses&&eligible(current,it.binding)
        }
        val eligibleTurns=recentTurns.filter {
            it.sourceConfirmed&&!it.deleted&&it.role in setOf("user","assistant")&&
                it.text.isNotBlank()&&eligible(current,it.binding)
        }
        if(task==null&&eligibleTurns.isEmpty())return null
        val selected=sortedMapOf<Int,JsonObject>()
        var ownerLimit=MIN_OWNER_CHARS
        var goalLimit=MIN_GOAL_CHARS
        fun taskValue()=task?.let{taskJson(it,ownerLimit,goalLimit)}
        fun fits(turns:List<JsonObject>,owner:Int=ownerLimit,goal:Int=goalLimit)=
            bytes(envelope(turns,task?.let{taskJson(it,owner,goal)}))<=MAX_UTF8_BYTES

        if(eligibleTurns.isNotEmpty()){
            val newest=eligibleTurns.lastIndex
            val latestRun = eligibleTurns[newest].binding.runId
            val returningToPausedRequest = task?.binding?.runId == latestRun &&
                (task.status == "paused" || task.status == "interrupted")
            val partner = if (newest == 0) null else nativeContextPartner(
                eligibleTurns.map { it.role == "user" },
                eligibleTurns.map { it.binding.runId == latestRun },
                returningToPausedRequest,
            )?.toInt()
            val priority=listOfNotNull(partner,newest).sorted()
            // Keep the latest row at its wire limit for an adjacent owner pair, a same-run reply,
            // or an owner correction paired with an earlier reply from another run.
            val earlier = partner?.let { eligibleTurns[it] }
            val newestIsOwner = eligibleTurns[newest].role == "user"
            val prioritizeNewest = earlier != null && (
                (earlier.role == "user" && (newestIsOwner || earlier.binding.runId == latestRun)) ||
                (earlier.role == "assistant" && newestIsOwner && earlier.binding.runId != latestRun)
            )
            fun priorityRow(index: Int, limit: Int) = turnJson(eligibleTurns[index],
                if (prioritizeNewest && index == newest) MAX_TURN_CHARS else limit)
            // One maximally escaped row plus the partner and task minima fits.
            val turnLimit=largestFitting(MIN_TURN_CHARS,MAX_TURN_CHARS){limit->
                fits(priority.map{priorityRow(it,limit)})
            }
            priority.forEach{selected[it]=priorityRow(it,turnLimit)}
            val older = eligibleTurns.indices.reversed().asSequence()
                .filterNot(selected::containsKey)
                .take(MAX_TURNS - selected.size)
                .map { index -> index to turnJson(eligibleTurns[index], MAX_TURN_CHARS) }
                .toList()
            if (older.isNotEmpty()) {
                val costs = older.map { (_, row) -> bytes(row).toUInt() }
                val limits = nativeOlderTurnByteLimits(
                    bytes(envelope(selected.values.toList(), taskValue())).toUInt(),
                    selected.size.toUInt(), costs,
                )
                for ((position, limit) in limits.withIndex()) {
                    val (index, full) = older[position]
                    if (costs[position] <= limit) {
                        selected[index] = full
                    } else {
                        // The boundary row alone may be shortened. Keep both ends and its original source.
                        val turn = eligibleTurns[index]
                        fun rowFits(chars: Int) = bytes(turnJson(turn, chars)).toUInt() <= limit
                        if (rowFits(MIN_TURN_CHARS)) {
                            val chars = largestFitting(MIN_TURN_CHARS, MAX_TURN_CHARS, ::rowFits)
                            selected[index] = turnJson(turn, chars)
                        }
                        break
                    }
                }
            }
        }
        // Historical task prose uses only the remaining budget; its metadata and uncertainty never disappear.
        if(task!=null){
            ownerLimit=largestFitting(MIN_OWNER_CHARS,MAX_OWNER_CHARS){fits(selected.values.toList(),owner=it)}
            goalLimit=largestFitting(MIN_GOAL_CHARS,MAX_GOAL_CHARS){fits(selected.values.toList(),goal=it)}
        }
        return envelope(selected.values.toList(),taskValue()).also {check(bytes(it)<=MAX_UTF8_BYTES)}
    }

    private fun cloudCapture(binding:Binding):Boolean =
        uuid(binding.runId)&&binding.threadId.isNotBlank()&&binding.branchId.isNotBlank()&&
            (binding.projectId==null||binding.projectId.isNotBlank())&&
            binding.authorizationRevision!=null&&binding.authorizationRevision>=0&&
            binding.pcRevision!=null&&binding.pcRevision>=0&&
            binding.modelId==ExternalAgentSettings.MODEL_ID&&binding.allowExternalAgent&&binding.mode in setOf("act","debug")

    internal fun isEligible(current:Binding,source:Binding):Boolean = cloudCapture(current)&&eligible(current,source)

    private fun eligible(current:Binding,source:Binding):Boolean = cloudCapture(source)&&
        source.runId!=current.runId&&source.threadId==current.threadId&&source.branchId==current.branchId&&
            source.projectId==current.projectId&&source.authorizationRevision==current.authorizationRevision&&
            source.pcRevision==current.pcRevision&&source.modelId==current.modelId&&
            source.allowExternalAgent==current.allowExternalAgent&&source.mode==current.mode

    private fun uuid(value:String):Boolean=runCatching{UUID.fromString(value).toString()==value}.getOrDefault(false)
    private fun bytes(value:JsonObject)=value.toString().toByteArray(Charsets.UTF_8).size
    private fun envelope(turns:List<JsonObject>,task:JsonObject?)=buildJsonObject {
        put("version",1);put("recentTurns",JsonArray(turns));put("previousTask",task?:JsonNull)
    }

    private fun turnJson(turn:Turn,maximum:Int)=buildJsonObject {
        put("role",turn.role);put("text",clip(turn.text,maximum));put("sourceRunId",turn.binding.runId)
    }
    private fun taskJson(task:PreviousTask,ownerLimit:Int,goalLimit:Int)=buildJsonObject {
            put("sourceRunId",task.binding.runId);put("status",task.status)
            put("ownerRequest",clip(task.ownerRequest,ownerLimit));put("goal",clip(task.goal,goalLimit))
            put("unresolvedEffects",task.unresolvedEffects!=false)
    }

    private fun largestFitting(minimum:Int,maximum:Int,fits:(Int)->Boolean):Int {
        check(fits(minimum))
        if(fits(maximum))return maximum
        var low=minimum;var high=maximum
        while(low<high){val middle=(low+high+1)/2;if(fits(middle))low=middle else high=middle-1}
        return low
    }

    /** Literal head AND tail excerpts retain late corrections without inferring which text matters. */
    private fun clip(value:String,maximum:Int):String {
        if(maximum==0)return ""
        if(value.length<=maximum)return value
        val marker=if(maximum>=OMITTED.length)OMITTED else "…"
        val available=(maximum-marker.length).coerceAtLeast(0)
        var headEnd=available/2
        var tailStart=value.length-(available-headEnd)
        if(headEnd>0&&value[headEnd-1].isHighSurrogate()&&value[headEnd].isLowSurrogate())headEnd--
        if(tailStart>0&&tailStart<value.length&&value[tailStart].isLowSurrogate()&&value[tailStart-1].isHighSurrogate())tailStart++
        return value.substring(0,headEnd)+marker+value.substring(tailStart)
    }
}
