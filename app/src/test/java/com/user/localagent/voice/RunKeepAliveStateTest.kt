package com.user.localagent.voice

import org.junit.Assert.*
import org.junit.Test

class RunKeepAliveStateTest {
    @Test fun startRequestIsNotAnActiveReceiptAndOnlyMatchingTokenAcknowledges(){
        val r=RunKeepAliveRegistry{0};val token=r.begin("fixture")!!
        assertFalse(r.snapshot("fixture").active)
        r.acknowledge("fixture","stale",true);assertFalse(r.snapshot("fixture").active)
        r.acknowledge("fixture",token,true);assertTrue(r.snapshot("fixture").active)
        r.acknowledge("fixture",token,false,"FOREGROUND_SERVICE_STOPPED");assertFalse(r.snapshot("fixture").active)
        r.acknowledge("fixture",token,true);assertFalse(r.snapshot("fixture").active)
        val next=r.begin("fixture")!!;assertNotEquals(token,next)
        r.acknowledge("fixture",token,false);r.acknowledge("fixture",next,true);assertTrue(r.snapshot("fixture").active)
    }
    @Test fun timeoutAndCapacityNeverPromoteOrEvictAnActiveRun(){
        var now=0L;val r=RunKeepAliveRegistry{now}
        val pending=r.begin("pending")!!;now=5000
        assertEquals("FOREGROUND_START_TIMEOUT",r.snapshot("pending").reason)
        r.acknowledge("pending",pending,true);assertFalse(r.snapshot("pending").active)
        repeat(64){val id="active$it";val token=r.begin(id)!!;r.acknowledge(id,token,true)}
        assertNull(r.begin("overflow"));assertTrue(r.snapshot("active0").active)
        assertEquals("FOREGROUND_NOT_REQUESTED",r.snapshot("unknown").reason)
    }
}
