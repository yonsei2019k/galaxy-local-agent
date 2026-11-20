package com.user.localagent.integrations

import com.user.localagent.core.HarnessStep
import com.user.localagent.core.ObservedToolOutcome
import com.user.localagent.core.ScreenControlToolCatalog
import com.user.localagent.core.ScreenReadEvidence
import com.user.localagent.core.ToolCall
import com.user.localagent.core.VerificationState
import com.user.localagent.core.compactJsonData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import uniffi.ziphap_android.NativeScreenRebindEvidence
import uniffi.ziphap_android.nativeCanRebindScreenTarget

/** Passes exact current-run receipts to the harness while retaining the original failed step. */
internal object ScreenActionRecovery {
    fun boundFailures(
        steps:List<HarnessStep>,
        outcomes:List<ObservedToolOutcome>,
        proposed:ToolCall?=null,
    ):Set<HarnessStep> {
        if(outcomes.size>steps.size||outcomes.none {it.result.verificationSummary==ScreenControlToolCatalog.STALE_SNAPSHOT_RECEIPT})
            return emptySet()
        val current=steps.takeLast(outcomes.size)
        if(current.zip(outcomes).any { (step,outcome)->
            step.tool!=outcome.call.name||step.arguments!=outcome.call.arguments||outcome.result.id!=outcome.call.id
        })return emptySet()
        return current.withIndex().filter { (index,step)->
            step.tool==ScreenControlToolCatalog.ACTION&&step.evidence.state==VerificationState.FAILED&&
                step.evidence.effectNotApplied&&isStale(outcomes[index])&&
                (outcomes.indices.drop(index+1).any { later->
                    val outcome=outcomes[later]
                    outcome.result.success&&outcome.result.verification==VerificationState.VERIFIED&&
                        matches(outcomes,index,later,outcome.call)
                }||proposed?.let {matches(outcomes,index,outcomes.size,it)}==true)
        }.mapTo(mutableSetOf()) {it.value}
    }

    /** Keep a real refreshed ref visible even when ordinary screen previews trim the element list. */
    fun modelRead(outcomes:List<ObservedToolOutcome>,call:ToolCall):String? {
        val current=outcomes.lastOrNull()?.takeIf {it.call==call}?:return null
        val screen=read(current)?:return null
        val rejectedIndex=outcomes.indexOfLast(::isStale)
        if(rejectedIndex<0||outcomes.subList(rejectedIndex+1,outcomes.lastIndex).any {
                it.call.name in ScreenControlToolCatalog.names
            })return null
        val old=originalTarget(outcomes,rejectedIndex)?:return null
        val ref=screen.targets.entries.singleOrNull {it.value==old.targetDigest}?.key?:return null
        val refreshed=screen.target(ref)?:return null
        if(!nativeCanRebindScreenTarget(old.snapshotId,screen.snapshotId,old,refreshed))return null

        val data=runCatching {Json.parseToJsonElement(current.result.content) as? JsonObject}.getOrNull()?:return null
        val elements=data["elements"] as? JsonArray?:return null
        val selected=elements.singleOrNull {(it as? JsonObject)?.get("ref")==JsonPrimitive(ref)}?:return null
        return compactJsonData(JsonObject(data+("elements" to JsonArray(listOf(selected)+elements.filterNot {it==selected}))).toString(),1100)
    }

    private fun matches(outcomes:List<ObservedToolOutcome>,rejectedIndex:Int,nextIndex:Int,next:ToolCall):Boolean {
        val original=outcomes[rejectedIndex].call
        if(next.name!=ScreenControlToolCatalog.ACTION||
            original.arguments.filterKeys {it !in bindingKeys}!=next.arguments.filterKeys {it !in bindingKeys})
            return false
        val newId=next.string("snapshotId")?:return false
        val newRef=next.string("ref")?:return false
        val before=originalTarget(outcomes,rejectedIndex)?:return false
        val after=outcomes.subList(rejectedIndex+1,nextIndex).lastOrNull {it.call.name==ScreenControlToolCatalog.READ}
            ?.let(::read)?.target(newRef)?:return false
        return nativeCanRebindScreenTarget(before.snapshotId,newId,before,after)
    }

    private fun originalTarget(outcomes:List<ObservedToolOutcome>,rejectedIndex:Int):NativeScreenRebindEvidence? {
        val original=outcomes[rejectedIndex].call
        if(original.string("action") !in elementActions)return null
        val snapshot=original.string("snapshotId")?:return null
        val ref=original.string("ref")?:return null
        return outcomes.subList(0,rejectedIndex).asReversed().firstNotNullOfOrNull {item->
            read(item)?.takeIf {it.snapshotId==snapshot}
        }?.target(ref)
    }

    private fun isStale(outcome:ObservedToolOutcome):Boolean =
        outcome.call.name==ScreenControlToolCatalog.ACTION&&outcome.result.id==outcome.call.id&&
            !outcome.result.success&&outcome.result.effectNotApplied&&outcome.result.verification==VerificationState.FAILED&&
            outcome.result.verificationSummary==ScreenControlToolCatalog.STALE_SNAPSHOT_RECEIPT

    private fun read(outcome:ObservedToolOutcome):ScreenReadEvidence? =
        outcome.result.screenReadEvidence.takeIf {
            outcome.call.name==ScreenControlToolCatalog.READ&&outcome.result.id==outcome.call.id&&outcome.result.success&&
                outcome.result.verification==VerificationState.VERIFIED
        }

    private fun ScreenReadEvidence.target(ref:String):NativeScreenRebindEvidence? {
        val digest=targets[ref]?:return null
        if(targets.values.count {it==digest}!=1)return null
        return NativeScreenRebindEvidence(snapshotId,packageDigest,windowId,digest)
    }

    private fun ToolCall.string(key:String):String?=(arguments[key] as? JsonPrimitive)?.takeIf {it.isString}?.content
    private val bindingKeys=setOf("snapshotId","ref")
    private val elementActions=setOf("tap","type","scroll")
}
