package com.user.localagent

import com.user.localagent.core.HarnessStep
import com.user.localagent.core.StepEvidence
import com.user.localagent.core.VerificationState
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudSteerCarryTest {
    private fun step(tool:String,state:VerificationState,noEffect:Boolean=false)=
        HarnessStep(tool,buildJsonObject {},StepEvidence(tool,state,"synthetic",effectNotApplied=noEffect))

    @Test fun verifiedReadIsDropped(){
        assertEquals(emptyList<HarnessStep>(),CloudSteerCarry.carriedSteps(true,listOf(step("search_notes",VerificationState.VERIFIED))))
    }
    @Test fun failedReadWithoutEffectIsDropped(){
        assertEquals(emptyList<HarnessStep>(),CloudSteerCarry.carriedSteps(true,listOf(step("search_notes",VerificationState.FAILED,true))))
    }
    @Test fun failedReadWithoutEffectFlagIsKept(){
        val s=listOf(step("search_notes",VerificationState.FAILED,false))
        assertEquals(s,CloudSteerCarry.carriedSteps(true,s))
    }
    @Test fun preparedStepIsKept(){
        val s=listOf(step("search_notes",VerificationState.PREPARED))
        assertEquals(s,CloudSteerCarry.carriedSteps(true,s))
    }
    @Test fun writeToolIsKeptEvenWhenVerified(){
        val s=listOf(step("update_note",VerificationState.VERIFIED))
        assertEquals(s,CloudSteerCarry.carriedSteps(true,s))
    }
    @Test fun unverifiedIsKept(){
        val s=listOf(step("search_notes",VerificationState.UNVERIFIED))
        assertEquals(s,CloudSteerCarry.carriedSteps(true,s))
    }
    @Test fun mixedListIsKeptWhole(){
        val s=listOf(step("search_notes",VerificationState.VERIFIED),step("update_note",VerificationState.VERIFIED))
        assertEquals(s,CloudSteerCarry.carriedSteps(true,s))
    }
    @Test fun localRunIsUnchanged(){
        val s=listOf(step("search_notes",VerificationState.VERIFIED))
        assertEquals(s,CloudSteerCarry.carriedSteps(false,s))
    }
    @Test fun emptyStaysEmpty(){
        assertEquals(emptyList<HarnessStep>(),CloudSteerCarry.carriedSteps(true,emptyList()))
    }
}
