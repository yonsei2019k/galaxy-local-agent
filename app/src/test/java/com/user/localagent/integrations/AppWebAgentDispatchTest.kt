package com.user.localagent.integrations

import com.user.localagent.compute.BrowserAgentTools
import com.user.localagent.core.AppBrowserToolCatalog
import com.user.localagent.core.PcBrowserToolCatalog
import com.user.localagent.core.ToolCall
import com.user.localagent.core.VerificationState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppWebAgentDispatchTest {
    private fun open(url:String)=ToolCall("open",AppBrowserToolCatalog.OPEN,buildJsonObject{put("url",url)})
    private fun read()=ToolCall("read",AppBrowserToolCatalog.READ,buildJsonObject{})

    @Test fun phonePresentationStaysOnPhoneAndRequiresAnActualReceipt()=runBlocking {
        val url="https://example.com/help"
        val call=open(url).copy(arguments=buildJsonObject {put("url",url);put("showOnPhone",true)})
        val browser=BrowserAgentTools(AppBrowserToolCatalog,"fixture",{true}){_,body->
            assertEquals(JsonPrimitive(true),body["showOnPhone"])
            buildJsonObject {put("error","PAGE_UNREACHABLE");put("phoneBrowserVisible",true)}
        }
        val result=browser.execute(call,pcFallback={_,_->error("Do not replace a visible phone request with PC reading")})
        val receipt=Json.parseToJsonElement(result.content).jsonObject
        assertFalse(result.success)
        assertEquals(JsonPrimitive(true),receipt["phoneBrowserVisible"])
        assertEquals(JsonPrimitive("PAGE_UNREACHABLE"),receipt["error"])
    }

    @Test
    fun plannedReadReobservesTheSamePageAndNeverUsesStalePhoneControls()=runBlocking {
        val url="https://example.com/help"
        val tools=BrowserAgentTools(AppBrowserToolCatalog,"fixture",{true}){op,_->
            if(op=="open")buildJsonObject{put("error","PAGE_TIMEOUT")}
            else buildJsonObject{put("url","https://example.com/unrelated");put("text","Old handset page")}
        }
        var reads=0
        val fallback:com.user.localagent.compute.BrowserBackend={action,body->
            assertEquals("open",action)
            assertEquals(url,body.getValue("url").jsonPrimitive.content)
            buildJsonObject { put("url",url);put("text","Observation ${++reads}");put("snapshotId","pc-ref") }
        }
        val opened=tools.execute(open(url),pcFallback=fallback)
        val refreshed=tools.execute(read(),pcFallback=fallback)
        assertTrue(opened.content.contains("Observation 1"))
        assertTrue(refreshed.content.contains("Observation 2"))
        assertFalse(refreshed.content.contains("Old handset page"))
        assertFalse(refreshed.content.contains("pc-ref"))
        assertFalse(tools.execute(click(AppBrowserToolCatalog.ACTION)).success)
        assertFalse(tools.execute(read()).success)
    }

    @Test
    fun nextOpenClearsOldTargetAndFreshPcAuthenticationPausesTheRead()=runBlocking {
        val url="https://example.com/help"
        val tools=BrowserAgentTools(AppBrowserToolCatalog,"fixture",{true}){op,_->
            if(op=="open")buildJsonObject{put("error","PAGE_TIMEOUT")}
            else buildJsonObject{put("url","https://other.example/");put("text","Unrelated")}
        }
        val fallback:com.user.localagent.compute.BrowserBackend={_,_->buildJsonObject{put("url",url);put("text","Public")}}
        assertTrue(tools.execute(open(url),pcFallback=fallback).success)
        val auth=tools.execute(read(),pcFallback={_,_->buildJsonObject{put("loginRequired",true)}})
        assertFalse(auth.success)
        assertTrue(auth.content.contains("https://example.com"))
        assertTrue(tools.execute(open(url),pcFallback=fallback).success)
        assertFalse(tools.execute(open("https://example.com/?state=opaque"),pcFallback=fallback).success)
        var usedPriorTarget=false
        tools.execute(read(),pcFallback={_,_->usedPriorTarget=true;buildJsonObject{}})
        assertFalse(usedPriorTarget)
    }

    @Test
    fun recoveredOpenUsesFreshHandsetReadWhenTheExpectedPageLoads()=runBlocking {
        val url="https://example.com/help"
        val tools=BrowserAgentTools(AppBrowserToolCatalog,"fixture",{true}){op,_->
            if(op=="open")buildJsonObject{put("error","PAGE_TIMEOUT")}
            else buildJsonObject{put("url",url);put("text","New handset evidence");put("snapshotId","handset-ref")}
        }
        var pcReads=0
        val fallback:com.user.localagent.compute.BrowserBackend={_,_->
            pcReads++
            buildJsonObject{put("url",url);put("text","PC evidence")}
        }
        assertTrue(tools.execute(open(url),pcFallback=fallback).success)
        val result=tools.execute(read(),pcFallback=fallback)
        assertEquals(1,pcReads)
        assertTrue(result.content.contains("New handset evidence"))
        assertTrue(result.content.contains("handset-ref"))
    }

    @Test
    fun failedPhoneReadUsesSamePublicPageOnPcWithoutTransferringControls()=runBlocking {
        val url="https://example.com/help"
        for(code in listOf("PAGE_UNREADABLE","PAGE_UNREACHABLE")){
            val tools=BrowserAgentTools(AppBrowserToolCatalog,"fixture",{true}){_,_->buildJsonObject{put("error",code)}}
            val result=tools.execute(open(url),pcFallback={op,body->
                assertEquals("open",op)
                assertEquals(url,body.getValue("url").jsonPrimitive.content)
                buildJsonObject{put("url",url);put("text","Public evidence");put("snapshotId","foreign-ref")}
            })
            assertEquals(VerificationState.VERIFIED,result.verification)
            assertTrue(result.content.contains("PC"))
            assertFalse(result.content.contains("foreign-ref"))
        }
        val tls=BrowserAgentTools(AppBrowserToolCatalog,"fixture",{true}){_,_->buildJsonObject{put("error","TLS_ERROR")}}
        val result=tls.execute(open(url),pcFallback={_,_->error("TLS failure must stay on the phone")})
        assertFalse(result.success)
        assertTrue(result.content.contains("TLS_ERROR"))
    }

    @Test
    fun phoneAuthUsesOnlyOriginalOriginAndNeverOpensPcForAnAuthenticationUrl()=runBlocking {
        val tools=BrowserAgentTools(AppBrowserToolCatalog,"fixture",{true}){_,_->buildJsonObject{put("error","PAGE_TIMEOUT")}}
        val url="https://example.com/help"
        val result=tools.execute(open(url),pcFallback={_,_->buildJsonObject{
            put("loginRequired",true);put("url","https://signin.example.net/login?token=secret");put("text","private view")
        }})
        assertFalse(result.success)
        assertTrue(result.effectNotApplied)
        assertTrue(result.content.contains("https://example.com"))
        assertFalse(result.content.contains("private view"))
        assertFalse(result.content.contains("secret"))
        for(blocked in listOf("https://example.com/help?token=secret","https://example.com/auth/continue","http://example.com/help")){
            var invoked=false
            val denied=tools.execute(open(blocked),pcFallback={_,_->invoked=true;buildJsonObject{}})
            assertFalse(invoked)
            assertFalse(denied.success)
            assertTrue(denied.content.contains("PAGE_TIMEOUT"))
        }
    }

    @Test
    fun retryNeverClaimsAnUnrelatedPcPageAsTheRequestedObservation()=runBlocking {
        val tools=BrowserAgentTools(AppBrowserToolCatalog,"fixture",{true}){_,_->buildJsonObject{put("error","PAGE_TIMEOUT")}}
        val result=tools.execute(open("https://example.com/help"),pcFallback={_,_->buildJsonObject{
            put("url","https://other.example/help");put("text","Unrelated page")
        }})
        assertFalse(result.success)
        assertTrue(result.content.contains("PAGE_TIMEOUT"))
    }

    @Test
    fun onlyTheHostsApprovalReachesTheAppBackend() = runBlocking {
        val calls=mutableListOf<JsonObject>()
        val tools=BrowserAgentTools(AppBrowserToolCatalog,"fixture",{true}){_,body->
            calls+=body
            buildJsonObject{put("status","approval_required");put("approvalId","a".repeat(32))}
        }
        val call=click(AppBrowserToolCatalog.ACTION,spoof=true)
        val withoutApproval=tools.execute(call)
        assertEquals(1,calls.size)
        assertNull(calls.single()["approvedAppSnapshot"])
        assertTrue(withoutApproval.effectNotApplied)
        assertFalse(withoutApproval.success)
        tools.execute(call,approvedSnapshot="snapshot")
        assertEquals(2,calls.size)
        assertEquals("snapshot",calls.last().getValue("approvedAppSnapshot").jsonPrimitive.content)
    }

    @Test
    fun preDispatchRefusalAndUnknownEffectRemainDistinct() = runBlocking {
        var code="SNAPSHOT_STALE"
        val tools=BrowserAgentTools(AppBrowserToolCatalog,"fixture",{true}){_,_->
            buildJsonObject{put("error",code)}
        }
        val call=click(AppBrowserToolCatalog.ACTION)
        assertTrue(tools.execute(call).effectNotApplied)
        code="ELEMENT_OBSCURED"
        val obscured=tools.execute(call)
        assertTrue(obscured.effectNotApplied)
        assertFalse(obscured.success)
        assertTrue(obscured.content.contains("페이지를 다시 읽고"))
        code="ACTION_OUTCOME_UNKNOWN"
        val unknown=tools.execute(call)
        assertFalse(unknown.effectNotApplied)
        assertFalse(unknown.success)
        assertEquals(VerificationState.UNVERIFIED,unknown.verification)
    }

    @Test
    fun readIsAllowedButRiskyPcActionsNeedTheCurrentReviewedSnapshot() = runBlocking {
        val actions=mutableListOf<JsonObject>()
        val tools=BrowserAgentTools(PcBrowserToolCatalog,"fixture",{true}){operation,body->
            if(operation=="read")snapshot(risky=true)
            else {
                actions+=body
                if(actions.size==1)challenge() else snapshot(risky=true)
            }
        }
        val read=ToolCall("read",PcBrowserToolCatalog.READ,JsonObject(emptyMap()))
        assertFalse(tools.review(read).first)
        assertTrue(tools.execute(read).success)
        val action=click(PcBrowserToolCatalog.ACTION).let {call->
            call.copy(arguments=JsonObject(call.arguments+("approvalId" to JsonPrimitive("model-value"))))
        }
        assertTrue(tools.review(action).first)
        for(approved in listOf(null,"stale")){
            assertTrue(tools.execute(action,approved).effectNotApplied)
            assertTrue(actions.isEmpty())
        }
        assertTrue(tools.execute(action,approvedSnapshot="snapshot").success)
        assertEquals(2,actions.size)
        assertNull(actions.first()["approvalId"])
        assertEquals("a".repeat(32),actions.last().getValue("approvalId").jsonPrimitive.content)
    }

    @Test
    fun unexpectedWorkerChallengeDoesNotGrantConsentOrTriggerASecondDispatch() = runBlocking {
        var attempts=0
        val tools=BrowserAgentTools(PcBrowserToolCatalog,"fixture",{true}){operation,_->
            if(operation=="read")snapshot(risky=false) else {attempts++;challenge()}
        }
        tools.execute(ToolCall("read",PcBrowserToolCatalog.READ,JsonObject(emptyMap())))
        val action=click(PcBrowserToolCatalog.ACTION)
        assertFalse(tools.review(action).first)
        val denied=tools.execute(action)
        assertEquals(1,attempts)
        assertFalse(denied.success)
        assertTrue(denied.effectNotApplied)
    }

    @Test
    fun pcTypingOutsideSearchNeedsApprovalBeforeBackendDispatch() = runBlocking {
        var attempts=0
        val tools=BrowserAgentTools(PcBrowserToolCatalog,"fixture",{true}){operation,_->
            if(operation!="read")attempts++
            snapshot(risky=false,tag="input",label="Description")
        }
        tools.execute(ToolCall("read",PcBrowserToolCatalog.READ,JsonObject(emptyMap())))
        val action=ToolCall("type",PcBrowserToolCatalog.ACTION,buildJsonObject {
            put("action","type");put("snapshotId","snapshot");put("ref","e1");put("text","Synthetic text")
        })
        assertTrue(tools.review(action).first)
        assertTrue(tools.execute(action).effectNotApplied)
        assertEquals(0,attempts)
        assertTrue(tools.execute(action,approvedSnapshot="snapshot").success)
        assertEquals(1,attempts)
    }

    private val ruleSource="https://fixture.example.test/list"
    private val ruleDestination="https://fixture.example.test/details"

    @Test fun aRejectedMediaProgramCanBeRewrittenAndRunAsReadOnly()=runBlocking {
        var reads=0
        val tools=BrowserAgentTools(AppBrowserToolCatalog,"fixture",{true}){op,body->
            assertEquals("read",op)
            assertEquals(JsonPrimitive(true),body["observeMedia"])
            reads++
            buildJsonObject {
                put("snapshotId","s$reads");put("url","https://fixture.example.test/media")
                put("text","Static slide")
                put("media",buildJsonObject {
                    put("status","observed");put("playerId","${"a".repeat(32)}:1");put("epoch",0)
                    put("positionMs",reads*1000);put("durationMs",12000);put("paused",false)
                    put("seeking",false);put("ended",false);put("readyState",4);put("rate",1)
                })
            }
        }
        fun observe(expression:String)=ToolCall("media",AppBrowserToolCatalog.READ,buildJsonObject {
            put("playbackRule",expression);put("waitSeconds",5)
        })
        assertTrue(tools.execute(observe("playing || window.play()")).effectNotApplied)
        assertEquals(0,reads)
        val result=tools.execute(observe("playing && advancing && !buffering"))
        val evidence=Json.parseToJsonElement(result.content).jsonObject.getValue("playbackEvidence").jsonObject
        assertEquals(JsonPrimitive("playing"),evidence["playbackStatus"])
        assertEquals(JsonPrimitive("matched"),evidence["mediaRuleResult"])
        assertTrue(reads>=2)
        assertFalse(result.content.contains("playerId"))
    }

    @Test fun unavailablePhoneMediaOffersPublicPcOpenAndUsesFreshPcEvidence()=runBlocking {
        val url="https://fixture.example.test/video"
        val phone=BrowserAgentTools(AppBrowserToolCatalog,"phone",{true}){operation,_->
            buildJsonObject {
                put("snapshotId","phone-snapshot");put("url",url)
                if(operation=="read")putJsonObject("media"){put("status","frame_unavailable")}
            }
        }
        fun observe(tool:String)=ToolCall("media",tool,buildJsonObject {
            put("playbackRule","paused");put("waitSeconds",3)
        })
        val appRead=observe(AppBrowserToolCatalog.READ)
        phone.execute(open(url))
        assertFalse(phone.execute(appRead).content.contains("pcHandoffUrl"))
        val handoff=phone.execute(appRead,pcFallback={_,_->error("handoff must not dispatch a PC action")})
        val offered=Json.parseToJsonElement(handoff.content).jsonObject.getValue("pcHandoffUrl").jsonPrimitive.content
        assertEquals(url,offered)
        val pc=BrowserAgentTools(PcBrowserToolCatalog,"pc",{true}){operation,body->
            buildJsonObject {
                put("snapshotId","fresh-pc-snapshot");put("url",url)
                if(operation=="open")assertEquals(url,body.getValue("url").jsonPrimitive.content)
                else {
                    assertEquals("read",operation)
                    assertEquals(JsonPrimitive(true),body["observeMedia"])
                    putJsonObject("media") {
                        put("status","observed");put("playerId","${"b".repeat(32)}:1");put("epoch",0)
                        put("positionMs",100);put("durationMs",4000);put("paused",true)
                        put("ended",false);put("seeking",false);put("readyState",4);put("rate",1)
                    }
                }
            }
        }
        assertTrue(pc.execute(ToolCall("pc-open",PcBrowserToolCatalog.OPEN,buildJsonObject {put("url",offered)})).success)
        val read=pc.execute(observe(PcBrowserToolCatalog.READ))
        assertTrue(read.content.contains("fresh-pc-snapshot"))
        assertTrue(read.content.contains("\"mediaRuleResult\":\"matched\""))
        assertFalse(read.content.contains("phone-snapshot"))
    }

    private fun ruleClick()=ToolCall("rule",AppBrowserToolCatalog.ACTION,buildJsonObject {
        put("action","click");put("snapshotId","snapshot");put("ref","e1")
        put("expectedLinkLabel","Details");put("verifyPageText","Product details")
    })

    private fun rulePage(id:String,url:String=ruleSource,refs:List<String> = listOf("e1"),
        marker:Boolean=false,performed:Boolean=false)=buildJsonObject {
        put("snapshotId",id);put("url",url);put("truncated",false)
        put("text",if(marker) "Product details" else "Products")
        if(performed)put("performed",buildJsonObject {put("action","click")})
        putJsonArray("elements"){
            for(ref in refs)addJsonObject {
                put("ref",ref);put("tag","a");put("label","Details");put("ruleHref",ruleDestination)
                for(flag in listOf("disabled","editable","sensitive","needsApproval"))put(flag,false)
            }
        }
    }

    @Test fun writtenRuleRebindsAfterExplicitNoEffectAndVerifiesTheNewPage()=runBlocking {
        var reads=0
        val dispatched=mutableListOf<String>()
        val tools=BrowserAgentTools(AppBrowserToolCatalog,"fixture",{true}){op,body->
            when(op){
                "open"->rulePage("snapshot")
                "read"->{reads++;rulePage("fresh$reads",refs=listOf("e${reads+1}"))}
                "act"->{
                    assertEquals(ruleDestination,body.getValue("ruleHref").jsonPrimitive.content)
                    assertFalse("expectedLinkLabel" in body)
                    dispatched+=body.getValue("ref").jsonPrimitive.content
                    if(dispatched.size==1)buildJsonObject {put("error","SNAPSHOT_STALE")}
                    else rulePage("done",ruleDestination,emptyList(),marker=true,performed=true)
                }
                else->error(op)
            }
        }
        val opened=tools.execute(open(ruleSource))
        assertTrue(opened.content.contains("recoverable-link"))
        assertFalse(opened.content.contains(ruleDestination))
        val result=tools.execute(ruleClick())
        assertTrue(result.success)
        assertTrue(result.content.contains("\"navigationRuleStatus\":\"verified\""))
        assertEquals(listOf("e2","e3"),dispatched)
    }

    @Test fun ruleRefusesAmbiguityScopeChangesAndAnAlreadyVisibleMarker()=runBlocking {
        for(fresh in listOf(rulePage("fresh",refs=listOf("e2","e3")),
                rulePage("fresh","https://fixture.example.test/other"),
                rulePage("fresh",marker=true))){
            var dispatched=0
            val tools=BrowserAgentTools(AppBrowserToolCatalog,"fixture",{true}){op,_->
                when(op){
                    "open"->rulePage("snapshot")
                    "read"->fresh
                    else->{dispatched++;error("unexpected action")}
                }
            }
            tools.execute(open(ruleSource))
            assertTrue(tools.execute(ruleClick()).effectNotApplied)
            assertEquals(0,dispatched)
        }
    }

    @Test fun uncertainNavigationAllowsObservationButCannotDispatchAgain()=runBlocking {
        var dispatched=0
        val tools=BrowserAgentTools(AppBrowserToolCatalog,"fixture",{true}){op,_->
            when(op){
                "open"->rulePage("snapshot")
                "read"->rulePage("snapshot")
                else->{dispatched++;buildJsonObject{put("error","ACTION_OUTCOME_UNKNOWN")}}
            }
        }
        tools.execute(open(ruleSource))
        val uncertain=tools.execute(ruleClick())
        assertEquals(VerificationState.UNVERIFIED,uncertain.verification)
        assertFalse(uncertain.effectNotApplied)
        assertTrue(tools.execute(read()).success)
        assertTrue(tools.execute(ruleClick()).effectNotApplied)
        assertTrue(tools.execute(click(AppBrowserToolCatalog.ACTION)).effectNotApplied)
        assertEquals(1,dispatched)
    }

    private fun challenge()=buildJsonObject {put("status","approval_required");put("approvalId","a".repeat(32))}

    private fun snapshot(risky:Boolean,tag:String="button",label:String="Details")=buildJsonObject {
        put("snapshotId","snapshot");put("url","https://fixture.example.test")
        putJsonArray("elements"){
            addJsonObject {put("ref","e1");put("tag",tag);put("label",label);put("needsApproval",risky)}
        }
    }

    private fun click(tool:String,spoof:Boolean=false)=ToolCall("call",tool,buildJsonObject {
        put("action","click");put("snapshotId","snapshot");put("ref","e1")
        if(spoof)put("approvedAppSnapshot","snapshot")
    })
}
