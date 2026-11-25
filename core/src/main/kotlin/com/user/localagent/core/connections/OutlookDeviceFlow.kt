package com.user.localagent.core.connections

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Poll only a device grant initiated by the current owner until expiry or cancellation. */
object OutlookDeviceFlow {
    suspend fun awaitToken(
        challenge:JsonObject, nowMillis:()->Long, waitMillis:suspend (Long)->Unit,
        stillActive:()->Boolean, showCode:suspend (String)->Unit, poll:suspend (String)->JsonObject,
    ):JsonObject {
        val device=challenge.getValue("device_code").jsonPrimitive.content
        val visible=challenge.getValue("user_code").jsonPrimitive.content
        require(device.isNotBlank() && visible.matches(Regex("[A-Za-z0-9-]{4,24}")))
        val lifetime=challenge.getValue("expires_in").jsonPrimitive.long.coerceAtMost(1200)
        var interval=(challenge["interval"]?.jsonPrimitive?.longOrNull ?: 5L).coerceAtLeast(5)
        require(lifetime>0 && interval<=120 && stillActive())
        val deadline=nowMillis()+lifetime*1000
        showCode(visible)
        while(true){
            val remaining=deadline-nowMillis()
            check(remaining>0 && stillActive()){"로그인 코드가 만료됐어요."}
            waitMillis(minOf(remaining,interval*1000))
            currentCoroutineContext().ensureActive()
            check(nowMillis()<deadline && stillActive()){"로그인 코드가 만료됐어요."}
            val result=poll(device)
            currentCoroutineContext().ensureActive()
            check(stillActive() && nowMillis()<deadline)
            when(result["error"]?.jsonPrimitive?.contentOrNull){
                "authorization_pending" -> continue
                "slow_down" -> interval+=5
                null -> return result
                else -> error("Microsoft 로그인을 완료하지 못했어요.")
            }
        }
    }
}
