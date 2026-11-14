package com.user.localagent.compute

import com.user.localagent.inference.InferenceOptions

/** Captured by the host, never derived from model output or inherited from later global settings. */
data class PcTextAuthority(val revision:Long,val allowCloudText:Boolean=false) {
    init {require(revision>=0)}
    fun matches(currentRevision:Long)=revision==currentRevision
    fun usesCloudText(options:InferenceOptions)=allowCloudText&&!options.imagePreparation&&!options.audioEnabled&&!options.visionEnabled
}
