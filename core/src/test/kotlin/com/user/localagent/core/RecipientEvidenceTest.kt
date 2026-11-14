package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RecipientEvidenceTest {
    private val first=ContactCandidate("동명이인","00000000001")
    private val second=ContactCandidate("동명이인","00000000002")
    private fun sms(number:String=first.phoneNumber,body:String="테스트")=ToolCall("sms","prepare_sms",buildJsonObject{put("phoneNumber",number);put("message",body)})
    @Test fun unknownOrUnboundNumberCannotOpenAnIntent(){
        assertTrue(runCatching{RecipientEvidence("").consume(sms())}.isFailure)
        assertTrue(runCatching{RecipientEvidence("연락처 찾아 문자 준비").consume(sms())}.isFailure)
    }
    @Test fun uniqueObservedRecipientAndExplicitOwnerNumberRemainUsable(){
        val guard=RecipientEvidence("연락처 찾아 문자 준비");guard.observe(listOf(first));guard.consume(sms())
        RecipientEvidence("000-0000-0001로 문자 준비").consume(sms())
    }
    @Test fun ambiguousNumberRequiresIndependentSelectionWithNoDefault(){
        val guard=RecipientEvidence("연락처 찾아 문자 준비");guard.observe(listOf(first,second))
        assertEquals(2,guard.choices(sms()).size)
        assertTrue(runCatching{guard.consume(sms())}.isFailure)
        val selected=guard.approve(sms(),second.phoneNumber)
        assertEquals(second.phoneNumber,selected.arguments.getValue("phoneNumber").jsonPrimitive.content)
        guard.consume(selected)
        assertTrue(runCatching{guard.consume(selected)}.isFailure)
    }
    @Test fun choiceCannotAuthorizeAnotherBodyOrUnobservedNumber(){
        val guard=RecipientEvidence("연락처 찾아 문자 준비");guard.observe(listOf(first,second));guard.approve(sms(),second.phoneNumber)
        assertTrue(runCatching{guard.consume(sms(second.phoneNumber,"변경된 본문"))}.isFailure)
        assertTrue(runCatching{guard.approve(sms(),"00000000003")}.isFailure)
    }
    @Test fun narrowingReadsAndDuplicateFormattingCannotHidePriorAmbiguity(){
        val guard=RecipientEvidence("연락처 찾아 문자 준비");guard.observe(listOf(first,second));guard.observe(listOf(first))
        assertTrue(runCatching{guard.consume(sms())}.isFailure)
        val unique=RecipientEvidence("문자 준비");unique.observe(listOf(first,first.copy(phoneNumber="000-0000-0001")));unique.consume(sms())
    }
    @Test fun incompleteResultsCannotBeApprovedAsACompleteChoice(){
        val guard=RecipientEvidence("문자 준비");guard.observe(listOf(first),true)
        assertTrue(runCatching{guard.approve(sms(),first.phoneNumber)}.isFailure)
    }
    @Test fun explicitCurrentOwnerChoiceDoesNotAuthorizeADifferentCandidate(){
        val guard=RecipientEvidence("00000000002로 문자 준비");guard.observe(listOf(first,second));guard.consume(sms(second.phoneNumber))
        assertTrue(runCatching{guard.consume(sms())}.isFailure)
    }
}
