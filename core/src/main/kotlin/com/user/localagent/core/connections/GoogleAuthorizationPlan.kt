package com.user.localagent.core.connections

object GoogleAuthorizationPlan {
    val services=ConnectedService.entries.filter {it.google&&it!=ConnectedService.YOUTUBE}+ConnectedService.YOUTUBE
    fun writes(service:ConnectedService)=ConnectionCatalog.tools.values.any {it.service==service&&it.writes}
    fun scopes(service:ConnectedService,write:Boolean):List<String> {
        require(service.google)
        return (service.scopes(write)+listOf("openid","email")).distinct()
    }
}
