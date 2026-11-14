package com.user.localagent.inference

import org.junit.Assert.*
import org.junit.Test

class PcResidencyTest {
    private val options=InferenceOptions(contextSize=8192)
    @Test fun sameModelAndOptionsWithinWindowSkipTheRoundTrip(){
        assertTrue(PcResidency.reusable("pc-text","pc-text",options,options,"/m","/m",1_000L,1_000L+30_000_000_000L))
    }
    @Test fun anythingDifferentOrStaleReloads(){
        val now=1_000L+30_000_000_000L
        assertFalse(PcResidency.reusable("pc-text","pc-text",options,options,"/m","/m",0L,now))
        assertFalse(PcResidency.reusable("pc-text","pc-text",options,options,"/m","/m",1_000L,1_000L+PcResidency.REUSE_NANOS))
        assertFalse(PcResidency.reusable("gemma-e2b","pc-text",options,options,"/m","/m",1_000L,now))
        assertFalse(PcResidency.reusable("pc-text","pc-text",options,options.copy(contextSize=4096),"/m","/m",1_000L,now))
        assertFalse(PcResidency.reusable("pc-text","pc-text",options,options,"/a","/m",1_000L,now))
        assertFalse(PcResidency.reusable("pc-text","pc-text",null,options,"/m","/m",1_000L,now))
    }
}
