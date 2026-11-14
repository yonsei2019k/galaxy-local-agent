package com.user.localagent.inference

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Metadata-only synthetic lifecycle proof. No native/model/Android/provider access. */
class NativeProgressTrackerTest {
    @org.junit.Test fun displayDescribesActualPrefillAndHiddenOnlySamplingWithoutInventingAnswerProgress(){
        val value=NativeGenerationProgress(java.util.UUID.randomUUID().toString(),NativeGenerationPhase.PREFILL,128,640,0,0,0,1000)
        org.junit.Assert.assertEquals("입력 계산 128/640 토큰",value.publicDescription())
        org.junit.Assert.assertEquals("출력 계산 24 토큰 · 화면 전달 0 바이트",value.copy(phase=NativeGenerationPhase.DECODE,processedPromptTokens=640,sampledTokens=24,rawUtf8Bytes=100).publicDescription())
    }
    @Test fun separatesPreparationSamplingPrefillAndInvisibleDecodeWithoutClaimingNoTokens() {
        val id=UUID.randomUUID().toString();val t=NativeProgressTracker(id)
        assertEquals(NativeGenerationPhase.PREPARE,t.accept(1,0,0,0,0,0,0)!!.phase)
        assertNotNull(t.accept(2,0,704,0,0,0,2))
        assertNotNull(t.accept(3,320,704,0,0,0,20))
        val first=t.accept(4,704,704,0,0,0,100)!!
        assertEquals(id,first.requestId);assertEquals(0,first.sampledTokens)
        val hidden=t.accept(4,704,704,20,120,0,400)!!
        assertEquals(20,hidden.sampledTokens);assertEquals(120L,hidden.rawUtf8Bytes);assertEquals(0L,hidden.visibleUtf8Bytes)
        val visible=t.accept(4,704,704,21,125,30,500)!!
        assertEquals(30L,visible.visibleUtf8Bytes)
        assertEquals(NativeGenerationPhase.FINISHED,t.accept(5,704,704,21,125,30,501)!!.phase)
        assertNull(t.accept(4,704,704,22,130,35,502))
    }
    @Test fun rejectsMalformedOrDecreasingMetadataWithoutCorruptingLatestSnapshot() {
        val t=NativeProgressTracker(UUID.randomUUID().toString())
        assertNull(t.accept(999,0,0,0,0,0,0));assertNull(t.accept(4,10,10,0,0,0,0))
        assertNotNull(t.accept(1,0,0,0,0,0,0));assertNotNull(t.accept(2,0,60,0,0,0,1))
        assertNotNull(t.accept(3,60,60,0,0,0,2));val last=t.accept(4,60,60,10,50,10,3)
        assertNull(t.accept(3,60,60,10,50,10,4));assertNull(t.accept(4,59,60,10,50,10,4))
        assertNull(t.accept(4,60,61,10,50,10,4));assertNull(t.accept(4,60,60,9,50,10,4))
        assertNull(t.accept(4,60,60,10,49,10,4));assertNull(t.accept(4,60,60,10,50,9,4))
        assertNull(t.accept(4,60,60,10,50,10,2));assertNull(t.accept(4,60,60,2057,50,10,4))
        assertNull(t.accept(4,60,60,10,1_048_577,10,4));assertEquals(last,t.latest)
    }
    @Test fun requestInstancesDoNotBorrowPriorRequestCountersAndRejectSampleBeforePrefill() {
        val a=NativeProgressTracker(UUID.randomUUID().toString());val b=NativeProgressTracker(UUID.randomUUID().toString())
        assertNotNull(a.accept(1,0,0,0,0,0,0));assertNotNull(a.accept(4,60,60,10,70,0,80))
        assertNotNull(b.accept(1,0,0,0,0,0,0));assertNotEquals(a.latest!!.requestId,b.latest!!.requestId)
        assertEquals(0,b.latest!!.sampledTokens)
        assertNull(b.accept(2,0,60,1,10,0,1));assertNull(b.accept(4,0,60,0,0,0,1))
        assertEquals(NativeGenerationPhase.PREPARE,b.latest!!.phase)
    }
}
