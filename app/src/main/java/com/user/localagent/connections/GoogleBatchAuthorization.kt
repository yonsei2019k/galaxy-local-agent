package com.user.localagent.connections

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.user.localagent.core.connections.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Service-isolated consent avoids mixing Drive/YouTube and previously granted project scopes. */
class GoogleBatchAuthorization(private val context:Context,private val hub:ConnectionHub) {
    private val services=GoogleAuthorizationPlan.services
    fun begin(){
        hub.store.write("google.batch.index","0");hub.store.write("google.batch.current",null)
        hub.store.write("google.batch.status","starting")
    }
    suspend fun authorize():AuthorizationResult? {
        try {
            while(true){
                val index=hub.store.read("google.batch.index")?.toIntOrNull()?:0
                require(index in 0..services.size)
                if(index==services.size){hub.store.write("google.batch.current",null);hub.store.write("google.batch.status","accounts_connected_unverified");return null}
                val service=services[index]
                hub.store.write("google.batch.current",service.id)
                val account=services.firstNotNullOfOrNull {hub.store.connection(it)?.accountName}
                val result=hub.google.authorizeBound(service,GoogleAuthorizationPlan.writes(service),account)
                if(result.hasResolution()){hub.store.write("google.batch.status","user_consent_required");return result}
                withContext(Dispatchers.IO){accept(result)}
            }
        }catch(error:com.google.android.gms.common.api.ApiException){hub.store.write("google.batch.status","google_sdk_error:${error.statusCode}");throw error}
        catch(error:Exception){hub.store.write("google.batch.status","authorization_error");throw error}
    }
    fun accept(result:AuthorizationResult):String {
        val index=hub.store.read("google.batch.index")?.toIntOrNull()?:error("구글 연결 진행 상태를 다시 시작해주세요.")
        require(index in services.indices)
        val service=services[index];require(hub.store.read("google.batch.current")==service.id)
        hub.google.accept(service,GoogleAuthorizationPlan.writes(service),result)
        hub.store.write("google.batch.index",(index+1).toString())
        hub.clearCache()
        return summary()
    }
    fun summary():String {
        val count=services.count {hub.store.connection(it)!=null}
        return "Google ${count}/${services.size}개 서비스의 계정 권한 연결됨 · 현재 ${hub.store.read("google.batch.current")?:"확인 완료"}. 실제 자료 조회는 별도로 확인합니다."
    }
}
