package com.user.localagent.inference

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PcResidencyTest {
    private val sha="03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8"
    private fun health(resident:JsonElement,structured:Boolean=true)=buildJsonObject {
        put("structured_output",structured)
        put("llm_models",buildJsonObject{put("pc-text",sha)})
        put("llmResident",resident)
    }
    private fun resident(model:String="pc-text",ctx:Int=8192,mine:Boolean=true)=buildJsonObject{put("modelId",model);put("contextSize",ctx);put("ownerMatches",mine)}

    @Test fun ownersIdenticalResidentModelSkipsTheLoad(){
        assertTrue(PcResidency.reusable(health(resident()),"pc-text","03b74727a860a563",8192))
    }
    @Test fun unloadedReplacedForeignOrOldWorkerAlwaysLoads(){
        assertFalse(PcResidency.reusable(health(JsonNull),"pc-text","03b74727a860a563",8192))          // image job / idle unload / restart
        assertFalse(PcResidency.reusable(health(resident(model="gemma-e2b")),"pc-text","03b74727a860a563",8192))
        assertFalse(PcResidency.reusable(health(resident(ctx=4096)),"pc-text","03b74727a860a563",8192))
        assertFalse(PcResidency.reusable(health(resident(mine=false)),"pc-text","03b74727a860a563",8192))
        assertFalse(PcResidency.reusable(health(resident(),structured=false),"pc-text","03b74727a860a563",8192))
        assertFalse(PcResidency.reusable(health(resident()),"pc-text","ffffffffffffffff",8192))
        assertFalse(PcResidency.reusable(buildJsonObject{put("structured_output",true)},"pc-text","03b74727a860a563",8192)) // worker without llmResident
        assertFalse(PcResidency.reusable(null,"pc-text","03b74727a860a563",8192))
    }
}
