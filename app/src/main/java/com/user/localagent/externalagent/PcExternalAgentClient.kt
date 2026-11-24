package com.user.localagent.externalagent

import com.user.localagent.core.ToolResult
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/** Actual v1 HTTPS client. Only the authenticated phone role can claim/report; no agent result import. */
class PcExternalAgentClient(
    private val captured:ExternalAgentCapture,private val transport:AgentBridgeTransport,
    private val onRetry:(operation:String,attempt:Int)->Unit={_,_->},
):ExternalAgentClient {
    private var sessionId:String?=null
    private var phoneCapability:String?=null
    private val claims=mutableMapOf<String,Long>()
    private val snapshotFields=setOf("sessionId","revision","state","expiresAtEpochMs","lastSequence",
        "allowExternalAgent","tools","pendingCall","restartPolicy")

    private fun snapshot(root:JsonObject,extra:Set<String> = emptySet()):AgentSessionSnapshot {
        val planFields=if(captured.requirePlan)setOf("requiresPlan","plan")else emptySet()
        ExternalAgentContract.fields(root,snapshotFields+extra+planFields,setOf("resultAck"))
        val plan=if(captured.requirePlan){
            require((root.getValue("requiresPlan") as JsonPrimitive).also{require(!it.isString)}.boolean)
            root.getValue("plan").takeUnless{it==JsonNull}?.jsonObject?.let{ExternalAgentContract.plan(it,captured)}
        }else null
        root["resultAck"]?.let {
            val ack=it.jsonObject;ExternalAgentContract.fields(ack,setOf("callId","sequence","resultDigest"))
            ExternalAgentContract.uuid(ExternalAgentContract.text(ack,"callId"))
            require(ExternalAgentContract.long(ack,"sequence") in 1..18&&
                ExternalAgentContract.text(ack,"resultDigest").matches(Regex("[a-f0-9]{64}")))
        } // Metadata only; poll/claim cannot create a phone execution receipt.
        val id=ExternalAgentContract.text(root,"sessionId");ExternalAgentContract.uuid(id)
        sessionId?.let{require(id==it){"BRIDGE_SESSION_ID"}}
        require(ExternalAgentContract.text(root,"restartPolicy")=="RAM_ONLY_RESTART_REVOKES")
        val active=(root.getValue("allowExternalAgent") as JsonPrimitive).also { require(!it.isString) }.boolean
        val state=AgentSessionState.valueOf(ExternalAgentContract.text(root,"state"))
        require(state!=AgentSessionState.ACTIVE||active&&captured.allowExternalAgent)
        val tools=root.getValue("tools").jsonArray;require(tools.size<=128+ExternalAgentContract.PC_TOOLS.size){"BRIDGE_TOOL_CATALOG_BOUND"}
        val names=tools.map { item ->
            val t=item.jsonObject;ExternalAgentContract.fields(t,setOf("name","description","inputSchema"))
            require(t.getValue("inputSchema") is JsonObject)
            ExternalAgentContract.text(t,"description")
            ExternalAgentContract.text(t,"name")
        }
        require(names.distinct().size==names.size&&names.containsAll(captured.phoneTools))
        require(names.all { it in captured.phoneTools||it in captured.pcTools })
        val pending=root.getValue("pendingCall").takeUnless{it==JsonNull}?.jsonObject?.let(ExternalAgentContract::proposal)
        pending?.checkedCall(captured)
        val revision=ExternalAgentContract.long(root,"revision");val sequence=ExternalAgentContract.long(root,"lastSequence")
        val expiry=ExternalAgentContract.long(root,"expiresAtEpochMs")
        require(revision>=0&&sequence in 0..18&&expiry>0&&pending?.let{
            it.sequence<=sequence&&(it.claimRevision==null||it.claimRevision<=revision)
        }!=false)
        return AgentSessionSnapshot(id,revision,state,expiry,sequence.toInt(),pending,captured.requirePlan,plan)
    }
    private fun path(suffix:String="")="/v1/agent/sessions/"+checkNotNull(sessionId)+suffix
    private suspend fun call(method:String,suffix:String="",body:JsonObject?=null):JsonObject {
        val route=path(suffix)
        val capability=checkNotNull(phoneCapability)
        val response=try {
            transport.exchange(route,method,body,capability)
        }catch(error:PcAgentBridgeTimeout){
            if(method!="GET")throw error
            onRetry(error.stageCode,2)
            delay(200)
            transport.exchange(route,method,body,capability)
        }
        return ExternalAgentContract.parseObject(response)
    }

    override suspend fun register(capture:ExternalAgentCapture):AgentSessionRegistration {
        check(sessionId==null&&phoneCapability==null&&capture===captured)
        check(capture.allowExternalAgent)
        val root=ExternalAgentContract.parseObject(transport.exchange("/v1/agent/sessions","POST",capture.registration()))
        val value=snapshot(root,setOf("phoneCapability"))
        val capability=ExternalAgentContract.text(root,"phoneCapability")
        require(capability.matches(Regex("[A-Za-z0-9_-]{32,256}")))
        require(value.state==AgentSessionState.ACTIVE&&value.lastSequence==0&&value.pending==null)
        sessionId=value.sessionId;phoneCapability=capability
        return AgentSessionRegistration(value,ExternalAgentBootstrap(capture.parentRunId,value.sessionId))
    }
    override suspend fun poll():AgentSessionSnapshot=snapshot(call("GET"))
    override suspend fun acknowledgePlan(planDigest:String,accepted:Boolean):AgentSessionSnapshot {
        require(captured.requirePlan&&planDigest.matches(Regex("[a-f0-9]{64}")))
        val value=snapshot(call("POST","/plan-ack",buildJsonObject{put("planDigest",planDigest);put("accepted",accepted)}))
        val plan=checkNotNull(value.plan)
        require(plan.planDigest==planDigest&&plan.state==if(accepted)AgentPlanState.ACCEPTED else AgentPlanState.REJECTED)
        return value
    }
    override suspend fun claim(proposal:ExternalAgentProposal,revision:Long):AgentClaimAck {
        proposal.checkedCall(captured);require(proposal.state==AgentPhoneCallState.QUEUED&&!claims.containsKey(proposal.callId))
        val root=call("POST","/claim",buildJsonObject{
            put("callId",proposal.callId);put("sequence",proposal.sequence);put("revision",revision)
        })
        val value=snapshot(root);val pending=checkNotNull(value.pending)
        require(value.state==AgentSessionState.ACTIVE&&pending.state==AgentPhoneCallState.CLAIMED&&
            pending.fingerprint()==proposal.fingerprint()&&value.revision>revision&&pending.claimRevision==value.revision){"BRIDGE_CLAIM_RECEIPT"}
        claims[proposal.callId]=checkNotNull(pending.claimRevision)
        return AgentClaimAck(value,proposal.callId,proposal.sequence)
    }
    override suspend fun report(proposal:ExternalAgentProposal,result:ToolResult,revision:Long):AgentResultAck {
        val claimRevision=checkNotNull(claims[proposal.callId])
        require(result.id==proposal.callId&&revision>=claimRevision&&
            result.content.toByteArray(Charsets.UTF_8).size<=ExternalAgentContract.MAX_RESULT_BYTES)
        val outgoing=com.user.localagent.core.CredentialRedaction.forCloudToolResult(result.content)
        fun payload(content:String)=buildJsonObject {
            put("success",result.success);put("content",content);put("verification",result.verification.name)
            put("effectNotApplied",result.effectNotApplied)
        }
        val actual=payload(outgoing).takeIf {
            ExternalAgentContract.sorted(it).toString().toByteArray(Charsets.UTF_8).size<=ExternalAgentContract.MAX_RESULT_BYTES
        }?:payload("도구 결과가 전송 한도를 초과해 원문을 모델에 전달하지 않았습니다.")
        val expectedDigest=ExternalAgentContract.sha(ExternalAgentContract.sorted(actual).toString())
        val root=call("POST","/result",buildJsonObject {
            put("callId",proposal.callId);put("sequence",proposal.sequence);put("revision",claimRevision);put("result",actual)
        })
        val value=snapshot(root,setOf("resultAck"));val ack=root.getValue("resultAck").jsonObject
        ExternalAgentContract.fields(ack,setOf("callId","sequence","resultDigest"))
        require(ExternalAgentContract.text(ack,"callId")==proposal.callId&&ExternalAgentContract.long(ack,"sequence")==proposal.sequence.toLong()&&
            ExternalAgentContract.text(ack,"resultDigest")==expectedDigest){"BRIDGE_RESULT_DIGEST_ACK"}
        return AgentResultAck(value,proposal.callId,proposal.sequence)
    }
    override suspend fun cancel(revision:Long):AgentSessionSnapshot =
        snapshot(call("POST","/cancel",buildJsonObject{put("revision",revision)}))
    private fun provider(root:JsonObject):JsonObject {
        ExternalAgentContract.fields(root,setOf("state","report","errorCode"),setOf("progress"))
        require(ExternalAgentContract.text(root,"state") in setOf("RUNNING","FINISHED","CANCELLING"))
        root.getValue("errorCode").takeUnless{it==JsonNull}?.let {
            val code=(it as JsonPrimitive).also{p->require(p.isString)}.content
            ExternalAgentContract.bounded(code,128)
        }
        root.getValue("report").takeUnless{it==JsonNull}?.jsonObject?.let { report ->
            ExternalAgentContract.fields(report,setOf("state","answer","question","elapsedMs","mcpCalls"),setOf("choices"))
            require(ExternalAgentContract.text(report,"state") in setOf("reported_done","needs_input","partial","failed"))
            val answer=ExternalAgentContract.text(report,"answer");val question=ExternalAgentContract.text(report,"question")
            require(answer.codePointCount(0,answer.length)<=12_000&&question.codePointCount(0,question.length)<=300)
            require(ExternalAgentContract.long(report,"elapsedMs")>=0&&ExternalAgentContract.long(report,"mcpCalls") in 0..1000)
        }
        ProviderProgress.parse(root["progress"])?.let{next->
            if(latestProviderProgress?.sequence?.let{next.sequence>it}!=false)
                latestProviderProgress=next.copy(toolName=next.toolName?.takeIf{it in captured.phoneTools||it in captured.pcTools})
        }
        return root // Agent prose/status only, never parsed as VERIFIED or added to the phone ledger.
    }
    var latestProviderProgress:ProviderProgress?=null
        private set
    /**
     * Public Office persona text (no owner-entered ages) for a fixed Office room, sent only when the PC
     * advertises support. Older workers reject unknown launch keys, so an unsupported or unknown answer omits it.
     */
    private suspend fun supportedOfficePersona(persona:String?):String? {
        if(persona.isNullOrBlank())return null
        require(persona==persona.trim()&&persona.length<=6000&&persona.none{it.isISOControl()&&it!='\n'}){"INVALID_OFFICE_PERSONA"}
        val support=try{call("GET","/persona-capabilities")}catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}catch(_:Exception){return null}
        val versions=support["officePersonaVersions"]?.let{runCatching{it.jsonArray}.getOrNull()}?:return null
        val max=support["maxOfficePersonaChars"]?.jsonPrimitive?.intOrNull?:return null
        return persona.takeIf{versions.any{it.jsonPrimitive.intOrNull==1}&&persona.length<=max}
    }
    suspend fun launchProvider(ownerRequest:String,conversationContext:JsonObject?=null,provider:String="codex",beforeLaunch:suspend ()->Unit={},
        officePersona:String?=null):JsonObject {
        require(captured.allowExternalAgent&&ownerRequest.isNotBlank()&&ownerRequest.toByteArray(Charsets.UTF_8).size<=65_536&&
            ExternalAgentContract.sha(ownerRequest)==captured.requestDigest){"BRIDGE_OWNER_REQUEST_CHANGED"}
        if(!captured.allowCloudPersonalData){
            require(!com.user.localagent.core.ConversationPrivacy.sensitiveText(ownerRequest) &&
                (conversationContext==null || !com.user.localagent.core.ConversationPrivacy.sensitiveText(conversationContext.toString()))){"PRIVATE_CLOUD_INPUT"}
        }
        beforeLaunch()
        if(conversationContext!=null){
            val support=call("GET","/context-capabilities")
            require(support.keys==setOf("conversationContextVersions","maxContextBytes")&&
                support.getValue("conversationContextVersions").jsonArray.any{it.jsonPrimitive.intOrNull==1}&&
                support.getValue("maxContextBytes").jsonPrimitive.int>=CloudConversationContext.MAX_UTF8_BYTES){
                "PC가 대화 맥락 전달을 지원하지 않습니다. PC 작업기를 업데이트한 뒤 다시 요청해주세요."
            }
        }
        val persona=supportedOfficePersona(officePersona)
        val request=buildJsonObject{
            persona?.let{put("officePersona",it)}
            put("ownerRequest",ownerRequest)
            require(provider in setOf("codex","claude"))
            if(provider=="claude")put("provider",provider)
            conversationContext?.let {
                require(it.toString().toByteArray(Charsets.UTF_8).size<=CloudConversationContext.MAX_UTF8_BYTES)
                put("conversationContext",it)
            }
        }
        repeat(3){attempt->
            beforeLaunch()
            try {
                return provider(call("POST","/launch",request))
            }catch(error:PcAgentProviderBusy){
                if(attempt==2)throw error
                onRetry("PROVIDER_BUSY",attempt+2)
                delay(1_000L*(attempt+1))
            }
        }
        error("Unreachable launch retry")
    }
    suspend fun providerStatus():JsonObject=provider(call("GET","/provider"))
    suspend fun cancelProvider():JsonObject=provider(call("POST","/provider-cancel",buildJsonObject {}))
    /** Server PC evidence is kept separate; phone rows are METADATA ONLY, never execution results. */
    suspend fun taskEvidence():JsonObject {
        val root=call("GET","/evidence")
        ExternalAgentContract.fields(root,setOf("sessionId","parentRunId","revision","state","projectBinding","authorityRevision","requestDigest",
            "acceptedPlan","requiredTools","verifiedPcTools","pcCalls","phoneCalls","phoneToolsRequiringLocalEvidence",
            "unresolvedCalls","preparedCalls","phoneEvidenceImported","restartPolicy"))
        require(ExternalAgentContract.text(root,"sessionId")==sessionId&&ExternalAgentContract.text(root,"parentRunId")==captured.parentRunId&&
            ExternalAgentContract.text(root,"projectBinding")==captured.projectBinding&&
            ExternalAgentContract.long(root,"authorityRevision")==captured.authorityRevision&&
            ExternalAgentContract.text(root,"requestDigest")==captured.requestDigest&&ExternalAgentContract.long(root,"revision")>=0)
        AgentSessionState.valueOf(ExternalAgentContract.text(root,"state"))
        require(ExternalAgentContract.text(root,"restartPolicy")=="RAM_ONLY_RESTART_REVOKES")
        require(!(root.getValue("phoneEvidenceImported") as JsonPrimitive).also{require(!it.isString)}.boolean)
        fun names(key:String):List<String> = root.getValue(key).jsonArray.map{
            (it as JsonPrimitive).also{p->require(p.isString)}.content
        }.also{require(it.size<=32&&it.distinct().size==it.size)}
        val required=names("requiredTools");val verified=names("verifiedPcTools");val local=names("phoneToolsRequiringLocalEvidence")
        require(required.all{it in captured.phoneTools||it in captured.pcTools}&&verified.all{it in captured.pcTools})
        require(local==required.filter{it in captured.phoneTools})
        root.getValue("acceptedPlan").takeUnless{it==JsonNull}?.jsonObject?.let {
            val plan=ExternalAgentContract.plan(it,captured)
            require(plan.state==AgentPlanState.ACCEPTED&&required==plan.requiredTools.toList())
        }
        val states=setOf("QUEUED","CLAIMED","RUNNING","COMPLETED","CANCELLED","UNKNOWN_EFFECT")
        fun metadata(row:JsonObject,tools:Set<String>) {
            ExternalAgentContract.uuid(ExternalAgentContract.text(row,"callId"))
            require(ExternalAgentContract.long(row,"sequence") in 1..18&&ExternalAgentContract.text(row,"tool") in tools&&
                ExternalAgentContract.text(row,"state") in states)
        }
        for(key in listOf("phoneCalls","unresolvedCalls","preparedCalls")){
            val rows=root.getValue(key).jsonArray;require(rows.size<=18)
            rows.forEach{
                val row=it.jsonObject;ExternalAgentContract.fields(row,setOf("callId","sequence","tool","state"))
                metadata(row,if(key=="phoneCalls")captured.phoneTools else captured.phoneTools+captured.pcTools)
            }
        }
        val pc=root.getValue("pcCalls").jsonArray;require(pc.size<=18&&pc.size+root.getValue("phoneCalls").jsonArray.size<=18)
        val all=pc.map{it.jsonObject}+root.getValue("phoneCalls").jsonArray.map{it.jsonObject}
        require(all.map{ExternalAgentContract.text(it,"callId")}.distinct().size==all.size&&
            all.map{ExternalAgentContract.long(it,"sequence")}.distinct().size==all.size)
        pc.forEach {
            val row=it.jsonObject
            ExternalAgentContract.fields(row,setOf("callId","sequence","tool","state","evidenceSource","verifiedComplete"),
                setOf("arguments","result","resultDigest"))
            metadata(row,captured.pcTools);require(ExternalAgentContract.text(row,"evidenceSource")=="BROKER_PC")
            val complete=(row.getValue("verifiedComplete") as JsonPrimitive).also{p->require(!p.isString)}.boolean
            val hasResult="result" in row
            require(hasResult==("arguments" in row)&&hasResult==("resultDigest" in row))
            if(hasResult){
                row.getValue("arguments").jsonObject
                val result=row.getValue("result").jsonObject
                ExternalAgentContract.fields(result,setOf("success","content","verification","effectNotApplied"))
                val success=(result.getValue("success") as JsonPrimitive).also{p->require(!p.isString)}.boolean
                val notApplied=(result.getValue("effectNotApplied") as JsonPrimitive).also{p->require(!p.isString)}.boolean
                val verification=ExternalAgentContract.text(result,"verification")
                require(verification in setOf("VERIFIED","PREPARED","UNVERIFIED","FAILED"))
                ExternalAgentContract.text(result,"content")
                require(!notApplied||!success&&verification=="FAILED")
                require(ExternalAgentContract.text(row,"resultDigest")==ExternalAgentContract.sha(ExternalAgentContract.sorted(result).toString()))
                if(complete)require(success&&!notApplied&&verification=="VERIFIED"&&ExternalAgentContract.text(row,"state")=="COMPLETED")
            }else require(!complete)
        }
        require(verified.toSet()==pc.filter{it.jsonObject.getValue("verifiedComplete").jsonPrimitive.boolean}
            .map{ExternalAgentContract.text(it.jsonObject,"tool")}.toSet())
        return root // Root still joins current live authority, its OWN phone ledger, TaskHarness and readback.
    }
    override suspend fun close(revision:Long):AgentSessionSnapshot {
        val closed=snapshot(call("POST","/close",buildJsonObject{put("revision",revision)}))
        require(closed.state==AgentSessionState.CLOSED)
        phoneCapability=null;claims.clear()
        return closed
    }
    override fun cancelNetwork(){transport.cancelNetwork()}
}
