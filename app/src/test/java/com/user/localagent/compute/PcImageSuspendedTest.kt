package com.user.localagent.compute

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class PcImageSuspendedTest {
    @Test fun explicitPauseStopsRetryWithOnlyActuallySavedProgress(){
        val saved=PcImageSuspended.fromReport("resumable",true,5,50,true)!!
        assertEquals(5,saved.savedStep)
        assertTrue(saved.message!!.contains("5단계 계산을 저장"))
        val inputOnly=PcImageSuspended.fromReport("suspended",true,0,50,true)!!
        assertTrue(inputOnly.message!!.contains("입력과 설정"))
        assertFalse(inputOnly.message!!.contains("계산을 저장"))
        assertFalse(CancellationException::class.java.isInstance(inputOnly))
    }
    @Test fun legacyAbsentOrCrashFalsePauseFieldDoesNotDisableBoundedRecovery(){
        for(paused in listOf(null,false))for(phase in listOf("resumable","suspended"))
            assertNull(PcImageSuspended.fromReport(phase,paused,5,50,true))
        for(phase in listOf("queued","loading","sampling","completed","failed","cancelled"))
            assertNull(PcImageSuspended.fromReport(phase,true,5,50,true))
    }
    @Test fun invalidSavedProgressCannotBecomeAPreservedCalculationClaim(){
        for(saved in listOf(-1,5))assertThrows(IllegalArgumentException::class.java){PcImageSuspended.fromReport("suspended",true,saved,4,true)}
        assertThrows(IllegalArgumentException::class.java){PcImageSuspended.fromReport("suspended",true,0,51,true)}
    }

    @Test fun pauseNeedsARealCheckpointAndCleanupReceipt() {
        assertTrue(PcImageSuspended.canPause(true,"sampling",false,1,50))
        assertFalse(PcImageSuspended.canPause(true,"sampling",false,0,50))
        assertFalse(PcImageSuspended.canPause(false,"sampling",false,5,50))
        assertFalse(PcImageSuspended.canPause(true,"completed",false,50,50))
        assertFalse(PcImageSuspended.canPause(true,"sampling",true,5,50))
        assertNull(PcImageSuspended.fromReport("resumable",true,5,50,false))
        assertNull(PcImageSuspended.fromReport("resumable",true,5,50,null))
    }
}
