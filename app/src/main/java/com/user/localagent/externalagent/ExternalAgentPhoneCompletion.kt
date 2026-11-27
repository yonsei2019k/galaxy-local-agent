package com.user.localagent.externalagent

import kotlinx.serialization.json.*
import uniffi.ziphap_android.NativePhoneObservation
import uniffi.ziphap_android.NativePhoneReceipt
import uniffi.ziphap_android.NativePhoneVerification
import uniffi.ziphap_android.nativePhoneCompletionProven

/** Joins broker metadata to actual local callbacks. Server phone results are never evidence. */
object ExternalAgentPhoneCompletion {
    fun matches(capture:ExternalAgentCapture,plan:ExternalAgentPlan,evidence:JsonObject,actual:List<PhoneAgentExecution>,
        expectedSessionId:String,publicPartial:Boolean=false):Boolean=
        checkEvidence(capture,plan,evidence,actual,expectedSessionId,publicPartial,false)

    /** This path can only produce a progress notice; it never proves task completion. */
    fun browserReadsMatch(capture:ExternalAgentCapture,plan:ExternalAgentPlan,evidence:JsonObject,
        actual:List<PhoneAgentExecution>,sessionId:String):Boolean=
        checkEvidence(capture,plan,evidence,actual,sessionId,false,true)

    private fun checkEvidence(capture:ExternalAgentCapture,plan:ExternalAgentPlan,evidence:JsonObject,actual:List<PhoneAgentExecution>,
        expectedSessionId:String,publicPartial:Boolean,browserReadsOnly:Boolean):Boolean=runCatching {
        require(capture.requirePlan&&capture.pcWorkspaceProfile=="phone-only")
        fun text(key:String)=ExternalAgentContract.text(evidence,key)
        require(text("sessionId")==expectedSessionId&&ExternalAgentContract.long(evidence,"revision")>0)
        require(text("parentRunId")==capture.parentRunId&&text("projectBinding")==capture.projectBinding&&
            text("requestDigest")==capture.requestDigest&&ExternalAgentContract.long(evidence,"authorityRevision")==capture.authorityRevision)
        require(text("state")=="ACTIVE"&&text("restartPolicy")=="RAM_ONLY_RESTART_REVOKES")
        require(evidence["phoneEvidenceImported"]==JsonPrimitive(false))
        listOf("verifiedPcTools","pcCalls","unresolvedCalls").forEach{require(evidence.getValue(it).jsonArray.isEmpty())}
        // Only screen openers (map, web page, consumer-app search) may stay PREPARED; a pending send/save never completes here.
        val prepared=evidence.getValue("preparedCalls").jsonArray
        require(if(publicPartial||browserReadsOnly)prepared.isEmpty() else prepared.all{
            ExternalAgentContract.text(it.jsonObject,"tool").let(com.user.localagent.core.PreparedHandoff::opensScreenOnly)})
        val accepted=ExternalAgentContract.plan(evidence.getValue("acceptedPlan").jsonObject,capture)
        require(accepted.state==AgentPlanState.ACCEPTED&&accepted.planDigest==plan.planDigest&&accepted.goal==plan.goal&&
            accepted.requiredTools==plan.requiredTools&&accepted.constraints==plan.constraints)
        fun names(key:String)=evidence.getValue(key).jsonArray.map{(it as JsonPrimitive).also{p->require(p.isString)}.content}
        require(names("requiredTools").let{it.size==it.toSet().size&&it.toSet()==plan.requiredTools})
        require(names("phoneToolsRequiringLocalEvidence").let{it.size==it.toSet().size&&it.toSet()==plan.requiredTools})
        val calls=evidence.getValue("phoneCalls").jsonArray
        require(calls.size==actual.size&&calls.size<=128)
        val receipts=calls.map{item->
            val row=item.jsonObject
            ExternalAgentContract.fields(row,setOf("callId","sequence","tool","state"))
            val sequence=ExternalAgentContract.long(row,"sequence")
            require(sequence in 1..Int.MAX_VALUE.toLong()&&ExternalAgentContract.text(row,"state")=="COMPLETED")
            NativePhoneReceipt(ExternalAgentContract.text(row,"callId"),sequence.toUInt(),ExternalAgentContract.text(row,"tool"))
        }
        val observations=actual.map{local->
            val proposal=local.proposal
            require(local.parentRunId==capture.parentRunId&&local.projectBinding==capture.projectBinding&&local.authorityRevision==capture.authorityRevision)
            require(proposal.sequence>0&&local.result.id==proposal.callId)
            NativePhoneObservation(proposal.callId,proposal.sequence.toUInt(),proposal.tool,local.result.success,
                NativePhoneVerification.valueOf(local.result.verification.name),local.result.effectNotApplied,local.withheldFromCloud)
        }
        val required=if(browserReadsOnly){
            val observed=actual.map { it.proposal.tool }.toSet()
            require(plan.requiredTools.all { it in CloudAgentFinals.browserReadTools || it in com.user.localagent.core.PcBrowserToolCatalog.names || it in setOf("open_app_browser_page","app_browser_action","save_browser_pdf","save_public_pdf") })
            require(observed.any {it in CloudAgentFinals.browserPageReads} && plan.requiredTools.containsAll(observed))
            require(actual.all { it.proposal.tool in CloudAgentFinals.browserReadTools &&
                !it.withheldFromCloud && it.result.success && !it.result.effectNotApplied &&
                it.result.verification==com.user.localagent.core.VerificationState.VERIFIED })
            observed
        } else plan.requiredTools
        nativePhoneCompletionProven(required.toList(),receipts,observations,publicPartial)
    }.getOrDefault(false)
}
