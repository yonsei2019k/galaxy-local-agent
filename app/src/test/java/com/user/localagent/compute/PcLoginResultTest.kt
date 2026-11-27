package com.user.localagent.compute

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PcLoginResultTest {
    private fun decode(status:Int, body:String)=PcLoginResult.decode(status,Json.parseToJsonElement(body).jsonObject)

    @Test fun requireSuccessfulReadbackAndAcceptOlderWorkers() {
        assertEquals(PcLoginStage.SIGNED_IN, decode(200,"""{"signedIn":true}""").stage)
        assertEquals(PcLoginStage.UNCONFIRMED, decode(200,"""{"signedIn":false,"outcome":"signed_in"}""").stage)
        assertEquals(PcLoginStage.UNCONFIRMED, decode(409,"""{"signedIn":true}""").stage)
        assertEquals(PcLoginStage.UNCONFIRMED, decode(200,"""{"signedIn":false,"outcome":"new_stage"}""").stage)
    }

    @Test fun distinguishOwnerActionFromTransportOrCooldown() {
        assertEquals(PcLoginStage.OWNER_VERIFICATION, decode(200,"""{"signedIn":false,"outcome":"owner_verification"}""").stage)
        assertEquals(PcLoginStage.OWNER_CODE_ON_SITE, decode(200,"""{"signedIn":false,"outcome":"owner_code_on_site"}""").stage)
        assertEquals(PcLoginStage.CORRECT_INPUT, decode(200,"""{"signedIn":false,"outcome":"correct_input"}""").stage)
        assertEquals(PcLoginStage.FORM_EXPIRED, decode(409,"""{"error":"OWNER_LOGIN_FORM_CHANGED"}""").stage)
        assertEquals(PcLoginResult(PcLoginStage.RETRY_LATER,17),
            decode(200,"""{"signedIn":false,"outcome":"retry_later","retryAfterSeconds":17}"""))
        assertEquals(PcLoginStage.RETRY_LATER, decode(429,"""{"error":"OWNER_LOGIN_WAIT"}""").stage)
    }
}
