package com.user.localagent.planning

import com.user.localagent.core.*
import com.user.localagent.inference.InferenceOptions
import com.user.localagent.models.ChosenInference
import com.user.localagent.models.InstalledModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EvidenceResearchWorkflowTest {
    private val chosen=ChosenInference(InstalledModel("synthetic","Synthetic","/not-loaded","synthetic",true,"gguf"),InferenceOptions(),"injected")
    private val first="https://europepmc.org/article/MED/12345"
    private val doi="https://doi.org/10.1000/example"
    private val offered=setOf("search_publications","verify_reference","search_web","read_webpage")
    private fun plan(tool:String="search_publications")=SemanticRequestPlan(SemanticAction.PUBLIC_RESEARCH,"교대 근무와 수면의 근거를 비교한다","교대 근무","shift work sleep",SemanticSourceKind.NONE,setOf(tool),listOf("체계적 고찰과 단일 연구를 구분"),false,"")
    private fun call(tool:String,args:JsonObject)=buildJsonObject {put("type","tool_call");put("tool",tool);put("arguments",args)}.toString()
    private val finish="{\"type\":\"finish\"}"

    @Test fun observedPublicationCanLeadToReferenceCheckInsteadOfAnotherWebSearch()=runBlocking {
        val calls=mutableListOf<ToolCall>();var decisions=0
        val workflow=EvidenceResearchWorkflow(EvidenceToolRunner {call,_->
            calls+=call
            if(call.name=="search_publications")EvidenceObservation("{\"doi\":\"10.1000/example\",\"abstract\":\"observed synthetic evidence\"}",true,setOf(first),1)
            else EvidenceObservation("{\"matchType\":\"exact_doi\"}",true,setOf(doi),1)
        },ResearchActionDecider {input,_,_,_->
            assertTrue(input.contains("observed synthetic evidence"))
            assertTrue(input.contains("체계적 고찰과 단일 연구를 구분"))
            if(decisions++==0)call("verify_reference",buildJsonObject {put("doi","10.1000/example")}) else finish
        })
        val packet=workflow.gather(plan(),chosen,offered)
        assertEquals(listOf("search_publications","verify_reference"),calls.map {it.name})
        assertEquals(setOf(first,doi),packet.observedUrls)
        assertTrue(packet.steps.all {it.verified})
        assertFalse(packet.partial)
    }

    @Test fun inventedUrlPrivateToolDuplicateAndDuplicateFinishFieldsCannotExecute()=runBlocking {
        val initial=buildJsonObject {put("query","shift work sleep");put("limit",3)}
        for(raw in listOf(
            call("read_webpage",buildJsonObject {put("url","https://invented.example/no")}),
            call("search_notes",buildJsonObject {put("query","secret")}),
            call("search_publications",initial),
            call("search_publications",buildJsonObject {put("limit",3);put("query","shift work sleep")}),
            "{\"type\":\"finish\",\"type\":\"finish\"}"
        )) {
            var executions=0;var decisions=0
            val workflow=EvidenceResearchWorkflow(EvidenceToolRunner {_,_->executions++;EvidenceObservation("observed",true,setOf(first),1)},ResearchActionDecider {_,_,_,_->decisions++;raw})
            val packet=workflow.gather(plan(),chosen,offered)
            assertEquals(1,executions);assertEquals(2,decisions);assertTrue(packet.partial)
        }
    }

    @Test fun networkBudgetIncludesWebProviderAttemptsAndStopsBeforeAnotherDecision()=runBlocking {
        var calls=0;var decisions=0
        val workflow=EvidenceResearchWorkflow(EvidenceToolRunner {call,remaining->
            calls++
            if(call.name=="search_web") {assertEquals(5,remaining);EvidenceObservation("web",true,setOf(first),5)}
            else EvidenceObservation("publication",true,setOf(first),1)
        },ResearchActionDecider {_,_,_,_->decisions++;call("search_web",buildJsonObject {put("query","a distinct public question")})})
        val packet=workflow.gather(plan(),chosen,offered)
        assertEquals(2,calls);assertEquals(1,decisions);assertTrue(packet.partial)
        assertEquals(6,Json.parseToJsonElement(packet.json).jsonObject.getValue("requestCount").jsonPrimitive.int)
    }

    @Test fun projectDisabledToolIsNeverInTheDecisionOrAllowedToExecute()=runBlocking {
        var executions=0
        val workflow=EvidenceResearchWorkflow(EvidenceToolRunner {_,_->executions++;EvidenceObservation("publication",true,setOf(first),1)},ResearchActionDecider {input,schema,_,_->
            assertFalse(input.contains("\"name\":\"verify_reference\""));assertFalse(schema.contains("verify_reference"))
            call("verify_reference",buildJsonObject {put("doi","10.1000/example")})
        })
        val packet=workflow.gather(plan(),chosen,offered-"verify_reference")
        assertEquals(1,executions);assertTrue(packet.partial)
    }

    @Test fun exactNotFoundIsValidLookupAndDoesNotBecomeFalseCitation()=runBlocking {
        val lookup="https://api.crossref.org/works/10.1000/not-registered"
        val workflow=EvidenceResearchWorkflow(EvidenceToolRunner {call,_->
            assertEquals("citation",call.arguments.keys.first())
            EvidenceObservation("{\"status\":\"not_found\",\"matchType\":\"not_found\",\"provesFalseCitation\":false}",true,setOf(lookup),1,sourceUrls=emptySet())
        },ResearchActionDecider {_,_,_,_->finish})
        val packet=workflow.gather(plan("verify_reference"),chosen,offered)
        assertTrue(packet.steps.single().verified);assertFalse(packet.partial)
        assertEquals(0,packet.sourceCount);assertEquals(setOf(lookup),packet.observedUrls)
        assertTrue(packet.json.contains("not_found"));assertTrue(packet.json.contains("별도"))
    }

    @Test fun transportExceptionConsumesUnverifiedAllowanceAndCancellationPropagates()=runBlocking {
        var decisions=0
        val failed=EvidenceResearchWorkflow(EvidenceToolRunner {_,_->error("transport")},ResearchActionDecider {_,_,_,_->decisions++;finish}).gather(plan(),chosen,offered)
        assertEquals(0,decisions);assertTrue(failed.steps.none {it.verified});assertTrue(failed.partial)
        assertTrue(failed.json.contains("unavailable"))
        try {
            EvidenceResearchWorkflow(EvidenceToolRunner {_,_->throw CancellationException("cancel")},ResearchActionDecider {_,_,_,_->finish}).gather(plan(),chosen,offered)
            fail("cancellation swallowed")
        }catch(_:CancellationException) { /* expected */ }
    }

    @Test fun partialProviderResultsRemainPartialEvenWhenSourcesExist()=runBlocking {
        val packet=EvidenceResearchWorkflow(EvidenceToolRunner {_,_->EvidenceObservation("partial provider",true,setOf(first),1,partial=true)},ResearchActionDecider {_,_,_,_->finish}).gather(plan(),chosen,offered)
        assertTrue(packet.steps.single().verified);assertTrue(packet.partial)
    }

    @Test fun modelMayFinishButUnperformedPlannedReferenceCheckRemainsExplicitlyIncomplete()=runBlocking {
        val packet=EvidenceResearchWorkflow(EvidenceToolRunner {_,_->EvidenceObservation("observed publication",true,setOf(first),1)},ResearchActionDecider {_,_,_,_->finish})
            .gather(plan().copy(requiredTools=setOf("search_publications","verify_reference")),chosen,offered)
        assertTrue(packet.partial)
        val root=Json.parseToJsonElement(packet.json).jsonObject
        assertEquals(listOf("verify_reference"),root.getValue("unverifiedPlannedTools").jsonArray.map {it.jsonPrimitive.content})
        assertEquals(1,packet.steps.size)
    }

    @Test fun validPublicPlanWithOpeningOrRetailStillExecutesSupportedEvidenceAndReportsRemainingWork()=runBlocking {
        for(extra in listOf("open_url","search_shopping")) {
            var calls=0
            val packet=EvidenceResearchWorkflow(EvidenceToolRunner {_,_->calls++;EvidenceObservation("publication",true,setOf(first),1)},ResearchActionDecider {_,_,_,_->finish})
                .gather(plan().copy(requiredTools=setOf("search_publications",extra),targetType=if(extra=="search_shopping")SemanticTargetType.PHYSICAL_PRODUCT else SemanticTargetType.GENERAL),chosen,offered+extra)
            assertEquals(1,calls);assertTrue(packet.partial)
            assertTrue(packet.writerJson!!.contains(extra))
        }
    }
}
