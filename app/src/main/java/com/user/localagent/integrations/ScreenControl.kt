package com.user.localagent.integrations

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.UserManager
import android.text.InputType
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.user.localagent.core.ObservedToolOutcome
import com.user.localagent.core.ScreenControlToolCatalog
import com.user.localagent.core.ScreenReadEvidence
import com.user.localagent.core.ScreenObservationWait
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolReadPolicy
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import uniffi.ziphap_android.NativeScreenDispatchDecision
import uniffi.ziphap_android.NativeScreenRole
import uniffi.ziphap_android.NativeScreenTarget
import uniffi.ziphap_android.nativeScreenDispatchDecision
import uniffi.ziphap_android.nativeScreenSnapshotId

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
    fun ready():Boolean=setupNeeded()==null

    private fun setupNeeded():SetupNeeded? {
        val bridge=ScreenBridgeController(context).state()
        if(!bridge.systemEnabled){
            val listed=android.provider.Settings.Secure.getString(context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').any{it.startsWith("${context.packageName}/")}
            return if(listed)SetupNeeded.RECONNECT else SetupNeeded.SYSTEM
        }
        if(!enabled())return SetupNeeded.APP
        return if(bridge.connected)null else SetupNeeded.RECONNECT
    }

    internal fun requireReady(){setupNeeded()?.let{throw SetupRequired(it)}}

    /** Element of a read: ref is its index in a deterministic traversal of the same screen. */
    private data class Element(val ref:String,val role:NativeScreenRole,val label:String,val node:AccessibilityNodeInfo,val sensitive:Boolean)
    private data class Snapshot(val packageName:String,val texts:List<String>,val elements:List<Element>,
        val truncated:Boolean,val evidence:ScreenReadEvidence) {
        val id get()=evidence.snapshotId
    }

    suspend fun execute(call:ToolCall,approvedSnapshot:String?=null):ToolResult=try {
        requireReady()
        val read=if(call.name==ScreenControlToolCatalog.READ) observe(call) else null
        val result=when(call.name){
            ScreenControlToolCatalog.READ->read?.let {(screen,status)->render(screen,waitStatus=status)}?:ownAppView()
            ScreenControlToolCatalog.ACTION->act(call,approvedSnapshot)
            else->error("지원하지 않는 화면 도구입니다.")
        }
        val summary=if(call.name==ScreenControlToolCatalog.READ)"현재 화면 접근성 정보 조회"
            else "동작 후 화면을 다시 읽음 · 동작 유형 "+call.arguments.getValue("action").jsonPrimitive.content
        ToolResult(call.id,true,result,VerificationState.VERIFIED,summary,screenReadEvidence=read?.first?.evidence)
    }catch(error:Throwable){
        if(error is kotlinx.coroutines.CancellationException)throw error
        if(error is SnapshotChanged)error.failure(call.id)
        else if(error is SetupRequired)error.failure(call.id)
        else ToolResult(call.id,false,error.message?:"화면 제어 실패",VerificationState.FAILED,"화면 동작을 실행하지 않았거나 확인하지 못함",
            effectNotApplied=error is ScreenRefused)
    }

    private suspend fun observe(call:ToolCall):Pair<Snapshot,String?>? {
        val request=ScreenObservationWait.from(call.arguments)
        suspend fun read()=try { snapshotWhenAvailable() }
            catch(own:OwnAppForeground) { null }
        var current=read()?:run {
            if(request!=null)throw ScreenRefused("기다릴 강의 화면을 먼저 열어야 합니다.")
            return null
        }
        if(request==null)return current to null
        val originalWindow=current.evidence.windowId
        val deadline=SystemClock.elapsedRealtime()+request.seconds*1000L
        while(true){
            val status=request.status(current.packageName,current.evidence.windowId==originalWindow,
                current.texts+current.elements.filterNot {it.sensitive}.map {it.label},
                current.elements.filter {it.role==NativeScreenRole.TAP&&!it.sensitive}.map {it.label})
            if(status==ScreenObservationWait.Status.CONTEXT_CHANGED)
                throw ScreenRefused("대기 중 원래 강의 화면을 확인할 수 없었습니다.")
            val remaining=deadline-SystemClock.elapsedRealtime()
            if(status==ScreenObservationWait.Status.READY||remaining<=0)
                return current to status.name.lowercase(Locale.ROOT)
            delay(minOf(2000L,remaining))
            current=read()?:throw ScreenRefused("대기 중 집합 화면으로 돌아왔습니다. 원래 강의 화면을 다시 확인해야 합니다.")
        }
    }

    /** Owner review text and whether this action needs it, from a fresh read of the same screen. */
    suspend fun review(call:ToolCall):Pair<Boolean,String> {
        ScreenControlToolCatalog.validate(call)
        val action=call.arguments.getValue("action").jsonPrimitive.content
        if(action in setOf("back","home","open_app"))return false to describe(call,null,null)
        val requested=call.arguments.getValue("snapshotId").jsonPrimitive.content
        val screen=snapshotWhenAvailable().also { requireCurrentSnapshot(requested,it.id) }
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

    private suspend fun act(call:ToolCall,approvedSnapshot:String?):String {
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
                    val screen=try{snapshot(service)}catch(refused:ScreenRefused){
                        if(approvedSnapshot!=null)throw SnapshotChanged(afterApproval=true)
                        throw refused
                    }
                    requireCurrentSnapshot(requested,screen.id,approvedSnapshot)
                    if(action=="back")check(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)){"뒤로 가지 못했습니다."}
                    else perform(screen,action,a)
                }
            }
        }
        delay(900) // Let the app render the result before the observation read.
        val after=try {
            var observed=snapshotWhenAvailable()
            // A Chrome navigation can still be loading when the accessibility action returns.
            // Wait briefly for new evidence without replaying the action.
            repeat(2) {
                if(observed.id==requested&&action!="type"){
                    delay(350)
                    observed=snapshotWhenAvailable()
                }
            }
            observed
        } catch (failure:IllegalStateException) {
            if(failure is kotlinx.coroutines.CancellationException)throw failure
            // A failed observation after dispatch cannot establish that no effect occurred.
            throw IllegalStateException("동작을 요청했지만 동작 후 화면을 확인하지 못했습니다.",failure)
        }
        return render(after,action,requested!=after.id)
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
                if(target.sensitive||target.role!=NativeScreenRole.EDIT)throw ScreenRefused("이 요소에는 입력하지 않습니다.")
                val args=Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,a.getValue("text").jsonPrimitive.content)}
                check(target.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)){"글자를 입력하지 못했습니다."}
            }
            "scroll"->{
                val node=(target?:screen.elements.firstOrNull{it.role==NativeScreenRole.SCROLL})?.node?:error("스크롤할 영역이 없습니다.")
                val id=if(a["direction"]?.jsonPrimitive?.content=="backward")AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                check(node.performAction(id)){"더 스크롤할 수 없습니다."}
            }
        }
    }

    /** Accepts a package name or the app's launcher label ("계산기"); only launcher apps, never refused ones. */
    private fun openApp(requested:String) {
        val packageName=resolveApp(requested)?:error("설치되지 않았거나 열 수 없는 앱입니다: $requested")
        if(refusedPackage(packageName))throw ScreenRefused("이 앱은 화면 제어 대상이 아닙니다.")
        val intent=context.packageManager.getLaunchIntentForPackage(packageName)?:error("설치되지 않았거나 열 수 없는 앱입니다.")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private fun launcherApps():List<Pair<String,String>> {
        val pm=context.packageManager
        val query=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(query,0).map{it.activityInfo.packageName to it.loadLabel(pm).toString()}
            .distinctBy{it.first}.filterNot{refusedPackage(it.first)}
    }
    private fun resolveApp(requested:String):String? {
        if(context.packageManager.getLaunchIntentForPackage(requested)!=null)return requested
        val wanted=requested.trim().lowercase(Locale.ROOT)
        val apps=launcherApps()
        return (apps.firstOrNull{it.second.lowercase(Locale.ROOT)==wanted}?:apps.singleOrNull{wanted in it.second.lowercase(Locale.ROOT)})?.first
    }
    /** ZipHap itself is in front: report that (no content) so the agent can go home or open the target app first. */
    private fun ownAppView():String=buildJsonObject {
        put("untrustedScreenData",true);put("packageName",context.packageName);put("app","ZipHap.Agent");put("snapshotId","ziphap-foreground")
        put("controllable",false)
        put("note","지금 전경은 ZipHap.Agent 자신이라 화면 내용은 읽지 않습니다. 다른 앱을 쓰려면 screen_action action=open_app (packageName에 패키지 이름 또는 앱 이름, 예: 계산기) 또는 action=home 을 snapshotId=ziphap-foreground 로 실행한 뒤 read_screen 하세요.")
    }.toString()

    private fun service():ScreenBridgeService {
        requireReady()
        return ScreenBridgeService.current()?:throw SetupRequired(SetupNeeded.RECONNECT)
    }

    internal enum class SetupNeeded(val code:String,val instruction:String){
        SYSTEM("screen_setup_system","휴대폰 설정 → 접근성 → 설치된 앱에서 ‘ZipHap.Agent 화면 연결’을 켜 주세요. 집합 설정 → 개인정보와 권한 → 다른 앱 화면 접근에서 ‘에이전트 화면 제어’도 켜져 있는지 확인해 주세요."),
        APP("screen_setup_app","집합 설정 → 개인정보와 권한 → 다른 앱 화면 접근에서 ‘에이전트 화면 제어’를 켜 주세요."),
        RECONNECT("screen_setup_reconnect","휴대폰 설정 → 접근성 → 설치된 앱에서 ‘ZipHap.Agent 화면 연결’을 껐다가 다시 켜고 집합으로 돌아와 주세요.");

        companion object {
            fun forPause(outcomes:List<ObservedToolOutcome>):SetupNeeded? {
                val result=outcomes.lastOrNull{it.call.name in ScreenControlToolCatalog.names}?.result?:return null
                if(result.success||result.verification!=VerificationState.FAILED||!result.effectNotApplied)return null
                // A preceding effect takes precedence over setup instructions and replay.
                if(outcomes.any{!it.result.effectNotApplied&&!ToolReadPolicy.isReadOnly(it.call.name)})return null
                return entries.firstOrNull{result.verificationSummary==it.code}
            }
        }
    }

    internal class SetupRequired(val setup:SetupNeeded):IllegalStateException(setup.instruction){
        fun failure(id:String)=ToolResult(id,false,setup.instruction,VerificationState.FAILED,setup.code,effectNotApplied=true)
    }

    private suspend fun snapshotWhenAvailable():Snapshot =
        retryScreenObservation(read={onMain{requireReady();snapshot(service())}})

    private fun snapshot(service:ScreenBridgeService):Snapshot {
        if(!context.getSystemService(UserManager::class.java).isUserUnlocked||
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            throw ScreenRefused("잠금 화면은 다루지 않습니다.")
        val window=service.windows.filter{it.displayId==Display.DEFAULT_DISPLAY&&it.type==AccessibilityWindowInfo.TYPE_APPLICATION&&it.isFocused}
            .singleOrNull()?:throw ScreenObservationUnavailable("전경 앱 창을 찾지 못했습니다.")
        val root=window.root?:throw ScreenObservationUnavailable("전경 앱 화면을 읽지 못했습니다.")
        val packageName=root.packageName?.toString()?:throw ScreenObservationUnavailable("전경 앱을 확인하지 못했습니다.")
        if(packageName==context.packageName)throw OwnAppForeground()
        if(refusedPackage(packageName))throw ScreenRefused("시스템·권한·설치·잠금 화면은 다루지 않습니다.")
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
                node.isEditable&&AccessibilityNodeInfo.ACTION_SET_TEXT in actions->NativeScreenRole.EDIT
                node.isClickable&&AccessibilityNodeInfo.ACTION_CLICK in actions&&node.isEnabled->NativeScreenRole.TAP
                node.isScrollable->NativeScreenRole.SCROLL
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
        val digest=MessageDigest.getInstance("SHA-256")
        fun hash(value:String)=digest.digest(value.toByteArray(Charsets.UTF_8))
        val identity=MessageDigest.getInstance("SHA-256")
        fun ByteArray.hex():String=buildString(size*2) {
            val digits="0123456789abcdef"
            for (byte in this@hex) {
                append(digits[(byte.toInt() ushr 4) and 15])
                append(digits[byte.toInt() and 15])
            }
        }
        val targetKeys=mutableMapOf<String,String>()
        val targets=elements.map { element->
            val node=element.node
            val bounds=Rect().also(node::getBoundsInScreen)
            val uniqueId=if(Build.VERSION.SDK_INT>=33) node.uniqueId else null
            val resourceId=node.viewIdResourceName
            // Use fixed-width field digests so adjacent identity components cannot alias.
            for (part in listOf(node.packageName,node.className,resourceId,uniqueId)) {
                identity.update(hash(part?.toString().orEmpty()))
            }
            val target=NativeScreenTarget(element.role,hash(element.label),identity.digest(),bounds.left,bounds.top,
                bounds.right,bounds.bottom,element.sensitive,node.isEnabled)
            if(!target.sensitive&&target.enabled&&element.label.isNotBlank()&&
                (!resourceId.isNullOrBlank()||!uniqueId.isNullOrBlank())){
                identity.update(target.role.ordinal.toByte())
                identity.update(target.labelDigest)
                targetKeys[element.ref]=identity.digest(target.identityDigest).hex()
            }
            target
        }
        val packageDigest=hash(packageName)
        val id=checkNotNull(nativeScreenSnapshotId(snapshotNonce,packageDigest,window.id,truncated,
            texts.map(::hash),targets)){"화면 관측을 확인하지 못했습니다."}
        return Snapshot(packageName,texts,elements,truncated,ScreenReadEvidence(id,packageDigest.hex(),window.id,targetKeys))
    }

    private fun render(screen:Snapshot,action:String?=null,changed:Boolean=false,waitStatus:String?=null):String=buildJsonObject {
        waitStatus?.let {put("waitStatus",it)}
        if(action!=null){put("lastAction",action);put("screenChanged",changed)}
        put("untrustedScreenData",true);put("packageName",screen.packageName);put("app",appLabel(screen.packageName))
        put("snapshotId",screen.id);put("truncated",screen.truncated)
        put("texts",JsonArray(screen.texts.map(::JsonPrimitive)))
        put("elements",buildJsonArray{screen.elements.forEach{e->add(buildJsonObject{
            put("ref",e.ref);put("role",e.role.name.lowercase(Locale.ROOT));put("label",e.label);if(e.sensitive)put("blocked",true)
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

    open class ScreenRefused(message:String):IllegalStateException(message)
    class OwnAppForeground:ScreenRefused("ZipHap.Agent 자신의 화면은 조작하지 않습니다.")
    internal class ScreenObservationUnavailable(message:String):ScreenRefused(message)
    internal class SnapshotChanged(private val afterApproval:Boolean):ScreenRefused(
        if(afterApproval)ScreenControlToolCatalog.APPROVED_SNAPSHOT_CHANGED_NOTICE
        else "화면이 바뀌었습니다. read_screen으로 다시 읽은 뒤 새 snapshotId로 요청하세요."
    ) {
        fun failure(callId:String)=ToolResult(callId,false,checkNotNull(message),VerificationState.FAILED,
            if(afterApproval)ScreenControlToolCatalog.APPROVED_SNAPSHOT_CHANGED_RECEIPT else ScreenControlToolCatalog.STALE_SNAPSHOT_RECEIPT,
            effectNotApplied=true)
    }

    companion object {
        private val snapshotNonce=ByteArray(32).also(SecureRandom()::nextBytes)

        /** Only screen observations can be retried here. Dispatch stays outside this loop. */
        internal suspend fun <T> retryScreenObservation(
            wait:suspend ()->Unit={delay(350)},
            read:suspend ()->T,
        ):T {
            repeat(3){
                try{return read()}
                catch(_:ScreenObservationUnavailable){wait()}
            }
            return read()
        }

        internal fun requireCurrentSnapshot(requested:String,current:String,approvedSnapshot:String?=null) {
            when(nativeScreenDispatchDecision(requested,current,approvedSnapshot)){
                NativeScreenDispatchDecision.CURRENT->Unit
                NativeScreenDispatchDecision.STALE->throw SnapshotChanged(afterApproval=false)
                NativeScreenDispatchDecision.APPROVAL_INVALIDATED->throw SnapshotChanged(afterApproval=true)
            }
        }

        private val refusedPackages=listOf("com.user.localagent.images","com.android.settings","com.samsung.android.settings","com.android.systemui","com.android.permissioncontroller",
            "com.google.android.permissioncontroller","com.android.packageinstaller","com.google.android.packageinstaller","com.samsung.android.packageinstaller",
            "com.android.vending","com.samsung.android.spay","com.samsung.android.samsungpass","com.samsung.android.biometrics","com.android.keychain",
            "com.google.android.gms","com.samsung.android.authfw","com.sec.android.app.firewall")
        private val secretIdWords=listOf("password","passwd","pin_code","pincode","otp","cvc","cvv","card_number","cardnumber","security_code","auth_code","verification_code")
    }
}
