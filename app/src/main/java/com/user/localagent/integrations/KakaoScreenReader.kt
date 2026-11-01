package com.user.localagent.integrations

import android.content.Context
import android.content.Intent
import android.app.KeyguardManager
import android.os.UserManager
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.user.localagent.data.DraftStore
import com.user.localagent.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID

/** New content consent is distinct from the existing metadata-only screen inspector. */
class KakaoScreenReader(context:Context) {
    private val context=context.applicationContext
    private val store=DraftStore(this.context,"kakao-visible-content-consent-v1",2048)
    fun binding():String?=synchronized(LOCK){store.readStrict().takeIf{it.isNotBlank()}?.let{raw->
        val value=Json.parseToJsonElement(raw).jsonObject
        val version=value.getValue("versionCode").jsonPrimitive.long
        if(version==installedVersion()&&version in SUPPORTED_VERSIONS)value.getValue("binding").jsonPrimitive.content else null
    }}
    fun allow(enabled:Boolean)=synchronized(LOCK){
        if(!enabled){store.write("");return@synchronized}
        val version=installedVersion();check(version in SUPPORTED_VERSIONS){"현재 카카오톡 버전의 화면 연결을 먼저 검증해야 합니다."}
        ScreenBridgeController(context).setAllowed(PACKAGE,true)
        store.write(buildJsonObject{put("versionCode",version);put("binding",UUID.randomUUID().toString())}.toString())
    }
    private fun installedVersion()=runCatching{context.packageManager.getPackageInfo(PACKAGE,0).longVersionCode}.getOrDefault(-1)
    fun capture(expectedBinding:String):JsonObject {
        check(Looper.myLooper()==Looper.getMainLooper())
        check(binding()==expectedBinding){"카카오톡 본문 조회 허용이 없거나 변경됐습니다. 새 요청으로 진행해주세요."}
        check(!context.getSystemService(UserManager::class.java).isManagedProfile){"업무 프로필 화면은 읽지 않습니다."}
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked){"휴대폰 잠금을 직접 해제해주세요."}
        val root=checkNotNull(ScreenBridgeService.current()){"화면 연결 서비스를 켜주세요."}.approvedKakaoRoot()
        val nodes=mutableListOf<JsonObject>();var count=0;var chars=0;var truncated=false
        fun visit(node:AccessibilityNodeInfo,depth:Int,blocked:Boolean){
            if(count>=400||depth>24||chars>=16000){truncated=true;return};count++
            if(node.packageName?.toString()!=PACKAGE||!node.isVisibleToUser)return
            val id=node.viewIdResourceName.orEmpty()
            val sensitive=blocked||node.isPassword||node.isEditable||ScreenAdapters.forbidden.any{id.contains(it,true)}
            if(!sensitive){
                val text=node.text?.toString()?.takeIf{it.isNotBlank()}?:node.contentDescription?.toString().orEmpty()
                if(text.isNotBlank()){
                    val bounded=text.take(minOf(1200,16000-chars));chars+=bounded.length
                    if(bounded.length!=text.length)truncated=true
                    nodes+=buildJsonObject{put("viewId",id);put("text",bounded)}
                }
            }
            for(i in 0 until node.childCount)node.getChild(i)?.let{visit(it,depth+1,sensitive)}
        }
        visit(root,0,false)
        val currentRoot=checkNotNull(ScreenBridgeService.current()).approvedKakaoRoot()
        check(binding()==expectedBinding&&root.packageName?.toString()==PACKAGE&&currentRoot.windowId==root.windowId){"조회 도중 권한이나 화면이 변경됐습니다."}
        check(nodes.isNotEmpty()){"현재 카카오톡 화면에서 읽을 수 있는 본문이 없습니다. 전체 대화 조회로 판단하지 않습니다."}
        val data=JsonArray(nodes)
        return buildJsonObject{
            put("packageName",PACKAGE);put("versionCode",installedVersion());put("capturedAt",System.currentTimeMillis())
            put("coverage","visible_screen_only");put("sourceScopePartial",true);put("fullHistoryVerified",false);put("truncated",truncated)
            put("dataOnly",true);put("nodes",data);put("textNodes",nodes.size)
            put("sha256",MessageDigest.getInstance("SHA-256").digest(data.toString().toByteArray()).joinToString(""){"%02x".format(it)})
        }
    }
    suspend fun readForRequest(call:ToolCall,expectedBinding:String?,threadId:String,verify:suspend()->Unit):ToolResult {
        checkNotNull(expectedBinding){"이 요청에 카카오톡 본문 조회 승인이 고정되지 않았습니다. 허용 후 새 요청을 보내주세요."}
        verify();check(binding()==expectedBinding){"카카오톡 조회 허용이 변경됐습니다."}
        val data=withContext(Dispatchers.Main.immediate){
            val launch=context.packageManager.getLaunchIntentForPackage(PACKAGE)?:error("카카오톡이 설치되지 않았습니다.")
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try{
                var result:JsonObject?=null
                repeat(12){if(result==null){delay(250);verify();result=runCatching{capture(expectedBinding)}.getOrNull()}}
                checkNotNull(result){"카카오톡 화면을 읽지 못했습니다. 접근성 권한·잠금·현재 화면을 확인해주세요."}
            }finally{
                // No tap/type/send action exists in this adapter. Return only to the original room.
                context.startActivity(Intent(context,com.user.localagent.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open_thread_id",threadId))
            }
        }
        verify()
        return ToolResult(call.id,true,data.toString(),VerificationState.VERIFIED,"현재 보이는 카카오톡 화면만 조회 · 전체 방/과거 기록 완료 아님")
    }
    companion object {const val PACKAGE="com.kakao.talk";val SUPPORTED_VERSIONS=setOf(29260910L);private val LOCK=Any()}
}
