package com.user.localagent.connections

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.user.localagent.core.connections.ConnectedService
import com.user.localagent.core.connections.GoogleAuthorizationPlan
import com.user.localagent.core.connections.ConnectionAccess
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class GoogleConsentNeeded(val pending:PendingIntent):IllegalStateException("구글 연결 화면에서 계정과 권한을 확인해주세요.")

class GoogleConnectionAuth(context:Context,private val store:ConnectionStore) {
    private val client=Identity.getAuthorizationClient(context.applicationContext)
    val registration=GoogleClientConfiguration(context.applicationContext)
    suspend fun authorize(service:ConnectedService,write:Boolean):AuthorizationResult=authorizeBound(service,write,null)
    suspend fun authorizeBound(service:ConnectedService,write:Boolean,preferredAccount:String?):AuthorizationResult=withContext(Dispatchers.Main) {
        require(service.google)
        registration.verifyInstalledIdentity()
        val builder=AuthorizationRequest.builder().setRequestedScopes(GoogleAuthorizationPlan.scopes(service,write).map(::Scope))
            .setOptOutIncludingGrantedScopes(true)
        (store.connection(service)?.accountName?:preferredAccount)?.let {builder.setAccount(Account(it,"com.google"))}
        client.authorize(builder.build()).await()
    }
    fun fromIntent(data:Intent)=client.getAuthorizationResultFromIntent(data)
    fun accept(service:ConnectedService,write:Boolean,result:AuthorizationResult):String {
        require(!result.hasResolution()&&!result.accessToken.isNullOrBlank())
        require(service.coveredBy(result.grantedScopes,write)){"필요한 서비스 권한이 모두 허용되지 않았습니다."}
        val account=GoogleAccountIdentity.read(result.accessToken!!)
        val name=account.email;val principal=account.principal
        val previous=store.connection(service)
        if(previous!=null)require(previous.principal==principal){"다른 계정입니다. 기존 연결을 해제한 뒤 선택해주세요."}
        store.connect(service,LocalConnection(principal,name,write,(previous?.generation?:0)+1))
        return result.accessToken!!
    }
    suspend fun token(service:ConnectedService,write:Boolean,expectedAccess:ConnectionAccess?=null):String {
        val current=store.connection(service)?:error("먼저 구글 계정을 연결해주세요.")
        require(expectedAccess==null||ConnectionAccess(current.principal,current.generation,true,current.writeAllowed)==expectedAccess){"승인한 계정 연결이 변경되었습니다."}
        require(!write||current.writeAllowed)
        val result=authorize(service,write)
        if(result.hasResolution())throw GoogleConsentNeeded(requireNotNull(result.pendingIntent))
        val token=requireNotNull(result.accessToken)
        val account=GoogleAccountIdentity.read(token)
        require(account.principal==current.principal&&service.coveredBy(result.grantedScopes,write)){"계정 또는 허용 범위가 변경됐습니다."}
        require(store.connection(service)?.let {it.principal==current.principal&&it.generation==current.generation&&it.writeAllowed==current.writeAllowed}==true){"계정 연결이 변경되었습니다."}
        return token
    }
}
