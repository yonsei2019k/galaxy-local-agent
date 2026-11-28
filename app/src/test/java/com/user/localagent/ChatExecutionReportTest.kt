package com.user.localagent

import com.user.localagent.core.*
import com.user.localagent.data.ChatRunRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatExecutionReportTest {
    private val empty=JsonObject(emptyMap())
    private val fresh=ChatExecutionReport.History()
    private fun result(tool:String,body:JsonObject=empty,args:JsonObject=empty):ObservedToolOutcome =
        ObservedToolOutcome(ToolCall("one",tool,args),ToolResult("one",true,body.toString(),VerificationState.VERIFIED))
    private fun media(status:String,position:Long)=result(PcBrowserToolCatalog.READ,buildJsonObject {
        put("url","https://private-subdomain.example.org/owner/session?token=private")
        put("title","raw private title")
        put("playbackEvidence",buildJsonObject {put("playbackStatus",status);put("positionMs",position);put("durationMs",120000);put("playerSignature","private-player-token")})
    })
    private fun persisted(history:ChatExecutionReport.History)=buildJsonObject {put(ChatExecutionReport.KEY,history.json())}
    @Test fun reportKeepsTheLatestChecklistAndUsesTheRecordedResumeTime() {
        val observed=result(GoalProgressToolCatalog.UPDATE,Json.parseToJsonElement("""{"ledger":{"items":[
            {"course":"기초교육","unit":"첫 강의","status":"done","evidence":["audit"]},
            {"course":"기초교육","unit":"둘째 강의","kind":"lecture","status":"active"},
            {"course":"기초교육","unit":"이수증","status":"done","evidence":[]}]}}""").jsonObject)
        val history=ChatExecutionReport.read(persisted(ChatExecutionReport.observe(fresh,observed)))
        val context=buildJsonObject {put("hostGoalWaitKind","time");put("hostGoalWaitAt",1_800_000_000_000L)}
        val report=ChatExecutionReport.display(history,"paused","waiting_goal_condition",context)
        assertTrue(report.any {it.contains("확인 1개 · 남음 2개")})
        assertTrue(report.any {it=="하는 중 · 기초교육 / 둘째 강의"})
        assertTrue(report.any {it=="남음 · 기초교육 / 이수증"})
        assertTrue(report.last().matches(Regex(".+ 이후 다시 보기")))
    }
    @Test fun repeatedPollsReplaceOneLineAndPlaybackEndingDoesNotCertifySiteCompletion() {
        var h=ChatExecutionReport.observe(fresh,media("playing",1000))
        h=ChatExecutionReport.read(Json.parseToJsonElement(persisted(h).toString()).jsonObject)
        h=ChatExecutionReport.observe(h,media("playing",40000))
        assertEquals(1,h.lines.size)
        assertTrue(h.lines.single().text.contains("관찰 위치 0분 40초"))
        h=ChatExecutionReport.observe(h,media("paused",40000))
        h=ChatExecutionReport.observe(h,media("ended",120000))
        val lines=ChatExecutionReport.display(h,"completed",null,empty)
        assertEquals(3,h.lines.size)
        assertTrue(lines[0].contains("example.org"))
        assertTrue(lines[1].contains("일시 정지"))
        assertTrue(lines[2].contains("재생 종료"))
        assertTrue(lines.any {it.contains("진도율이나 이수 처리")})
        assertFalse(h.json().toString().contains("private"))
        assertFalse(lines.any {it.contains("%")})
    }
    @Test fun completionRequiresObservedOfficialCourseAndTheSameRequest() {
        val title="기초교육"
        val args=buildJsonObject {put(PcBrowserToolCatalog.COURSE_TITLE,title)}
        fun completed(url:String, observed:String)=result(PcBrowserToolCatalog.READ,buildJsonObject {
            put("url",url);put("verifiedCompletedCourseTitle",observed)
        },args)
        val player=ChatExecutionReport.observe(fresh,media("ended",120000))
        val official=ChatExecutionReport.observe(player,completed(PcBrowserToolCatalog.KAIRB_COMPLETED_URL,title))
        assertTrue(official.lines.last().text.contains("수강완료 목록에서 「기초교육」 확인"))
        assertFalse(ChatExecutionReport.display(official,"completed",null,empty).any {it.contains("진도율이나 이수 처리")})
        assertFalse(ChatExecutionReport.observe(player,completed("https://example.org",title)).lines.any {it.key.startsWith("course:")})
        assertFalse(ChatExecutionReport.observe(player,completed(PcBrowserToolCatalog.KAIRB_COMPLETED_URL,"다른 과정")).lines.any {it.key.startsWith("course:")})
    }
    @Test fun ledgerLectureIdentifiesTheTargetAndNeverImportsFreeformNotesOrSiteProgress() {
        fun ledger(name:String)=result(GoalProgressToolCatalog.READ,buildJsonObject {
            put("ledger",buildJsonObject {put("items",buildJsonArray {add(buildJsonObject {
                put("kind","lecture");put("status","active");put("course",name);put("unit","1강");put("note","raw private owner material and 99%")
            })})})
        })
        val h=ChatExecutionReport.observe(fresh,ledger("기초 과정"))
        assertEquals("할 일 장부 · 지금 할 것 · 기초 과정 / 1강",h.lines.single().text)
        assertEquals(h,ChatExecutionReport.observe(h,ledger("기초 과정")))
        val redacted=ChatExecutionReport.observe(h,ledger("OTP 123456"))
        assertEquals(h.lines,redacted.lines)
        assertEquals(listOf("할 일 1개 · 확인 0개 · 남음 1개"),redacted.checklist)
        assertFalse(redacted.json().toString().contains("123456"))
        assertFalse(h.json().toString().contains("99%"))
    }
    @Test fun ownerAuthenticationAndInterruptedActionsReportOnlyHostFacts() {
        val call=ToolCall("auth",PcBrowserToolCatalog.READ,empty)
        val outcome=ObservedToolOutcome(call,ToolResult(call.id,false,"owner secret code 123456",VerificationState.FAILED,"PC_BROWSER_AUTH_REQUIRED",true))
        val h=ChatExecutionReport.observe(fresh,outcome)
        assertEquals(h,ChatExecutionReport.observe(h,outcome))
        val parked=buildJsonObject {put("hostGoalWaitKind","auth")}
        assertTrue(ChatExecutionReport.display(h,"paused","waiting_goal_condition",parked).last().contains("공식 로그인"))
        assertFalse(h.json().toString().contains("123456"))
        assertEquals("그만둠",ChatExecutionReport.display(h,"cancelled",null,empty).last())
        val wake=buildJsonObject {put("hostGoalWake",buildJsonObject {put("event","OWNER_AUTH_DONE");put("token","secret")})}
        val resumed=ChatExecutionReport.display(h,"paused","waiting_goal_condition",wake)
        assertTrue(resumed.last().contains("로그인 결과를 다시 확인"))
        assertFalse(resumed.any {it.contains("secret")})
        assertEquals(fresh,ChatExecutionReport.observe(fresh,outcome.copy(call=call.copy(id="different"))))
    }
    @Test fun textCheckpointRetainsTheReportWithoutRestoringOtherCapturedRequestFields() {
        val history=ChatExecutionReport.observe(fresh,media("playing",40000))
        val old=JsonObject(persisted(history)+("allowTools" to JsonPrimitive(true))).toString()
        val next=buildJsonObject {put("allowTools",false);put("continuation","next")}.toString()
        val restored=Json.parseToJsonElement(ChatExecutionReport.retain(old,next)).jsonObject
        assertEquals(history,ChatExecutionReport.read(restored))
        assertEquals(JsonPrimitive(false),restored["allowTools"])
        assertEquals(JsonPrimitive("next"),restored["continuation"])
        val updated=persisted(ChatExecutionReport.observe(history,media("ended",120000))).toString()
        assertEquals(updated,ChatExecutionReport.retain(old,updated))
    }
    @Test fun repeatedPersistenceAndRepeatedPollsAfterAuthDoNotCreateAnotherRow()=runBlocking {
        val request=QueuedChatRequest("run","room","branch",null,"확인","auto",false,emptyList(),"user")
        val auth=ChatExecutionReport.History().append(ChatExecutionReport.Line("auth","사이트 · 로그인 필요"))
        val page=result(PcBrowserToolCatalog.READ,buildJsonObject {put("url","https://example.org");put("loginRequired",false)})
        val first=ChatExecutionReport.checkpoint(request,persisted(auth),page,2){null}
        assertEquals(2,first.lines.size)
        assertTrue(first.lines.last().text.contains("추가 로그인 요구"))
        val repeated=ChatExecutionReport.checkpoint(request,persisted(first),page,2){null}
        assertEquals(first,repeated)
        val next=ChatExecutionReport.checkpoint(request,persisted(repeated),page.copy(call=page.call.copy(id="next"),result=page.result.copy(id="next")),3){null}
        assertEquals(first.lines,next.lines)
    }
    @Test fun finalCriteriaDistinguishVerifiedOwnerConfirmedAndOutstandingWithoutExportingCriterionText() {
        val goal=GoalDefinition(threadId="room",outcome="private goal text",criteria=listOf(
            GoalCriterion(text="private title",status=GoalCriterionStatus.VERIFIED),
            GoalCriterion(text="private owner choice",status=GoalCriterionStatus.USER_CONFIRMED),
            GoalCriterion(text="private pending"),
        ))
        val context=Json.parseToJsonElement(ChatExecutionReport.goalResult("{}",goal)).jsonObject
        val report=ChatExecutionReport.display(fresh,"completed",GoalAutopilotPauseReason.FAILED_STEP.code,context)
        assertTrue(report.first()=="목표 기준 3개 · 직접 확인 1개 · 주인이 확인 1개 · 아직 1개")
        assertEquals(GoalAutopilotPauseReason.FAILED_STEP.label,report.last())
        assertFalse(context.toString().contains("private"))
        assertEquals("목표 기준 모두 확인함",ChatExecutionReport.state("completed","goal_verified_complete",context))
    }
    @Test fun reportLinesUsePlainToolNamesAndKeepEvidenceStatesApart() {
        fun outcome(tool:String,success:Boolean,state:VerificationState,notApplied:Boolean=false)=
            ObservedToolOutcome(ToolCall("c-$tool-$state",tool,empty),ToolResult("c-$tool-$state",success,"{}",state,"",notApplied))
        val read=ChatExecutionReport.observe(fresh,outcome("read_screen",true,VerificationState.VERIFIED)).lines.single().text
        val failed=ChatExecutionReport.observe(fresh,outcome("screen_action",false,VerificationState.FAILED)).lines.single().text
        val stopped=ChatExecutionReport.observe(fresh,outcome("screen_action",false,VerificationState.FAILED,true)).lines.single().text
        val unverified=ChatExecutionReport.observe(fresh,outcome("screen_action",true,VerificationState.UNVERIFIED)).lines.single().text
        assertEquals("화면 읽기 · 확인함",read)
        assertEquals("화면 누르기 · 안 됨 · 바뀌었는지 확인 필요",failed)
        assertEquals("화면 누르기 · 멈춤 · 바뀐 것 없음",stopped)
        assertEquals("화면 누르기 · 결과 못 봄",unverified)
        assertEquals(4,setOf(read,failed,stopped,unverified).size)
        listOf(read,failed,stopped,unverified).forEach {assertFalse(it,it.contains("_"))}
        assertEquals(listOf("잠깐 멈춤"),ChatExecutionReport.display(fresh,"paused","execution_not_verified",
            buildJsonObject {put(ChatExecutionReport.KEY,fresh.json())}))
    }
    @Test fun hostNoticeIsShownAsAStatusLineInsteadOfAChatSentence() {
        val context=RunStatusLabels.write(empty,RunNotice("마지막 동작이 됐는지 아직 모름"))
        assertEquals(listOf("잠깐 멈춤 · 마지막 동작이 됐는지 아직 모름"),ChatExecutionReport.display(fresh,"paused","execution_not_verified",context))
        assertEquals(listOf("끝남 · 바꾼 대상 다시 확인함"),ChatExecutionReport.display(fresh,"completed",null,
            RunStatusLabels.write(empty,RunNotice("바꾼 대상 다시 확인함"))))
        assertEquals(listOf("끝내지 못함 · 문제가 생겨 끝내지 못함"),ChatExecutionReport.display(fresh,"failed","IllegalStateException",
            RunStatusLabels.write(empty,RunNotice("문제가 생겨 끝내지 못함"))))
        assertTrue(ChatExecutionReport.display(fresh,"paused","execution_not_verified",empty).isEmpty())
    }
    @Test fun reopeningRunUsesDurableLinesAndContinuationIsScopedToItsParent()=runBlocking {
        val parent=QueuedChatRequest("parent","room","branch",null,"원래 작업","auto",false,emptyList(),"user")
        val child=parent.copy(runId="child",browserAuthParentRunId="parent")
        val report=ChatExecutionReport.observe(fresh,media("playing",40000))
        val context=JsonObject(Json.parseToJsonElement(parent.toJson()).jsonObject+persisted(report))
        val row=ChatRunRecord(id="parent",threadId="room",branchId="branch",status="paused",errorCode="waiting_browser_auth",contextJson=context.toString())
        val h=ChatExecutionReport.checkpoint(child,empty,null){row}
        assertEquals(report.lines.first(),h.lines.first())
        assertEquals(2,h.lines.size)
        assertTrue(h.lines.last().text.contains("로그인"))
        assertEquals(h,ChatExecutionReport.checkpoint(child,persisted(h),null){error("must use stored snapshot")})
        assertEquals(fresh,ChatExecutionReport.checkpoint(child,empty,null){row.copy(branchId="other")})

    }
}
