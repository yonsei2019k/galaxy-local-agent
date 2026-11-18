package com.user.localagent.externalagent

import kotlinx.serialization.json.*

/** Joins broker metadata to actual local callbacks. Server phone results are never evidence. */
object ExternalAgentPhoneCompletion {
    fun matches(capture:ExternalAgentCapture,plan:ExternalAgentPlan,evidence:JsonObject,actual:List<PhoneAgentExecution>,expectedSessionId:String):Boolean=runCatching {
        require(capture.requirePlan&&capture.pcWorkspaceProfile=="phone-only")
        fun text(key:String)=ExternalAgentContract.text(evidence,key)
        require(text("sessionId")==expectedSessionId&&ExternalAgentContract.long(evidence,"revision")>0)
        require(text("parentRunId")==capture.parentRunId&&text("projectBinding")==capture.projectBinding&&
            text("requestDigest")==capture.requestDigest&&ExternalAgentContract.long(evidence,"authorityRevision")==capture.authorityRevision)
        require(text("state")=="ACTIVE"&&text("restartPolicy")=="RAM_ONLY_RESTART_REVOKES")
        require(evidence["phoneEvidenceImported"]==JsonPrimitive(false))
        listOf("verifiedPcTools","pcCalls","unresolvedCalls").forEach{require(evidence.getValue(it).jsonArray.isEmpty())}
        // Only screen openers (map, web page, consumer-app search) may stay PREPARED; a pending send/save never completes here.
        require(evidence.getValue("preparedCalls").jsonArray.all{ExternalAgentContract.text(it.jsonObject,"tool").let(com.user.localagent.core.PreparedHandoff::opensScreenOnly)})
        val accepted=ExternalAgentContract.plan(evidence.getValue("acceptedPlan").jsonObject,capture)
        require(accepted.state==AgentPlanState.ACCEPTED&&accepted.planDigest==plan.planDigest&&accepted.goal==plan.goal&&
            accepted.requiredTools==plan.requiredTools&&accepted.constraints==plan.constraints)
        fun names(key:String)=evidence.getValue(key).jsonArray.map{(it as JsonPrimitive).also{p->require(p.isString)}.content}
        require(names("requiredTools").let{it.size==it.toSet().size&&it.toSet()==plan.requiredTools})
        require(names("phoneToolsRequiringLocalEvidence").let{it.size==it.toSet().size&&it.toSet()==plan.requiredTools})
        val calls=evidence.getValue("phoneCalls").jsonArray.map{it.jsonObject}
        require(calls.size==actual.size&&actual.map{it.proposal.callId}.distinct().size==actual.size)
        require(plan.requiredTools.all{name->actual.any{it.proposal.tool==name&&it.result.success&&!it.result.effectNotApplied&&
            (it.result.verification==com.user.localagent.core.VerificationState.VERIFIED||
                com.user.localagent.core.PreparedHandoff.opensScreenOnly(name)&&it.result.verification==com.user.localagent.core.VerificationState.PREPARED)}})
        calls.forEachIndexed{index,row->
            ExternalAgentContract.fields(row,setOf("callId","sequence","tool","state"))
            val local=actual[index];val proposal=local.proposal
            require(local.parentRunId==capture.parentRunId&&local.projectBinding==capture.projectBinding&&local.authorityRevision==capture.authorityRevision)
            require(ExternalAgentContract.text(row,"callId")==proposal.callId&&ExternalAgentContract.long(row,"sequence")==proposal.sequence.toLong()&&
                ExternalAgentContract.text(row,"tool")==proposal.tool&&ExternalAgentContract.text(row,"state")=="COMPLETED")
            require(proposal.tool in plan.requiredTools&&local.result.id==proposal.callId)
        }
        true
    }.getOrDefault(false)
}
