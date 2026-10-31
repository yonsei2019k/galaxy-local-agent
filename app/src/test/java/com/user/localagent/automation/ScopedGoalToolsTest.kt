package com.user.localagent.automation

import com.user.localagent.core.*
import com.user.localagent.data.ChatAccessScope
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScopedGoalToolsTest {
    private val scope=ChatAccessScope("thread","project","project:project",false,7,true)
    private fun goal(status:GoalStatus=GoalStatus.ACTIVE)=GoalDefinition(id="goal",threadId=scope.threadId,projectId=scope.projectId,
        outcome="실제 사용자 목표",criteria=listOf(GoalCriterion("a","확인할 결과")),status=status,linkedRunIds=listOf("caller"),updatedAt=1)
    private class MemoryStore(val original:ChatAccessScope,var value:GoalDefinition?=null):GoalToolStore {
        var live=original;var loads=0;var saves=0;var deletes=0;var beforeDelete:(()->Unit)?=null;var beforeSave:(()->Unit)?=null
        override suspend fun load(scope:ChatAccessScope):GoalDefinition? {check(scope==live);loads++;return value}
        override suspend fun save(scope:ChatAccessScope,goal:GoalDefinition):GoalDefinition {
            beforeSave?.invoke()
            check(scope==live);check(goal.threadId==scope.threadId&&goal.projectId==scope.projectId)
            check(if(value==null)goal.updatedAt==0L else goal.id==value!!.id&&goal.updatedAt==value!!.updatedAt)
            saves++;return goal.copy(updatedAt=(value?.updatedAt?:0)+1).also {value=it}
        }
        override suspend fun delete(scope:ChatAccessScope,goalId:String,expectedUpdatedAt:Long):Boolean {
            beforeDelete?.invoke();check(scope==live&&value?.id==goalId&&value?.updatedAt==expectedUpdatedAt)
            deletes++;value=null;return true
        }
    }
    private fun call(name:String,goal:GoalDefinition=goal(),extra:JsonObject=buildJsonObject {})=ToolCall("synthetic",name,
        JsonObject(buildJsonObject {put("goalId",goal.id);put("expectedUpdatedAt",goal.updatedAt.toString())}+extra))
    private fun tools(store:MemoryStore,authorized:()->Set<String> = {GoalToolCatalog.names},caller:String?="caller",
        lifecycle:GoalToolLifecycle=GoalToolLifecycle {error("lifecycle must not run")})=ScopedGoalTools(scope,store,authorized,caller,lifecycle,editReadGuard={_,_->})

    @Test fun ordinaryRequestCannotCreateGoalAndAuthorizationIsReadAtExecutionTime()=runBlocking {
        val store=MemoryStore(scope);var allowed=emptySet<String>();val tools=tools(store,{allowed})
        val create=ToolCall("x",GoalToolCatalog.CREATE,buildJsonObject {put("outcome","목표");put("criteria",buildJsonArray {add("기준")})})
        assertTrue(runCatching {tools.execute(create)}.isFailure);assertEquals(0,store.loads)
        allowed=setOf(GoalToolCatalog.CREATE)
        val result=checkNotNull(tools.execute(create));assertEquals(VerificationState.VERIFIED,result.verification)
        assertEquals(GoalStatus.DRAFT,store.value!!.status);assertTrue(store.value!!.criteria.all {it.status==GoalCriterionStatus.PENDING})
    }
    @Test fun staleVersionAndChangedFullAccessCannotMutateOrQuiesce()=runBlocking {
        val store=MemoryStore(scope,goal().copy(updatedAt=2))
        assertTrue(runCatching {tools(store).execute(call(GoalToolCatalog.CLEAR))}.isFailure)
        store.live=scope.copy(useOwnerMemory=true)
        assertTrue(runCatching {tools(store).execute(call(GoalToolCatalog.UPDATE,extra=buildJsonObject {put("outcome","새 내용")}))}.isFailure)
        assertEquals(0,store.saves);assertEquals(0,store.deletes)
    }
    @Test fun patchDoesNotDropHiddenCriteriaOrForgeProof()=runBlocking {
        val old=goal().copy(criteria=List(20){GoalCriterion("id-$it","기준 $it",GoalCriterionStatus.USER_CONFIRMED)},linkedRunIds=emptyList())
        val store=MemoryStore(scope,old)
        tools(store).execute(call(GoalToolCatalog.UPDATE,old,buildJsonObject {put("criteria",buildJsonArray {add(buildJsonObject {put("id","id-0");put("text","바꾼 기준")})})}))
        assertEquals(20,store.value!!.criteria.size);assertEquals("기준 19",store.value!!.criteria.last().text)
        assertEquals(GoalStatus.DRAFT,store.value!!.status);assertTrue(store.value!!.criteria.all {it.status==GoalCriterionStatus.PENDING})
    }
    @Test fun queueReceiptNeedsActualSameWorkStoreLinkReadback()=runBlocking {
        val store=MemoryStore(scope,goal(GoalStatus.PAUSED))
        val lifecycle=GoalToolLifecycle {request->
            assertEquals(GoalLifecycleAction.RESUME,request.action);assertEquals(scope,request.scope)
            store.save(scope,request.goal.copy(status=GoalStatus.ACTIVE,linkedRunIds=request.goal.linkedRunIds+"queued-run"))
            GoalLifecycleReceipt("queued-run",queuedOneBoundedRun=true)
        }
        val result=checkNotNull(tools(store,lifecycle=lifecycle).execute(call(GoalToolCatalog.RESUME,store.value!!)))
        assertTrue(Json.parseToJsonElement(result.content).jsonObject.getValue("queuedOneBoundedRun").jsonPrimitive.boolean)
        assertEquals(VerificationState.VERIFIED,result.verification)
        val falseStore=MemoryStore(scope,goal())
        assertTrue(runCatching {tools(falseStore,lifecycle=GoalToolLifecycle {GoalLifecycleReceipt("not-linked",true)}).execute(call(GoalToolCatalog.START))}.isFailure)
    }
    @Test fun callerLinkedPauseIsPreparedUntilHostStopsCallerAfterDurableReceipt()=runBlocking {
        val store=MemoryStore(scope,goal())
        val result=checkNotNull(tools(store,lifecycle=GoalToolLifecycle {GoalLifecycleReceipt(quiesced=true,deferredCallerStop=true)}).execute(call(GoalToolCatalog.PAUSE)))
        assertEquals(VerificationState.PREPARED,result.verification);assertEquals(GoalStatus.PAUSED,store.value!!.status)
        assertTrue(Json.parseToJsonElement(result.content).jsonObject.getValue("deferredCallerStop").jsonPrimitive.boolean)
        assertFalse(Json.parseToJsonElement(result.content).jsonObject.getValue("goalCompletionEvidence").jsonPrimitive.boolean)
    }
    @Test fun unlinkedCallerCannotClaimDeferredStopAndClearUsesActualCas()=runBlocking {
        val store=MemoryStore(scope,goal())
        assertTrue(runCatching {tools(store,caller="foreign",lifecycle=GoalToolLifecycle {GoalLifecycleReceipt(quiesced=true,deferredCallerStop=true)}).execute(call(GoalToolCatalog.CLEAR))}.isFailure)
        store.beforeDelete={store.value=store.value!!.copy(updatedAt=2)}
        assertTrue(runCatching {tools(store,lifecycle=GoalToolLifecycle {GoalLifecycleReceipt(quiesced=true)}).execute(call(GoalToolCatalog.CLEAR))}.isFailure)
        assertNotNull(store.value);assertEquals(0,store.deletes)
    }
    @Test fun cancellationOfOriginalJobPropagatesAndDoesNotPublishPausedState()=runBlocking {
        val store=MemoryStore(scope,goal());val entered=CompletableDeferred<Unit>()
        val tools=tools(store,lifecycle=GoalToolLifecycle {entered.complete(Unit);awaitCancellation()})
        val worker=launch {tools.execute(call(GoalToolCatalog.PAUSE));fail("cancelled result must not publish")}
        entered.await();worker.cancelAndJoin()
        assertEquals(GoalStatus.ACTIVE,store.value!!.status);assertEquals(0,store.saves)
    }
    @Test fun pagedReadRejectsChangedGoalVersionAndDebugDtosRedactPrivateValues()=runBlocking {
        val store=MemoryStore(scope,goal().copy(criteria=List(20){GoalCriterion("criterion-$it","조건 $it")}))
        val first=checkNotNull(tools(store).execute(ToolCall("read",GoalToolCatalog.GET,buildJsonObject {})))
        val payload=Json.parseToJsonElement(first.content).jsonObject
        assertEquals(8,payload.getValue("nextCriteriaOffset").jsonPrimitive.int);assertTrue(payload.getValue("criteriaPartial").jsonPrimitive.boolean)
        store.value=store.value!!.copy(updatedAt=2)
        assertTrue(runCatching {tools(store).execute(call(GoalToolCatalog.GET,extra=buildJsonObject {put("criteriaOffset",8)}))}.isFailure)
        val request=GoalLifecycleRequest(GoalLifecycleAction.START,scope,goal(),"PRIVATE_CALLER")
        assertFalse(request.toString().contains("실제 사용자"));assertFalse(request.toString().contains("PRIVATE_CALLER"))
        assertFalse(GoalLifecycleReceipt("PRIVATE_RUN").toString().contains("PRIVATE_RUN"))
    }
    @Test fun changedWorkQuiescesOldLinkedExecutionBeforeWritingAndDefersCallerStop()=runBlocking {
        val old=goal();val store=MemoryStore(scope,old);var stopped=false
        store.beforeSave={assertTrue(stopped)}
        val handler=tools(store,lifecycle=GoalToolLifecycle {request->
            assertEquals(GoalLifecycleAction.UPDATE,request.action);assertEquals(old,store.value)
            assertEquals(0,store.saves);stopped=true
            GoalLifecycleReceipt(quiesced=true,deferredCallerStop=true)
        })
        val result=checkNotNull(handler.execute(call(GoalToolCatalog.UPDATE,old,buildJsonObject {put("outcome","명시적으로 바꾼 목표")})))
        assertEquals(GoalStatus.DRAFT,store.value!!.status);assertEquals(VerificationState.PREPARED,result.verification)
        assertTrue(Json.parseToJsonElement(result.content).jsonObject.getValue("deferredCallerStop").jsonPrimitive.boolean)
    }
    @Test fun failedQuiescePreservesOldWorkAndIdenticalPatchNeverStopsIt()=runBlocking {
        val old=goal();val store=MemoryStore(scope,old)
        val failed=tools(store,lifecycle=GoalToolLifecycle {GoalLifecycleReceipt(quiesced=false)})
        assertTrue(runCatching {failed.execute(call(GoalToolCatalog.UPDATE,old,buildJsonObject {put("outcome","바꿀 목표")}))}.isFailure)
        assertEquals(old,store.value);assertEquals(0,store.saves)
        val unchanged=checkNotNull(tools(store).execute(call(GoalToolCatalog.UPDATE,old,buildJsonObject {put("outcome",old.outcome)})))
        assertEquals(old,store.value);assertEquals(0,store.saves)
        assertEquals("goal_unchanged",Json.parseToJsonElement(unchanged.content).jsonObject.getValue("status").jsonPrimitive.content)
    }
    private fun textRead(old:GoalDefinition,field:String,extra:JsonObject=buildJsonObject {})=call(GoalToolCatalog.GET,old,
        JsonObject(buildJsonObject {put("textField",field)}+extra))
    private fun textEdit(old:GoalDefinition,field:String,oldText:String,newText:String,selector:JsonObject=buildJsonObject {})=
        call(GoalToolCatalog.UPDATE,old,buildJsonObject {put("textEdits",buildJsonArray {add(JsonObject(buildJsonObject {
            put("field",field);put("oldText",oldText);put("newText",newText)
        }+selector))})})
    private fun deliver(authority:GoalReadAuthority,result:ToolResult):JsonObject {
        val preview=Json.parseToJsonElement(GoalModelContext.build(result)).jsonObject
        val evidence=listOf(buildJsonObject {
            put("tool",GoalToolCatalog.GET);put("success",result.success);put("verification",result.verification.name);put("result",preview)
        })
        authority.accept(evidence,JsonArray(evidence).toString())
        return preview
    }
    @Test fun changedUpdateDefaultsToReadGuardDenialBeforeQuiesceOrWrite()=runBlocking {
        val store=MemoryStore(scope,goal());var calls=0
        val handler=ScopedGoalTools(scope,store,{GoalToolCatalog.names},"caller",GoalToolLifecycle {calls++;GoalLifecycleReceipt(quiesced=true)})
        assertTrue(runCatching {handler.execute(call(GoalToolCatalog.UPDATE,extra=buildJsonObject {put("outcome","잘라 읽고 만든 대체 문장")}))}.isFailure)
        assertEquals(0,calls);assertEquals(0,store.saves);assertEquals(goal(),store.value)
    }
    @Test fun actualFragmentFormatterAuthorityPipelinePatchesBeyondSixThousandAndKeepsSuffix()=runBlocking {
        val old=goal().copy(outcome="P".repeat(7000)+"UNIQUE_TARGET"+"S".repeat(1500),linkedRunIds=emptyList())
        val store=MemoryStore(scope,old);val authority=GoalReadAuthority()
        val handler=ScopedGoalTools(scope,store,{GoalToolCatalog.names},"caller",GoalToolLifecycle {error("no linked run")},authority::requireEdit)
        val read=checkNotNull(handler.execute(textRead(old,"outcome",buildJsonObject {put("textOffset",7000);put("textLimit",900)})))
        val preview=deliver(authority,read)
        assertTrue(preview.getValue("goalPresent").jsonPrimitive.boolean)
        assertEquals(scope.threadId,preview.getValue("threadId").jsonPrimitive.content)
        assertEquals(scope.projectId,preview.getValue("projectId").jsonPrimitive.content)
        handler.execute(textEdit(old,"outcome","UNIQUE_TARGET","NEW"))
        assertEquals("P".repeat(7000)+"NEW"+"S".repeat(1500),store.value!!.outcome)
        assertEquals(old.criteria.map {it.text},store.value!!.criteria.map {it.text})
    }
    @Test fun exactEmptyReplacementKeepsSeventeenthConstraintAndUnmentionedFields()=runBlocking {
        val old=goal().copy(outcome="UNCHANGED_OUTCOME",constraints=List(20){if(it==17)"prefixTARGETsuffix"else"condition-$it"},linkedRunIds=emptyList())
        val store=MemoryStore(scope,old);val authority=GoalReadAuthority()
        val handler=ScopedGoalTools(scope,store,{GoalToolCatalog.names},null,GoalToolLifecycle {error("no linked run")},authority::requireEdit)
        deliver(authority,checkNotNull(handler.execute(textRead(old,"constraint",buildJsonObject {put("constraintIndex",17)}))))
        handler.execute(textEdit(old,"constraint","TARGET","",buildJsonObject {put("constraintIndex",17)}))
        assertEquals(20,store.value!!.constraints.size);assertEquals("prefixsuffix",store.value!!.constraints[17])
        assertEquals("condition-19",store.value!!.constraints[19]);assertEquals(old.outcome,store.value!!.outcome)
    }
    @Test fun fieldIdentityUniqueMatchAndCurrentVersionAreRequired()=runBlocking {
        val old=goal().copy(outcome="SAME",criteria=listOf(GoalCriterion("a","SAME")),linkedRunIds=emptyList())
        val store=MemoryStore(scope,old);val authority=GoalReadAuthority()
        val handler=ScopedGoalTools(scope,store,{GoalToolCatalog.names},null,GoalToolLifecycle {error("no linked run")},authority::requireEdit)
        deliver(authority,checkNotNull(handler.execute(textRead(old,"criterion",buildJsonObject {put("criterionId","a")}))))
        assertTrue(runCatching {handler.execute(textEdit(old,"outcome","SAME","changed"))}.isFailure)
        store.value=old.copy(updatedAt=2)
        assertTrue(runCatching {handler.execute(textRead(old,"outcome"))}.isFailure)
        assertTrue(runCatching {handler.execute(textEdit(old,"criterion","SAME","changed",buildJsonObject {put("criterionId","a")}))}.isFailure)
        val repeated=old.copy(outcome="repeat-and-repeat")
        val repeatedStore=MemoryStore(scope,repeated)
        assertTrue(runCatching {tools(repeatedStore).execute(textEdit(repeated,"outcome","repeat","x"))}.isFailure)
        assertEquals(0,store.saves);assertEquals(0,repeatedStore.saves)
    }
    @Test fun unicodeReadsAndEditsNeverSplitSurrogatePairs()=runBlocking {
        val old=goal().copy(outcome="A😀B",linkedRunIds=emptyList());val store=MemoryStore(scope,old)
        assertTrue(runCatching {tools(store).execute(textRead(old,"outcome",buildJsonObject {put("textOffset",2)}))}.isFailure)
        assertTrue(runCatching {tools(store).execute(textRead(old,"outcome",buildJsonObject {put("textOffset",1);put("textLimit",1)}))}.isFailure)
        val read=checkNotNull(tools(store).execute(textRead(old,"outcome",buildJsonObject {put("textOffset",1);put("textLimit",2)})))
        val fragment=Json.parseToJsonElement(read.content).jsonObject.getValue("textRead").jsonObject
        assertEquals("😀",fragment.getValue("text").jsonPrimitive.content);assertEquals(3,fragment.getValue("nextOffset").jsonPrimitive.int)
        assertEquals(fragment,Json.parseToJsonElement(GoalModelContext.build(read)).jsonObject.getValue("textRead"))
        assertTrue(runCatching {tools(store).execute(textEdit(old,"outcome","\uDE00","x"))}.isFailure)
        assertTrue(runCatching {tools(store).execute(textEdit(old,"outcome","😀","\uD800"))}.isFailure)
        assertEquals(old,store.value)
    }
    @Test fun encodedBudgetSelectsSmallerExactFragmentAndSuffixEofIsNotWholeField()=runBlocking {
        val old=goal().copy(outcome="\\\"".repeat(2000),linkedRunIds=emptyList());val store=MemoryStore(scope,old)
        val read=checkNotNull(tools(store).execute(textRead(old,"outcome",buildJsonObject {put("textLimit",900)})))
        val fragment=Json.parseToJsonElement(read.content).jsonObject.getValue("textRead").jsonObject
        val end=fragment.getValue("nextOffset").jsonPrimitive.int
        assertTrue(end in 1..899);assertEquals(old.outcome.substring(0,end),fragment.getValue("text").jsonPrimitive.content)
        val preview=GoalModelContext.build(read)
        assertTrue(preview.length<=2200);assertEquals(fragment,Json.parseToJsonElement(preview).jsonObject.getValue("textRead"))
        val suffixGoal=goal().copy(outcome="prefixTAIL",linkedRunIds=emptyList());val suffixStore=MemoryStore(scope,suffixGoal)
        val suffix=checkNotNull(tools(suffixStore).execute(textRead(suffixGoal,"outcome",buildJsonObject {put("textOffset",6)})))
        val authority=GoalReadAuthority();val suffixPreview=deliver(authority,suffix)
        assertFalse(suffixPreview.getValue("textRead").jsonObject.getValue("textComplete").jsonPrimitive.boolean)
        assertTrue(suffixPreview.getValue("textRead").jsonObject.getValue("nextOffset") is JsonNull)
        assertTrue(runCatching {authority.requireEdit(call(GoalToolCatalog.UPDATE,suffixGoal,buildJsonObject {put("outcome","whole replacement")}),suffixGoal)}.isFailure)
    }
}
