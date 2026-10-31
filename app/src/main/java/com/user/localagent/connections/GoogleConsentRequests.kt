package com.user.localagent.connections

import java.util.UUID

internal data class GoogleConsentRequest(val token:String,val serviceId:String,val write:Boolean)

/** Nonsecret pending consent survives Activity replacement, but never accepts an unbound result. */
internal class GoogleConsentRequestStore {
    private var pending:GoogleConsentRequest?=null
    private val consumed=ArrayDeque<String>()
    @Synchronized fun begin(serviceId:String,write:Boolean):GoogleConsentRequest {
        check(pending==null){"이전 Google 권한 확인을 먼저 마쳐주세요."}
        return GoogleConsentRequest(UUID.randomUUID().toString(),serviceId,write).also{pending=it}
    }
    @Synchronized fun peek(token:String?):GoogleConsentRequest?=pending?.takeIf{it.token==token}
    @Synchronized fun claim(token:String?):GoogleConsentRequest? {
        if(token==null||token in consumed)return null
        return pending?.takeIf{it.token==token}?.also{pending=null;remember(token)}
    }
    @Synchronized fun cancel(token:String?){if(pending?.token==token&&token!=null){pending=null;remember(token)}}
    @Synchronized fun wasConsumed(token:String?):Boolean=token!=null&&token in consumed
    private fun remember(token:String){consumed.addLast(token);while(consumed.size>32)consumed.removeFirst()}
}
internal object GoogleConsentRequests {val store=GoogleConsentRequestStore()}
