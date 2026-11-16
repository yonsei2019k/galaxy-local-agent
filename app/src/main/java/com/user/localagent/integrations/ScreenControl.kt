package com.user.localagent.integrations

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.UserManager
import android.text.InputType
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.user.localagent.core.ScreenControlToolCatalog
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.Locale

/**
 * Agent control of the foreground app through the owner-enabled accessibility service.
 * The owner turns it on twice: Android's accessibility consent and the in-app switch.
 * Every action re-reads the screen, refuses a changed snapshot, and returns the screen after the action.
 */
class ScreenControl(context:Context) {
    private val context=context.applicationContext
    private val prefs=this.context.getSharedPreferences("screen_control_v1",Context.MODE_PRIVATE)

    fun enabled():Boolean=prefs.getBoolean("enabled",false)
    fun setEnabled(value:Boolean){prefs.edit().putBoolean("enabled",value).apply()}
    fun ready():Boolean=enabled()&&ScreenBridgeController(context).state().let{it.systemEnabled&&it.connected}

    /** Element of a read: ref is its index in a deterministic traversal of the same screen. */
    private data class Element(val ref:String,val role:String,val label:String,val node:AccessibilityNodeInfo,val sensitive:Boolean)
    private data class Snapshot(val packageName:String,val id:String,val texts:List<String>,val elements:List<Element>,val truncated:Boolean)

    suspend fun execute(call:ToolCall):ToolResult=try {
        check(enabled()){"화면 제어가 꺼져 있습니다. 설정 > 화면 연결에서 '에이전트 화면 제어'를 켜주세요."}
        val result=when(call.name){
            ScreenControlToolCatalog.READ->render(onMain{snapshot(service())})
            ScreenControlToolCatalog.ACTION->act(call)
            else->error("지원하지 않는 화면 도구입니다.")
        }
        ToolResult(call.id,true,result,VerificationState.VERIFIED,
            if(call.name==ScreenControlToolCatalog.READ)"현재 화면 접근성 정보 조회" else "동작 후 화면을 다시 읽음 · 앱 안의 최종 결과는 화면 내용으로만 판단")
    }catch(error:Throwable){
        if(error is kotlinx.coroutines.CancellationException)throw error
        ToolResult(call.id,false,error.message?:"화면 제어 실패",VerificationState.FAILED,"화면 동작을 실행하지 않았거나 확인하지 못함",
            effectNotApplied=error is ScreenRefused)
    }

    /** Owner review text and whether this action needs it, from a fresh read of the same screen. */
    suspend fun review(call:ToolCall):Pair<Boolean,String> {
        ScreenControlToolCatalog.validate(call)
        val action=call.arguments.getValue("action").jsonPrimitive.content
        if(action in setOf("back","home","open_app"))return false to describe(call,null,null)
        val screen=onMain{snapshot(service())}
        val target=call.arguments["ref"]?.jsonPrimitive?.content?.let{ref->screen.elements.firstOrNull{it.ref==ref}}
        val app=appLabel(screen.packageName)
        val needs=action=="type"||(action=="tap"&&target!=null&&ScreenControlToolCatalog.isCommitLabel(target.label))
        return needs to describe(call,app,target?.label)
    }

    private fun describe(call:ToolCall,app:String?,label:String?):String {
        val a=call.arguments
        val action=a.getValue("action").jsonPrimitive.content
        val where=app?.let{"앱: $it\n"}.orEmpty()
        return where+when(action){
            "tap"->"누를 요소: ${label?.take(120)?:"(화면에서 찾지 못함)"}"
            "type"->"입력할 칸: ${label?.take(120)?:"(화면에서 찾지 못함)"}\n입력할 글자:\n${a["text"]?.jsonPrimitive?.content}"
            "scroll"->"스크롤: ${a["direction"]?.jsonPrimitive?.content?:"forward"}"
            "back"->"뒤로 가기";"home"->"홈 화면으로"
            else->"앱 열기: ${a["packageName"]?.jsonPrimitive?.content?.let(::appLabel)}"
        }
    }

    private suspend fun act(call:ToolCall):String {
        ScreenControlToolCatalog.validate(call)
        val a=call.arguments
        val action=a.getValue("action").jsonPrimitive.content
        val requested=a.getValue("snapshotId").jsonPrimitive.content
        onMain {
            val service=service()
            when(action){
                "home"->check(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)){"홈으로 이동하지 못했습니다."}
                "open_app"->openApp(a.getValue("packageName").jsonPrimitive.content)
                else->{
                    val screen=snapshot(service)
                    if(screen.id!=requested)throw ScreenRefused("화면이 바뀌었습니다. read_screen으로 다시 읽은 뒤 새 snapshotId로 요청하세요.")
                    if(action=="back")check(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)){"뒤로 가지 못했습니다."}
                    else perform(screen,action,a)
                }
            }
        }
        delay(900) // Let the app render the result before the observation read.
        return "동작: $action 실행\n\n동작 후 화면:\n"+render(onMain{snapshot(service())})
    }

    private fun perform(screen:Snapshot,action:String,a:JsonObject) {
        val ref=a["ref"]?.jsonPrimitive?.content
        val target=ref?.let{r->screen.elements.firstOrNull{it.ref==r}}
        when(action){
            "tap"->{
                checkNotNull(target){"요소 $ref 를 찾지 못했습니다."}
                if(target.sensitive)throw ScreenRefused("보호된 요소는 누르지 않습니다.")
                check(target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)){"요소를 누르지 못했습니다."}
            }
            "type"->{
                checkNotNull(target){"입력칸 $ref 를 찾지 못했습니다."}
                if(target.sensitive||target.role!="edit")throw ScreenRefused("이 요소에는 입력하지 않습니다.")
                val args=Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,a.getValue("text").jsonPrimitive.content)}
                check(target.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)){"글자를 입력하지 못했습니다."}
            }
            "scroll"->{
                val node=(target?:screen.elements.firstOrNull{it.role=="scroll"})?.node?:error("스크롤할 영역이 없습니다.")
                val id=if(a["direction"]?.jsonPrimitive?.content=="backward")AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                check(node.performAction(id)){"더 스크롤할 수 없습니다."}
            }
        }
    }

    private fun openApp(packageName:String) {
        if(refusedPackage(packageName))throw ScreenRefused("이 앱은 화면 제어 대상이 아닙니다.")
        val intent=context.packageManager.getLaunchIntentForPackage(packageName)?:error("설치되지 않았거나 열 수 없는 앱입니다.")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun service():ScreenBridgeService {
        check(ScreenBridgeController(context).state().systemEnabled){"Android 접근성에서 'ZipHap.Agent 화면 연결'을 켜주세요."}
        return ScreenBridgeService.current()?:error("화면 연결 서비스가 아직 연결되지 않았습니다.")
    }

    private fun snapshot(service:ScreenBridgeService):Snapshot {
        check(context.getSystemService(UserManager::class.java).isUserUnlocked){"휴대폰 잠금을 먼저 풀어주세요."}
        if(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)throw ScreenRefused("잠금 화면은 다루지 않습니다.")
        val window=service.windows.filter{it.displayId==Display.DEFAULT_DISPLAY&&it.type==AccessibilityWindowInfo.TYPE_APPLICATION&&it.isFocused}
            .singleOrNull()?:error("전경 앱 창을 찾지 못했습니다.")
        val root=window.root?:error("전경 앱 화면을 읽지 못했습니다.")
        val packageName=root.packageName?.toString()?:error("전경 앱을 확인하지 못했습니다.")
        if(refusedPackage(packageName))throw ScreenRefused(if(packageName==context.packageName)"ZipHap.Agent 자신의 화면은 조작하지 않습니다." else "시스템·권한·설치·잠금 화면은 다루지 않습니다.")
        val texts=mutableListOf<String>();val elements=mutableListOf<Element>()
        var visited=0;var truncated=false;var textBudget=6000
        fun visit(node:AccessibilityNodeInfo,depth:Int,sensitiveAncestor:Boolean) {
            if(visited>=600||depth>30){truncated=true;return}
            visited++
            if(!node.isVisibleToUser)return
            val sensitive=sensitiveAncestor||sensitive(node)
            val label=labelOf(node)
            val actions=node.actionList.map{it.id}.toSet()
            val role=when {
                node.isEditable&&AccessibilityNodeInfo.ACTION_SET_TEXT in actions->"edit"
                node.isClickable&&AccessibilityNodeInfo.ACTION_CLICK in actions&&node.isEnabled->"tap"
                node.isScrollable->"scroll"
                else->null
            }
            if(role!=null&&elements.size<150){
                val shown=if(node.isPassword)"[보호된 입력칸]" else (label.ifBlank{descendantLabel(node)}).take(120)
                elements+=Element("e${elements.size+1}",role,shown,node,sensitive||node.isPassword)
            } else if(label.isNotBlank()&&!node.isPassword&&textBudget>0){
                val piece=label.take(200);texts+=piece;textBudget-=piece.length
            }
            for(index in 0 until node.childCount.coerceAtMost(80))node.getChild(index)?.let{visit(it,depth+1,sensitive)}
            if(node.childCount>80)truncated=true
        }
        visit(root,0,false)
        if(elements.size>=150||textBudget<=0)truncated=true
        val signature=buildString {
            append(packageName)
            elements.forEach{e->val r=Rect();e.node.getBoundsInScreen(r);append('|').append(e.role).append(e.label).append(r.flattenToString())}
        }
        val id=MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString(""){"%02x".format(it)}.take(16)
        return Snapshot(packageName,id,texts,elements,truncated)
    }

    private fun render(screen:Snapshot):String=buildJsonObject {
        put("untrustedScreenData",true);put("packageName",screen.packageName);put("app",appLabel(screen.packageName))
        put("snapshotId",screen.id);put("truncated",screen.truncated)
        put("texts",JsonArray(screen.texts.map(::JsonPrimitive)))
        put("elements",buildJsonArray{screen.elements.forEach{e->add(buildJsonObject{
            put("ref",e.ref);put("role",e.role);put("label",e.label);if(e.sensitive)put("blocked",true)
        })}})
    }.toString()

    private fun labelOf(node:AccessibilityNodeInfo):String=
        listOfNotNull(node.text?.toString(),node.contentDescription?.toString(),node.hintText?.toString())
            .firstOrNull{it.isNotBlank()}?.replace(Regex("\\s+")," ")?.trim().orEmpty()
    private fun descendantLabel(node:AccessibilityNodeInfo,depth:Int=0):String {
        if(depth>4)return ""
        for(i in 0 until node.childCount.coerceAtMost(12)){
            val child=node.getChild(i)?:continue
            val own=if(child.isPassword)"" else labelOf(child)
            if(own.isNotBlank())return own
            descendantLabel(child,depth+1).takeIf{it.isNotBlank()}?.let{return it}
        }
        return node.viewIdResourceName?.substringAfter(":id/").orEmpty()
    }
    private fun sensitive(node:AccessibilityNodeInfo):Boolean {
        if(node.isPassword)return true
        val inputClass=node.inputType and InputType.TYPE_MASK_CLASS
        val variation=node.inputType and InputType.TYPE_MASK_VARIATION
        if((inputClass==InputType.TYPE_CLASS_TEXT&&variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))||
            (inputClass==InputType.TYPE_CLASS_NUMBER&&variation==InputType.TYPE_NUMBER_VARIATION_PASSWORD))return true
        val id=node.viewIdResourceName.orEmpty().lowercase(Locale.ROOT)
        return secretIdWords.any{it in id}
    }
    private fun appLabel(packageName:String):String=runCatching {
        val pm=context.packageManager;pm.getApplicationLabel(pm.getApplicationInfo(packageName,0)).toString()
    }.getOrDefault(packageName)
    private fun refusedPackage(packageName:String):Boolean=packageName==context.packageName||refusedPackages.any{packageName==it||packageName.startsWith("$it.")}

    private suspend fun <T> onMain(block:()->T):T=withContext(Dispatchers.Main){block()}

    class ScreenRefused(message:String):IllegalStateException(message)

    companion object {
        private val refusedPackages=listOf("com.android.settings","com.samsung.android.settings","com.android.systemui","com.android.permissioncontroller",
            "com.google.android.permissioncontroller","com.android.packageinstaller","com.google.android.packageinstaller","com.samsung.android.packageinstaller",
            "com.android.vending","com.samsung.android.spay","com.samsung.android.samsungpass","com.samsung.android.biometrics","com.android.keychain",
            "com.google.android.gms","com.samsung.android.authfw","com.sec.android.app.firewall")
        private val secretIdWords=listOf("password","passwd","pin_code","pincode","otp","cvc","cvv","card_number","cardnumber","security_code","auth_code","verification_code")
    }
}
