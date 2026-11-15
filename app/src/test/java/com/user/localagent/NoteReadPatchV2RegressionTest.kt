package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.core.RequiredReadbackObligations as L
import com.user.localagent.notes.*
import com.user.localagent.inference.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Staged source only. Synthetic host observations are not actual model/Room/device evidence. */
class NoteReadPatchV2RegressionTest {
    private val binding=NoteReadBinding("run-demo","thread-demo","branch-demo","scope-demo")
    private val context=L.Context(binding.runId,binding.threadId,null,binding.scopeKey,null)
    private val required=setOf("patch_note","read_note")
    private val after="changed "+"x".repeat(1300)
    private fun source(id:String="note-demo",body:String=after,revision:Long=8)=NoteSource(id,binding.scopeKey,"team","title",body,revision)
    private fun read(n:NoteSource=source(),offset:Int=0,limit:Int?=900,callId:String="read-$offset")=ToolCall(callId,"read_note",buildJsonObject{
        put("id",n.id);put("scope",n.collection);put("expectedUpdatedAt",n.updatedAt.toString());put("offset",offset);limit?.let{put("limit",it)}
    })
    private fun page(call:ToolCall,n:NoteSource=source())=NoteReadPatchSession(binding).read(n,NoteReadPatchToolCatalog.readRequest(call))
    private fun observation(offset:Int=0,n:NoteSource=source(),callId:String="read-$offset"):ObservedToolOutcome {
        val c=read(n,offset,callId=callId);return ObservedToolOutcome(c,ToolResult(c.id,true,page(c,n).json().toString(),VerificationState.VERIFIED,"synthetic provider"))
    }
    private fun mutation():ObservedToolOutcome {
        val call=ToolCall("patch-demo","patch_note",buildJsonObject{put("id","note-demo");put("scope","team");put("expectedUpdatedAt","7");put("oldText","needle");put("newText","changed")})
        val receipt=NotePatchReceipt(NotePatchIdentity("note-patch-"+"a".repeat(64),"a".repeat(64),"note-demo","team"),"title",7,8,
            noteSha("needle "+"x".repeat(1300)),noteSha(after),true)
        return ObservedToolOutcome(call,ToolResult(call.id,true,receipt.json().toString(),VerificationState.VERIFIED,"synthetic committed receipt"))
    }
    private fun steps(outcomes:List<ObservedToolOutcome>)=outcomes.map{HarnessStep(it.call,StepEvidence(it.call.name,it.result.verification,"synthetic host",effectNotApplied=it.result.effectNotApplied))}
    private fun keys(outcomes:List<ObservedToolOutcome>)=outcomes.filter{it.call.name=="read_note"&&it.result.success}.map{NoteReadbackPages.deliveryKey(it.call,it.result.content)}.toSet()
    private fun collect(outcomes:List<ObservedToolOutcome>,delivered:Set<String>)=ChatReadbackEvidence.collect(context.proofBinding(),required,steps(outcomes),outcomes,delivered)
    private fun capture(outcomes:List<ObservedToolOutcome>,delivered:Set<String>,prior:L.State?=null):L.State {
        val c=collect(outcomes,delivered)
        return checkNotNull(L.capture(prior?:L.select(null,context,required),context,steps(outcomes),c.mutations,c.reads,c.aligned,
            c.reads.associate{it.stepIndex to checkNotNull(ChatReadbackEvidence.querySignature(it.tool,outcomes[it.stepIndex].call.arguments))}))
    }
    private fun met(state:L.State,outcomes:List<ObservedToolOutcome>)=L.assess(state,context,steps(outcomes),0,required,18-outcomes.size,3).readbackSatisfied
    private fun reject(action:()->Unit){try{action();fail("Expected rejection")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}
    private fun evidence(page:NoteReadPage)=buildJsonObject{put("tool","read_note");put("success",true);put("verification","VERIFIED");put("result",page.modelResult())}
    private fun arguments(raw:String,tool:String):List<JsonObject> {
        val found=mutableListOf<JsonObject>()
        fun walk(e:JsonElement){when(e){
            is JsonArray->e.forEach(::walk)
            is JsonObject->{val p=e["properties"] as? JsonObject
                if((p?.get("tool") as? JsonObject)?.get("enum")?.jsonArray?.any{it.jsonPrimitive.content==tool}==true)
                    (p?.get("arguments") as? JsonObject)?.let(found::add)
                e.values.forEach(::walk)}
            else->Unit
        }}
        walk(Json.parseToJsonElement(raw));return found
    }

    @Test fun registryExplicitPageLimitsMatchCatalogAndHostInsteadOfFallbackFifty() {
        for(limit in listOf(2,50,51,900)){
            val c=read(limit=limit);assertEquals(c,ToolRegistry.validate(c))
            val p=page(c);assertTrue(p.text.length<=limit);assertTrue(p.json().toString().length<=3000)
        }
        for(limit in listOf(1,901))reject{ToolRegistry.validate(read(limit=limit))}
        assertEquals(900,page(read(limit=null)).text.length)
    }
    @Test fun finalActionQuestionAndReviewSchemasPreserveOnlyExplicitEmptyReplacement() {
        val schemas=listOf(ToolCallSchema.forTools(required),NextActionQuestion.schema(required,"synthetic request"),
            NextActionQuestion.extend(AutonomousReviewPolicy.schema(required)))
        for(raw in schemas){val variants=arguments(raw,"patch_note");assertTrue(variants.isNotEmpty())
            for(a in variants){val p=a.getValue("properties").jsonObject
                assertEquals(0,p.getValue("newText").jsonObject.getValue("minLength").jsonPrimitive.int)
                for(key in listOf("id","scope","expectedUpdatedAt","oldText"))assertTrue(p.getValue(key).jsonObject.getValue("minLength").jsonPrimitive.int>=1)
            }
        }
        val old=arguments(ToolCallSchema.forTools(setOf("update_note")),"update_note").single().getValue("properties").jsonObject
        assertEquals(1,old.getValue("content").jsonObject.getValue("minLength").jsonPrimitive.int)
    }
    @Test fun actualIssuedIntactPageRequiresAcknowledgementBeforeAfterReadProof() {
        val session=NoteReadPatchSession(binding);val ledger=NoteReadDeliveryLedger();val c=read();val p=session.read(source(),NoteReadPatchToolCatalog.readRequest(c))
        ledger.issued(c,p);val e=evidence(p);val input=JsonArray(listOf(e)).toString();session.acceptDelivered(listOf(e),input)
        val batch=ledger.accept(session.acceptedPageTokens());assertTrue(batch.hasPages);assertTrue(ledger.keys().isEmpty())
        ledger.acknowledge(batch);assertEquals(setOf(NoteReadbackPages.deliveryKey(c,p.json().toString())),ledger.keys())
    }
    @Test fun omittedTruncatedAndAlteredPagesDoNotBecomeDeliveredAuthority() {
        for(mode in listOf("omitted","truncated","altered")){
            val session=NoteReadPatchSession(binding);val ledger=NoteReadDeliveryLedger();val c=read();val p=session.read(source(),NoteReadPatchToolCatalog.readRequest(c));ledger.issued(c,p)
            val original=evidence(p);val e=if(mode=="altered")JsonObject(original+mapOf("result" to JsonObject(p.modelResult()+mapOf("truncated" to JsonPrimitive(true)))))else original
            val query=if(mode=="omitted")"no page"else if(mode=="truncated")JsonArray(listOf(e)).toString().take(80)else JsonArray(listOf(e)).toString()
            session.acceptDelivered(listOf(e),query);val batch=ledger.accept(session.acceptedPageTokens());ledger.acknowledge(batch);assertTrue(ledger.keys().isEmpty())
        }
    }
    @Test fun reusedCallIdCannotBorrowAnotherTargetOrDifferentPageResult() {
        val a=observation(callId="same-id");val b=observation(n=source(id="other-note"),callId="same-id")
        assertNotEquals(NoteReadbackPages.deliveryKey(a.call,a.result.content),NoteReadbackPages.deliveryKey(b.call,b.result.content))
        assertNotEquals(NoteReadbackPages.deliveryKey(a.call,a.result.content),NoteReadbackPages.deliveryKey(a.call,a.result.content+" "))
        val outcomes=listOf(mutation(),observation(0),observation(900))
        val foreign=outcomes.filter{it.call.name=="read_note"}.map{NoteReadbackPages.deliveryKey(b.call,b.result.content)}.toSet()
        assertFalse(met(capture(outcomes,foreign),outcomes))
    }
    @Test fun staleForeignAndReplayedAcknowledgementsDoNotRestoreProof() {
        val a=NoteReadDeliveryLedger();val b=NoteReadDeliveryLedger();val pending=a.accept(emptySet())
        reject{b.acknowledge(pending)};a.acknowledge(pending);reject{a.acknowledge(pending)}
        val stale=a.accept(emptySet());a.accept(emptySet());reject{a.acknowledge(stale)}
        val cleared=a.accept(emptySet());a.clear();reject{a.acknowledge(cleared)}
    }
    @Test fun errorUnknownEndClippingPauseOpaqueAndEmptyOutputCannotAcknowledge() {
        fun summary(reason:GenerationStopReason=GenerationStopReason.EOG)=GenerationSummary("synthetic-generation",reason,GenerationEvidence.HOST,false,768)
        val good=summary();assertTrue(NoteReadDeliveryCompletion.canAcknowledge(false,false,true,good))
        for(reason in GenerationStopReason.entries.filter{it!=GenerationStopReason.EOG})assertFalse(NoteReadDeliveryCompletion.canAcknowledge(false,false,true,summary(reason)))
        assertFalse(NoteReadDeliveryCompletion.canAcknowledge(false,false,true,null))
        assertFalse(NoteReadDeliveryCompletion.canAcknowledge(true,false,true,good));assertFalse(NoteReadDeliveryCompletion.canAcknowledge(false,true,true,good))
        assertFalse(NoteReadDeliveryCompletion.canAcknowledge(false,false,false,good))
        assertFalse(NoteReadDeliveryCompletion.canAcknowledge(false,false,true,good.copy(errorCode="synthetic_error")))
        assertFalse(NoteReadDeliveryCompletion.canAcknowledge(false,false,true,good.copy(capReached=true)))
    }
    @Test fun rawProviderWholePagesAloneCannotCompleteModelDeliveryCriterion() {
        val outcomes=listOf(mutation(),observation(),observation(900))
        val pending=capture(outcomes,emptySet());assertFalse(met(pending,outcomes))
        val unmet=L.assess(pending,context,steps(outcomes),0,required,15,3).pending
        assertTrue(NoteReadbackPages.canReviewDelivery(unmet,outcomes)) // Scheduling only, real gate still false.
        assertFalse(NoteReadbackPages.canReviewDelivery(unmet,outcomes.take(2)))
        val failed=outcomes.last().let{it.copy(result=ToolResult(it.call.id,false,"failed",VerificationState.FAILED,"synthetic"))}
        assertFalse(NoteReadbackPages.canReviewDelivery(unmet,outcomes+failed))
        assertFalse(met(pending,outcomes))
        assertTrue(met(capture(outcomes,keys(outcomes)),outcomes))
    }
    @Test fun oneDeliveredPageOrBeforeVersionPagesCannotBecomeWholeAfterRead() {
        val outcomes=listOf(mutation(),observation(),observation(900))
        assertFalse(met(capture(outcomes,setOf(NoteReadbackPages.deliveryKey(outcomes[1].call,outcomes[1].result.content))),outcomes))
        val before=source(body="needle "+"x".repeat(1300),revision=7)
        val stale=listOf(mutation(),observation(n=before),observation(900,n=before))
        assertFalse(met(capture(stale,keys(stale)),stale))
    }
    @Test fun latestFailedReadRevokesDurableWholeProofUntilFreshWholeDelivery() {
        val first=listOf(mutation(),observation(),observation(900));val complete=capture(first,keys(first));assertTrue(met(complete,first))
        val call=read(callId="failed-latest");val failed=ObservedToolOutcome(call,ToolResult(call.id,false,"unavailable",VerificationState.FAILED,"synthetic failed provider",effectNotApplied=true))
        val afterFailure=first+failed;val invalidated=capture(afterFailure,keys(first),complete);assertFalse(met(invalidated,afterFailure))
        val partial=afterFailure+observation(callId="fresh-first");val pending=capture(partial,keys(partial),invalidated);assertFalse(met(pending,partial))
        val fresh=partial+observation(900,callId="fresh-tail");assertTrue(met(capture(fresh,keys(fresh),pending),fresh))
    }
    @Test fun providerFailureClearsOnlyThatTargetsAcknowledgedKeys() {
        val session=NoteReadPatchSession(binding);val ledger=NoteReadDeliveryLedger()
        val a=read();val p=session.read(source(),NoteReadPatchToolCatalog.readRequest(a));ledger.issued(a,p)
        val other=source(id="other-note");val b=read(other);val q=session.read(other,NoteReadPatchToolCatalog.readRequest(b));ledger.issued(b,q)
        val entries=listOf(evidence(p),evidence(q));session.acceptDelivered(entries,JsonArray(entries).toString())
        ledger.acknowledge(ledger.accept(session.acceptedPageTokens()));assertEquals(2,ledger.keys().size)
        ledger.invalidate("note-demo");assertEquals(setOf(NoteReadbackPages.deliveryKey(b,q.json().toString())),ledger.keys())
        assertTrue(ledger.accept(emptySet()).invalidated)
    }
    @Test fun explicitWholeCriterionRemainsDistinctFromLiteralCriterionAndOldNotePairs() {
        assertEquals("note_body_v1",L.Pair.NOTE_PATCH.kind);assertEquals("note",L.Pair.NOTE_SAVE.kind);assertEquals("note",L.Pair.NOTE_UPDATE.kind)
        val outcomes=listOf(mutation(),observation());assertFalse(met(capture(outcomes,keys(outcomes)),outcomes))
        assertFalse(CapabilityPolicy.canPreapprove("patch_note"));assertFalse(AgentWorkModePolicy.allows(AgentWorkMode.PLAN,"patch_note"))
    }
    @Test fun finishChecksAndDurableReadOnlyRestoreCannotPromoteIncompleteDelivery() {
        val raw=listOf(mutation(),observation(),observation(900))
        val partial=listOf(mutation(),observation())
        val wrong=listOf(mutation(),observation(n=source(revision=9)),observation(900,n=source(revision=9)))
        for((outcomes,delivered) in listOf(raw to emptySet<String>(),partial to keys(partial),wrong to keys(wrong))){
            val state=capture(outcomes,delivered)
            assertFalse(L.assess(state,context,steps(outcomes),0,required,14,1,requestedFinish=true).readbackSatisfied)
            val restored=ChatReadbackObligationCodec.decode(ChatReadbackObligationCodec.encode(state))
            assertFalse(L.assess(restored,context,steps(outcomes),outcomes.size,setOf("read_note"),14,1,requestedFinish=true).readbackSatisfied)
        }
    }
}
