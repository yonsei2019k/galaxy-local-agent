package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class PhoneImageQualityTest {
    @Test fun phoneQualityDoesNotReuseTheLegacyLcmStepBudget(){
        assertEquals(40,PcComputeContract.steps(false,null,true))
        assertEquals(50,PcComputeContract.steps(false,50,true))
        assertThrows(IllegalArgumentException::class.java){PcComputeContract.steps(false,8,true)}
        assertThrows(IllegalArgumentException::class.java){PcComputeContract.steps(false,40,false)}
    }
    @Test fun callsTakePriorityEvenWhenMemoryAndTemperatureAreGood(){
        assertNotNull(PhoneImageQuality.throttleReason(true,false,false,8L*1024*1024*1024))
        assertTrue(PhoneImageQuality.throttleMillis(true,false,false,8L*1024*1024*1024)>0)
        assertEquals(0,PhoneImageQuality.throttleMillis(false,false,false,8L*1024*1024*1024))
    }
    @Test fun resourcePressureSlowsComputationWithinABoundedYieldBudget(){
        assertTrue(PhoneImageQuality.throttleMillis(false,false,false,PhoneImageQuality.RESERVE_BYTES-1)>0)
        assertEquals(0,PhoneImageQuality.throttleMillis(false,false,false,PhoneImageQuality.RESERVE_BYTES))
        assertTrue(PhoneImageQuality.throttleMillis(false,true,false,8L*1024*1024*1024) in 1..2000)
        assertTrue(PhoneImageQuality.throttleMillis(false,false,true,8L*1024*1024*1024)>0)
        assertTrue(PhoneImageQuality.throttleMillis(false,false,false,8L*1024*1024*1024,true)>0)
    }
}
