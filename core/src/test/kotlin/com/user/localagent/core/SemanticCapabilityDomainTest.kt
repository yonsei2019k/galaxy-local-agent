package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SemanticCapabilityDomainTest {
    private fun proposal(type:String,tools:List<String>)=buildJsonObject {
        put("action","PUBLIC_RESEARCH");put("goal","조건에 맞는 후보와 실제 확인 가능한 가격 범위를 조사한다")
        put("target","요청한 대상");put("targetType",type);put("publicQuery","공개 주제 조건")
        put("sourceKind","none");put("requiredTools",JsonArray(tools.map(::JsonPrimitive)))
        put("constraints",buildJsonArray {add("확인하지 않은 전체 최저가를 주장하지 않는다")})
        put("usesPrivateData",false);put("question","")
    }.toString()

    @Test fun aServiceCannotUseARetailPriceProviderRegardlessOfItsWording(){
        for(type in listOf("SERVICE","GENERAL","DEVICE","PERSONAL_RECORD","CONTENT")){
            assertTrue(runCatching {SemanticRequestPlanning.parse(proposal(type,listOf("search_shopping")))}.isFailure)
        }
    }
    @Test fun physicalProductOffersKeepTheirActualCapability(){
        val plan=SemanticRequestPlanning.parse(proposal("PHYSICAL_PRODUCT",listOf("search_shopping")))
        assertEquals(SemanticTargetType.PHYSICAL_PRODUCT,plan.targetType)
        assertEquals(setOf("search_shopping"),SemanticRequestPlanning.allowedTools(plan))
    }
    @Test fun serviceInformationCanUsePublicSourcesWithoutClaimingALiveQuote(){
        val plan=SemanticRequestPlanning.parse(proposal("SERVICE",listOf("search_web","read_webpage")))
        assertEquals(SemanticTargetType.SERVICE,plan.targetType)
        assertEquals("SERVICE",SemanticRequestPlanning.toIntentPlan(plan).defaults["targetType"])
        assertTrue(SemanticRequestPlanning.contextJson(plan).contains("SERVICE"))
    }
    @Test fun legacyPlansCannotBypassTheProductScopeGuard(){
        val json=Json.parseToJsonElement(proposal("PHYSICAL_PRODUCT",listOf("search_shopping"))).jsonObject
        val legacy=JsonObject(json.filterKeys {it!="targetType"}).toString()
        assertTrue(runCatching {SemanticRequestPlanning.parse(legacy)}.isFailure)
    }
}
