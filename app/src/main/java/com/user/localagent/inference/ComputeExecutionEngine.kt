package com.user.localagent.inference

import com.user.localagent.compute.ComputeResource
import kotlinx.coroutines.flow.StateFlow

/** A compute route is observable execution metadata, never a tool or approval authority. */
interface ComputeExecutionEngine : InferenceEngine {
    val computeLocation: StateFlow<ComputeResource?>
    val waitingForResource: StateFlow<ComputeResource?>
    val executionLocation: String
    suspend fun <T> withPhoneResource(cleanup: suspend () -> Unit, block: suspend () -> T): T =
        throw UnsupportedOperationException("이 실행 엔진의 로컬 확장 경로가 연결되지 않았습니다.")
    suspend fun <T> withPcResource(cleanup: suspend () -> Unit, block: suspend () -> T): T =
        throw UnsupportedOperationException("이 실행 엔진의 PC 확장 경로가 연결되지 않았습니다.")
}
