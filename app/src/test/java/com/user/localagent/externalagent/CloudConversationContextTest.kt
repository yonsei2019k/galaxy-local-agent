package com.user.localagent.externalagent

import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CloudConversationContextTest {
    private fun binding(run:Int=99)=CloudConversationContext.Binding(
        UUID(0,run.toLong()).toString(),"same-thread","same-branch","same-project",7,11,
        ExternalAgentSettings.MODEL_ID,true,"act")
    private fun turn(run:Int,role:String="user",text:String="원문 $run")=
        CloudConversationContext.Turn(binding(run),role,text,sourceConfirmed=true)
    private fun task(run:Int=1,status:String="paused",unknown:Boolean?=false,
        owner:String="원래 사용자 요청",goal:String="원래 목표")=
        CloudConversationContext.PreviousTask(binding(run),status,owner,goal,unknown,sourceConfirmed=true)
    private fun build(turns:List<CloudConversationContext.Turn> =emptyList(),previous:CloudConversationContext.PreviousTask?=null)=
        CloudConversationContext.build(binding(),turns,previous)
    private fun turns(value:JsonObject)=value.getValue("recentTurns").jsonArray.map{it.jsonObject}
    private fun previous(value:JsonObject)=value.getValue("previousTask").jsonObject

    @Test fun noEligibleSourceProducesNoAdditionalContext(){
        assertNull(build())
        assertNull(build(listOf(turn(1).copy(sourceConfirmed=false))))
    }

    @Test fun exactWireShapePreservesTextAndOriginalMessageOrder(){
        val result=checkNotNull(build(listOf(turn(1,text="한 줄\n두  칸 😀"),turn(1,"assistant","확인한 결과"))))
        assertEquals(setOf("version","recentTurns","previousTask"),result.keys)
        assertEquals(1,result.getValue("version").jsonPrimitive.int);assertEquals(JsonNull,result["previousTask"])
        val entries=turns(result);assertEquals(listOf("user","assistant"),entries.map{it.getValue("role").jsonPrimitive.content})
        assertEquals("한 줄\n두  칸 😀",entries.first().getValue("text").jsonPrimitive.content)
        assertEquals(setOf("role","text","sourceRunId"),entries.first().keys)
    }

    @Test fun oldLocalOnlyOrUnpermittedRequestsCannotSupplyContext(){
        val candidate=turn(1)
        for(source in listOf(candidate.binding.copy(modelId="paired-pc-text-v1"),
            candidate.binding.copy(allowExternalAgent=false),candidate.binding.copy(mode="plan"),
            candidate.binding.copy(mode="research"),candidate.binding.copy(authorizationRevision=null),
            candidate.binding.copy(pcRevision=null))){assertNull(build(listOf(candidate.copy(binding=source))))}
    }

    @Test fun revokedPermissionOrLocalCurrentRequestDoesNotGetOldCloudContent(){
        for(current in listOf(binding().copy(allowExternalAgent=false),binding().copy(modelId="gemma-e2b"),
            binding().copy(mode="plan"),binding().copy(authorizationRevision=null),binding().copy(pcRevision=null))){
            assertNull(CloudConversationContext.build(current,listOf(turn(1)),task(1,unknown=true)))
        }
    }

    @Test fun projectBranchThreadAndBothRevisionChangesAreExcluded(){
        val source=turn(1)
        for(changed in listOf(source.binding.copy(projectId="other-project"),source.binding.copy(projectId=null),
            source.binding.copy(branchId="other-branch"),source.binding.copy(threadId="other-thread"),
            source.binding.copy(authorizationRevision=6),source.binding.copy(pcRevision=10),source.binding.copy(mode="debug"))){
            assertNull(build(listOf(source.copy(binding=changed)),task().copy(binding=changed)))
        }
    }

    @Test fun ambiguousDeletedCurrentAndMalformedRunSourcesAreExcluded(){
        val values=listOf(turn(1).copy(sourceConfirmed=false),turn(2).copy(deleted=true),turn(99),
            turn(3).copy(binding=binding(3).copy(runId="3")),turn(4,role="tool"),turn(5,text="   "))
        assertNull(build(values));assertNull(build(previous=task().copy(deleted=true)))
        assertNull(build(previous=task().copy(sourceConfirmed=false)))
        assertNull(build(previous=task(99)))
    }

    @Test fun latestEightEligibleTurnsRetainTheirOriginalOrder(){
        val result=checkNotNull(build((1..12).map{turn(it)}))
        assertEquals((5..12).map{binding(it).runId},turns(result).map{it.getValue("sourceRunId").jsonPrimitive.content})
    }

    @Test fun surrogateBoundaryIsNeverSplitAndClippingIsVisible(){
        val value="a".repeat(CloudConversationContext.MAX_TURN_CHARS-15)+"😀".repeat(20)
        val text=turns(checkNotNull(build(listOf(turn(1,text=value))))).single().getValue("text").jsonPrimitive.content
        assertTrue(text.length<=CloudConversationContext.MAX_TURN_CHARS);assertTrue(text.contains("생략"))
        assertWellFormed(text)
    }

    @Test fun previousTaskCarriesUnknownEffectsAsDataWithoutPromotingItsStatus(){
        for(status in listOf("completed","paused","failed","cancelled","interrupted")){
            for(uncertain in listOf<Boolean?>(true,null)){
                val data=previous(checkNotNull(build(previous=task(status=status,unknown=uncertain))))
                assertEquals(status,data.getValue("status").jsonPrimitive.content)
                assertTrue(data.getValue("unresolvedEffects").jsonPrimitive.boolean)
                assertEquals(setOf("sourceRunId","status","ownerRequest","goal","unresolvedEffects"),data.keys)
            }
        }
        assertFalse(previous(checkNotNull(build(previous=task(unknown=false)))).getValue("unresolvedEffects").jsonPrimitive.boolean)
    }

    @Test fun runningQueuedAndUnknownTaskStatesAreNotInventedAsPriorCompletion(){
        for(status in listOf("running","queued","waiting_approval","unknown","")){
            assertNull(build(previous=task(status=status,unknown=true)))
        }
    }

    @Test fun byteBudgetShrinksTaskProseWithoutLosingUncertaintyOrChangingCurrentRequest(){
        val owner="漢".repeat(4000);val goal="😀".repeat(2500)
        val current=binding();val original=current.copy()
        val result=checkNotNull(CloudConversationContext.build(current,(1..8).map{turn(it,text="漢".repeat(3000))},task(unknown=true,owner=owner,goal=goal)))
        assertTrue(result.toString().toByteArray(Charsets.UTF_8).size<=CloudConversationContext.MAX_UTF8_BYTES)
        assertTrue(previous(result).getValue("unresolvedEffects").jsonPrimitive.boolean)
        val ownerText=previous(result).getValue("ownerRequest").jsonPrimitive.content
        val goalText=previous(result).getValue("goal").jsonPrimitive.content
        assertTrue(ownerText.length<=3000);assertTrue(goalText.length<=1500)
        assertTrue(ownerText.contains("생략"));assertTrue(goalText.isEmpty()||goalText.contains("생략"))
        assertWellFormed(ownerText);assertWellFormed(goalText);assertEquals(original,current)
        assertEquals("漢".repeat(4000),owner)
    }

    @Test fun jsonEscapesAreIncludedInByteBudgetAndUnknownFlagCannotBeDropped(){
        val result=checkNotNull(build(previous=task(unknown=null,owner="\u0000".repeat(4000),goal="\u0001".repeat(3000))))
        assertTrue(result.toString().toByteArray(Charsets.UTF_8).size<=12_000)
        assertTrue(previous(result).getValue("unresolvedEffects").jsonPrimitive.boolean)
        assertTrue(previous(result).getValue("ownerRequest").jsonPrimitive.content.contains("생략"))
    }

    @Test fun budgetDoesNotDiscardNewerTurnToIncludeAnOlderShortTurn(){
        val values=listOf(turn(1,text="old-short"),turn(2,text="漢".repeat(1500)),turn(3,text="漢".repeat(1500)),turn(4,text="漢".repeat(1500)))
        val selected=turns(checkNotNull(build(values))).map{it.getValue("sourceRunId").jsonPrimitive.content}
        assertEquals(listOf(binding(3).runId,binding(4).runId),selected)
        assertFalse(selected.contains(binding(1).runId))
    }

    @Test fun sameDebugAndPersonalScopeAreEligibleOnlyWhenBothCapturesMatch(){
        val current=binding().copy(mode="debug",projectId=null)
        val source=turn(1).copy(binding=binding(1).copy(mode="debug",projectId=null))
        assertNotNull(CloudConversationContext.build(current,listOf(source)))
        assertNull(CloudConversationContext.build(current,listOf(source.copy(binding=binding(1)))))
    }

    @Test fun longPreviousTaskCannotStarveLatestUserAssistantExchange(){
        val user=turn(8,text="사용자 앞부분\n"+"漢".repeat(4000)+"\n최신 정정: 금요일 16시")
        val assistant=turn(8,"assistant","응답 앞부분\n"+"漢".repeat(4000)+"\n확인하지 못한 부분은 대기")
        val result=checkNotNull(build(listOf(user,assistant),task(unknown=true,owner="漢".repeat(6000),goal="漢".repeat(6000))))
        val selected=turns(result)
        assertEquals(listOf("user","assistant"),selected.map{it.getValue("role").jsonPrimitive.content})
        assertTrue(selected[0].getValue("text").jsonPrimitive.content.endsWith("최신 정정: 금요일 16시"))
        assertTrue(selected[1].getValue("text").jsonPrimitive.content.endsWith("확인하지 못한 부분은 대기"))
        assertTrue(previous(result).getValue("unresolvedEffects").jsonPrimitive.boolean)
        assertEquals(binding(1).runId,previous(result).getValue("sourceRunId").jsonPrimitive.content)
        assertEquals("paused",previous(result).getValue("status").jsonPrimitive.content)
        assertTrue(result.toString().toByteArray(Charsets.UTF_8).size<=12_000)
    }

    @Test fun escapedRecentPairStaysPresentEvenWhenNeitherFullTurnCanFitTogether(){
        val user=turn(8,text="USER_HEAD"+"\u0000".repeat(6000)+"USER_TAIL_CORRECTION")
        val assistant=turn(8,"assistant","ASSISTANT_HEAD"+"\u0001".repeat(6000)+"ASSISTANT_TAIL")
        val result=checkNotNull(build(listOf(user,assistant),task(unknown=null,owner="\u0002".repeat(6000),goal="\u0003".repeat(6000))))
        val selected=turns(result)
        assertEquals(2,selected.size)
        for((index,head,tail) in listOf(Triple(0,"USER_HEAD","USER_TAIL_CORRECTION"),Triple(1,"ASSISTANT_HEAD","ASSISTANT_TAIL"))){
            val text=selected[index].getValue("text").jsonPrimitive.content
            assertTrue(text.startsWith(head));assertTrue(text.endsWith(tail));assertTrue(text.contains("생략"))
            assertTrue(text.length<=1500);assertWellFormed(text)
        }
        assertTrue(previous(result).getValue("unresolvedEffects").jsonPrimitive.boolean)
        assertTrue(result.toString().toByteArray(Charsets.UTF_8).size<=12_000)
    }

    @Test fun headAndTailClippingKeepsLiteralLateCorrectionWithoutInventingMeaning(){
        val original="원래 요청: 서울역\n"+"자료 ".repeat(2000)+"\n정정: 부산역만 검색, 예약 금지"
        val result=checkNotNull(build(listOf(turn(1,text=original))))
        val excerpt=turns(result).single().getValue("text").jsonPrimitive.content
        assertTrue(excerpt.startsWith("원래 요청: 서울역"));assertTrue(excerpt.endsWith("정정: 부산역만 검색, 예약 금지"))
        assertTrue(excerpt.contains("생략"));assertTrue(excerpt.length<=1500)
        val data=previous(checkNotNull(build(previous=task(owner=original,goal=original,unknown=true))))
        assertTrue(data.getValue("ownerRequest").jsonPrimitive.content.endsWith("정정: 부산역만 검색, 예약 금지"))
        assertTrue(data.getValue("goal").jsonPrimitive.content.endsWith("정정: 부산역만 검색, 예약 금지"))
        assertEquals(setOf("sourceRunId","status","ownerRequest","goal","unresolvedEffects"),data.keys)
    }

    @Test fun sameRunPartnerIsPreferredAndOutputStillUsesMessageOrder(){
        val values=listOf(turn(2,text="same-run user"),turn(3,text="unrelated later user"),
            turn(2,"assistant","漢".repeat(3000)+"same-run assistant tail"))
        val result=checkNotNull(build(values,task(unknown=true,owner="漢".repeat(6000),goal="漢".repeat(6000))))
        val selected=turns(result)
        assertEquals("same-run user",selected.first().getValue("text").jsonPrimitive.content)
        assertEquals("assistant",selected.last().getValue("role").jsonPrimitive.content)
        assertEquals(binding(2).runId,selected.last().getValue("sourceRunId").jsonPrimitive.content)
        assertTrue(result.toString().toByteArray(Charsets.UTF_8).size<=12_000)
    }

    @Test fun emojiHeadAndTailBoundariesStayWellFormedUnderEscapedTaskBudget(){
        val text="😀".repeat(3000)+"마지막 정정 😀"
        val result=checkNotNull(build(listOf(turn(1,text=text),turn(1,"assistant",text)),
            task(unknown=true,owner="\u0000".repeat(5000),goal="😀".repeat(3000))))
        for(item in turns(result)){
            val excerpt=item.getValue("text").jsonPrimitive.content
            assertWellFormed(excerpt);assertTrue(excerpt.endsWith("마지막 정정 😀"))
        }
        assertTrue(previous(result).getValue("unresolvedEffects").jsonPrimitive.boolean)
        assertTrue(result.toString().toByteArray(Charsets.UTF_8).size<=12_000)
    }

    @Test fun latestPairReservationNeverReintroducesAnIneligiblePartner(){
        val forbidden=turn(1,text="OTHER_BRANCH_PRIVATE").copy(binding=binding(1).copy(branchId="other-branch"))
        val currentAssistant=turn(2,"assistant","漢".repeat(3000)+"latest assistant")
        val result=checkNotNull(build(listOf(forbidden,currentAssistant),task(unknown=true,owner="漢".repeat(6000),goal="漢".repeat(6000))))
        assertEquals(1,turns(result).size);assertFalse(result.toString().contains("OTHER_BRANCH_PRIVATE"))
        assertTrue(turns(result).single().getValue("text").jsonPrimitive.content.endsWith("latest assistant"))
    }

    private fun assertWellFormed(value:String){
        value.forEachIndexed{index,char->
            if(char.isHighSurrogate())assertTrue(index+1<value.length&&value[index+1].isLowSurrogate())
            if(char.isLowSurrogate())assertTrue(index>0&&value[index-1].isHighSurrogate())
        }
    }
}
