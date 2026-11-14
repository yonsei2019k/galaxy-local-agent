package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class PcImageProgressPolicyTest {
    @Test fun onlyExplicitLockContentionClaimsAnotherOperation(){
        assertEquals("다른 PC 연산이 끝나기를 기다리는 중",PcImageProgressPolicy.label("queued","waiting_compute"))
        for(stage in listOf(null,"unknown","verifying_models","preparing_runtime","admitting"))
            assertFalse(PcImageProgressPolicy.label("queued",stage).contains("다른 PC 연산"))
        assertEquals("PC 이미지 모델 파일 검사 중",PcImageProgressPolicy.label("queued","verifying_models"))
        assertEquals("PC 이미지 실행 환경 준비 중",PcImageProgressPolicy.label("queued","preparing_runtime"))
    }
    @Test fun currentPhaseOverridesStaleStageAndResourceReasonsRemainDistinct(){
        assertEquals("PC 이미지 모델 로딩 중",PcImageProgressPolicy.label("loading","waiting_compute"))
        assertEquals("PC에서 장면을 정교하게 만드는 중",PcImageProgressPolicy.label("sampling","verifying_models"))
        assertEquals("PC 메모리 여유 대기",PcImageProgressPolicy.label("waiting_resources","waiting_memory"))
        assertEquals("PC GPU 여유 대기",PcImageProgressPolicy.label("waiting_resources","waiting_gpu"))
        assertEquals("PC 메모리 또는 GPU 여유 대기",PcImageProgressPolicy.label("waiting_resources",null))
    }
    @Test fun pollingSlowsWhileUnchangedAndResetsOnProgress(){
        assertEquals(1000L,PcImageProgressPolicy.pollDelayMillis(0))
        assertEquals(2000L,PcImageProgressPolicy.pollDelayMillis(2))
        assertEquals(5000L,PcImageProgressPolicy.pollDelayMillis(50))
        assertEquals(1000L,PcImageProgressPolicy.pollDelayMillis(-1))
    }
}
