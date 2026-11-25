package com.user.localagent.compute

import android.content.Context
import com.user.localagent.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.net.URI
import java.util.Locale
import uniffi.ziphap_android.nativeBrowserNavigationTarget
import uniffi.ziphap_android.nativeEvaluateMediaRule
import uniffi.ziphap_android.nativeLessonProbeDelayMs
import uniffi.ziphap_android.NativeMediaFacts
import uniffi.ziphap_android.NativeMediaRuleResult

/** Owner switch per browser: on by default (each browser profile starts with no logins); turning it off persists. */
class BrowserAgentSettings private constructor(context:Context,name:String) {
    companion object {
        private val instances=mutableMapOf<String,BrowserAgentSettings>()
        fun pc(context:Context)=get(context,"pc_browser_v1")
        fun app(context:Context)=get(context,"app_browser_v1")
        @Synchronized private fun get(context:Context,name:String)=instances.getOrPut(name){BrowserAgentSettings(context.applicationContext,name)}
    }
    private val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE)
    fun enabled():Boolean=!prefs.getBoolean("owner_off",false)
    fun setEnabled(on:Boolean){prefs.edit().putBoolean("owner_off",!on).apply()}
}

/** Executes open/read/action for one browser; refusals come back as {"error": CODE}. */
typealias BrowserBackend = suspend (operation:String,body:JsonObject)->JsonObject

/**
 * One instance per request executor and browser: remembers the last page snapshot so owner approval names the
 * real control. Irreversible clicks and typing outside search boxes need the owner; the browser itself refuses
 * login/card/OTP fields. PC actions use a one-time approval token. In-app actions use the
 * snapshot ID attached to the owner's approval.
 */
class BrowserAgentTools(private val catalog:BrowserToolCatalog,private val where:String,private val enabled:()->Boolean,private val backend:BrowserBackend) {
    companion object {
        private const val APP_APPROVED_SNAPSHOT="approvedAppSnapshot"
        internal const val PHONE_AUTH_REQUIRED="PHONE_BROWSER_AUTH_REQUIRED"
        internal const val PC_AUTH_REQUIRED="PC_BROWSER_AUTH_REQUIRED"
        internal fun pcReadFallback(context:Context):BrowserBackend = {operation,body->
            check(operation=="open"&&BrowserAgentSettings.pc(context).enabled())
            pcRequest(context,"public-read",body)
        }
        private suspend fun pcRequest(context:Context,operation:String,body:JsonObject):JsonObject {
            val transport=PcTransport(context)
            val config=transport.available(waitForRestart=true)?:error("PC에 연결하지 못했습니다. 집 밖이면 휴대폰의 Tailscale을 켜주세요.")
            return transport.jsonWithStatus(config,"/v1/browser/$operation",body,85_000).second
        }
        fun pc(context:Context,work:suspend ()->com.user.localagent.integrations.AppWebAgent.WorkBinding?={null})=BrowserAgentTools(PcBrowserToolCatalog,"PC 백그라운드 브라우저",{BrowserAgentSettings.pc(context).enabled()}){operation,body->
            val transport=PcTransport(context)
            val config=transport.available(waitForRestart=true)?:throw PcUnavailable()
            val result=transport.jsonWithStatus(config,"/v1/browser/$operation",body,85_000).second
            val url=body["url"]?.jsonPrimitive?.contentOrNull
            val binding=work()
            val observedUrl=result["url"]?.jsonPrimitive?.contentOrNull ?: url
            if(binding!=null && observedUrl?.startsWith(PairedWebsiteLogin.ORIGIN+"/")==true &&
                result["loginRequired"]==JsonPrimitive(false) && result["blocked"]!=JsonPrimitive(true) &&
                result["snapshotId"] is JsonPrimitive) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                val path=URI(observedUrl).path
                val media=result["media"] as? JsonObject
                val stage=when {
                    media?.get("ended")==JsonPrimitive(true) -> "영상 종료 확인 · 사이트 진도 확인 필요"
                    media?.get("status")==JsonPrimitive("observed") -> "강의 플레이어 확인"
                    path=="/cme/cme_ing.php" -> "로그인 확인 · 수강 목록 확인"
                    path=="/cme/cme_detail_view.php" -> "강의 상세 확인"
                    else -> "사이트 페이지 확인"
                }
                com.user.localagent.integrations.AppWebAgent.get(context).recordPcWork(binding,stage,stage)
            }
            if(operation=="read" && binding!=null && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                com.user.localagent.integrations.AppWebAgent.get(context).expectsPcReadback(binding)
            } && (result["loginRequired"]!=JsonPrimitive(false) ||
                result["url"]?.jsonPrimitive?.contentOrNull?.startsWith(PairedWebsiteLogin.ORIGIN+"/")!=true))
                return@BrowserAgentTools buildJsonObject { put("error","OWNER_LOGIN_PAGE_CHANGED") }
            val offer=if(operation=="open" && result["loginRequired"]==JsonPrimitive(true) && binding!=null &&
                url!=null && url.startsWith(PairedWebsiteLogin.ORIGIN+"/")) try {
                PairedWebsiteLogin.prepare(context,config)
            } catch(cancel:CancellationException){throw cancel}
              catch(_:Exception){null}
            else null
            if(offer!=null && binding!=null && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate){
                    com.user.localagent.integrations.AppWebAgent.get(context).offerPcLogin(binding,offer)
                }) JsonObject(result+("nativeOwnerCard" to JsonPrimitive(true))) else result
        }
        fun app(context:Context,work:suspend ()->com.user.localagent.integrations.AppWebAgent.WorkBinding?={null},
                deferLogin:()->Boolean={false})=BrowserAgentTools(AppBrowserToolCatalog,"앱 브라우저",{BrowserAgentSettings.app(context).enabled()}){operation,body->
            val web=com.user.localagent.integrations.AppWebAgent.get(context)
            val binding=work()
            when(operation){
                "open"->web.open(body.getValue("url").jsonPrimitive.content,body["showOnPhone"]==JsonPrimitive(true),binding,deferLogin())
                "read"->web.read(body["observeMedia"]==JsonPrimitive(true),binding,deferLogin())
                else->web.act(body,body[APP_APPROVED_SNAPSHOT]?.jsonPrimitive?.contentOrNull,binding)
            }
        }
    }
    private data class Element(val label:String,val tag:String,val type:String,val needsApproval:Boolean)
    private var last:Triple<String,String,Map<String,Element>>?=null // snapshotId, url, elements
    private var lastPage:JsonObject?=null
    private var initialOrigin:String?=null
    private var recoveredTarget:String?=null
    private var ruleRecoveryBlocked=false
    private var ruleEffectUncertain=false
    private data class MediaWatch(val url:String,val player:String,val epoch:Int,val progress:BrowserMediaProgress)
    private var mediaWatch:MediaWatch?=null

    fun review(call:ToolCall):Pair<Boolean,String?> {
        if(call.name!=catalog.action)return false to null
        catalog.validate(call)
        if(catalog==AppBrowserToolCatalog&&BrowserNavigationRule.from(call)!=null)return false to null
        if(recoveredTarget!=null)return false to null
        val action=call.arguments.getValue("action").jsonPrimitive.content
        val snapshot=last?.takeIf{it.first==call.arguments.getValue("snapshotId").jsonPrimitive.content}
        val element=call.arguments["ref"]?.jsonPrimitive?.content?.let{snapshot?.third?.get(it)}
        val search=element!=null&&WebAgentPolicy.isSearchField(WebAgentPolicy.Element("",element.tag,type=element.type,label=element.label))
        val needs=when(action){
            "click"->element==null||element.needsApproval
            "type","press_enter"->!search
            else->false
        }
        val text=call.arguments["text"]?.jsonPrimitive?.content
        val review=buildString {
            append(where).append(" 동작\n페이지: ").append(snapshot?.second?:"(최근에 읽은 페이지 아님)")
            append("\n동작: ").append(action)
            element?.let{append("\n대상: ").append(it.label.ifBlank{it.tag})}
            text?.let{append("\n입력: ").append(it)}
            if(action=="click"&&element?.needsApproval==true)append("\n결제·주문·예약·전송처럼 되돌리기 어려운 동작일 수 있습니다.")
        }
        return needs to review
    }

    internal fun delegatedAssessmentSnapshot(call:ToolCall,scope:com.user.localagent.core.DelegationScope):String? {
        if(catalog!=AppBrowserToolCatalog||call.name!=catalog.action||call.arguments["action"]!=JsonPrimitive("click")||
            BrowserNavigationRule.from(call)!=null)return null
        val page=lastPage?:return null
        val id=page.string("snapshotId")
        if(!rulePage(page)||id!=call.arguments.string("snapshotId"))return null
        val field=ruleElements(page).singleOrNull { it.string("ref")==call.arguments.string("ref") }?:return null
        if(field["disabled"]!=JsonPrimitive(false)||field["sensitive"]!=JsonPrimitive(false))return null
        return id.takeIf { com.user.localagent.core.DelegatedSubmission.permitsAssessmentForm(
            scope,page.string("url"),field.string("assessmentForm"),field.string("label")) }
    }

    suspend fun execute(call:ToolCall,approvedSnapshot:String?=null,pcFallback:BrowserBackend?=null,
        verifyRuleAccess:suspend ()->Unit={}):ToolResult {
        catalog.validate(call)
        check(enabled()){"설정에서 '$where 에이전트'가 꺼져 있습니다."}
        if(call.name==catalog.read) {
            BrowserMediaRule.from(call)?.let { rule->
                if(recoveredTarget!=null)return refused(call,"RULE_SOURCE_CHANGED")
                return observeMedia(call,rule,verifyRuleAccess,pcFallback!=null)
            }
        }
        if(call.name==catalog.action&&ruleEffectUncertain)return refused(call,"RULE_EFFECT_PENDING")
        if(call.name==catalog.action&&recoveredTarget!=null)return refused(call,"SNAPSHOT_STALE")
        if(catalog==AppBrowserToolCatalog&&call.name==catalog.action) {
            BrowserNavigationRule.from(call)?.let { rule->
                if(approvedSnapshot!=null||ruleRecoveryBlocked)return refused(call,"RULE_EFFECT_PENDING")
                return runNavigationRule(call,rule,verifyRuleAccess)
            }
        }
        val pcAction=catalog==PcBrowserToolCatalog&&call.name==catalog.action
        val observed=last
        val ref=call.arguments["ref"]?.jsonPrimitive?.contentOrNull
        val attemptedControl=if(call.arguments["action"]==JsonPrimitive("click")&&
            call.arguments["snapshotId"]?.jsonPrimitive?.contentOrNull==observed?.first)
            ref?.let { observed?.third?.get(it)?.label } else null
        val approvedPcTarget=pcAction&&approvedSnapshot!=null&&observed!=null&&observed.first==approvedSnapshot&&
            call.arguments["snapshotId"]?.jsonPrimitive?.contentOrNull==approvedSnapshot&&ref!=null&&ref in observed.third
        if(pcAction&&(review(call).first||approvedSnapshot!=null)&&!approvedPcTarget)
            return refused(call,"APPROVAL_MISMATCH")
        val (operation,body)=when(call.name){
            catalog.open->"open" to buildJsonObject{
                put("url",call.arguments.getValue("url"))
                if(catalog==AppBrowserToolCatalog&&call.arguments["showOnPhone"]==JsonPrimitive(true))put("showOnPhone",true)
            }
            catalog.read->"read" to buildJsonObject{}
            else->{
                val arguments=if(catalog==AppBrowserToolCatalog){
                    val modelArguments=call.arguments-APP_APPROVED_SNAPSHOT
                    if(approvedSnapshot==null)modelArguments else modelArguments+(APP_APPROVED_SNAPSHOT to JsonPrimitive(approvedSnapshot))
                }else call.arguments-"approvalId"
                "act" to JsonObject(arguments)
            }
        }
        if(operation=="open"){
            last=null
            lastPage=null
            mediaWatch=null
            recoveredTarget=null
            initialOrigin=origin(body.getValue("url").jsonPrimitive.content)
        }
        var result=backend(operation,body)
        if(result["status"]?.jsonPrimitive?.contentOrNull=="approval_required"&&call.name==catalog.action){
            // A worker challenge is not owner consent. Echo only its token for a host-approved target.
            if(!approvedPcTarget)return refused(call,"APPROVAL_MISMATCH")
            val token=result["approvalId"] as? JsonPrimitive
            if(token==null||!token.isString||!Regex("[0-9a-f]{32}").matches(token.content))
                return refused(call,"APPROVAL_MISMATCH")
            result=backend(operation,JsonObject(body+("approvalId" to token)))
            if(result["status"]?.jsonPrimitive?.contentOrNull=="approval_required")return refused(call,"APPROVAL_MISMATCH")
        }
        val target=recoveredTarget
        if(result["loginRequired"]==JsonPrimitive(true)&&!(operation=="read"&&target!=null))
            return authenticationRequired(call,result["phoneBrowserVisible"]==JsonPrimitive(true),
                pcSession=catalog==PcBrowserToolCatalog && result["nativeOwnerCard"]!=JsonPrimitive(true))
        val phoneError=result["error"]?.jsonPrimitive?.contentOrNull
        if(operation=="read"&&target!=null){
            if(!readablePage(target,result))
                return recoverPublicPage(call,target,pcFallback)?:refused(call,"PC_RECOVERY_UNAVAILABLE")
            recoveredTarget=null
        }
        if(catalog==AppBrowserToolCatalog&&operation=="open"&&body["showOnPhone"]!=JsonPrimitive(true)&&
            phoneError in setOf("PAGE_TIMEOUT","PAGE_UNREADABLE","PAGE_UNREACHABLE")){
            val url=body.getValue("url").jsonPrimitive.content
            if(fallbackUrlAllowed(url))recoverPublicPage(call,url,pcFallback)?.let{return it}
        }
        val error=phoneError
        if(error!=null){
            if(catalog==AppBrowserToolCatalog&&error=="ACTION_OUTCOME_UNKNOWN"){
                last=null
                lastPage=null
                ruleRecoveryBlocked=true
                return ToolResult(call.id,false,browserFailure(error,attemptedControl=attemptedControl,
                    navigationError=result.string("navigationError")),
                    VerificationState.UNVERIFIED,"$where 동작 결과 미확인")
            }
            if(error in setOf("SNAPSHOT_STALE","ELEMENT_OBSCURED")){last=null;lastPage=null}
            return refused(call,error,(result["phoneBrowserVisible"] as? JsonPrimitive)?.booleanOrNull,attemptedControl)
        }
        remember(result)
        return ToolResult(call.id,true,compact(result),VerificationState.VERIFIED,
            when {
                result["pageChanged"]==JsonPrimitive(false)->"$where 클릭 후 화면 전환 미확인"
                result["performed"]==null->"$where 페이지를 실제로 읽음"
                else->"$where 동작 후 페이지를 다시 읽음"
            })
    }

    private suspend fun runNavigationRule(call:ToolCall,rule:BrowserNavigationRule,verifyAccess:suspend ()->Unit):ToolResult {
        val original=lastPage?:return refused(call,"RULE_SOURCE_CHANGED")
        val source=original.string("url")
        val originalRef=call.arguments.string("ref")
        if(original.string("snapshotId")!=call.arguments.string("snapshotId")||!rulePage(original)||
            original["truncated"]!=JsonPrimitive(false))return refused(call,"RULE_SOURCE_CHANGED")
        val first=ruleElements(original).singleOrNull {it.string("ref")==originalRef}
            ?:return refused(call,"RULE_LINK_UNPROVEN")
        val destination=first.string("ruleHref")
        if(!BrowserNavigationRule.sameSiteLink(source,destination)||!matchesRule(first,rule,destination)||
            rule.sees(original.string("title"),original.string("text")))return refused(call,"RULE_LINK_UNPROVEN")

        suspend fun checkAccess() {
            currentCoroutineContext().ensureActive()
            verifyAccess()
            check(enabled()){"브라우저 사용이 중지됐습니다."}
        }
        suspend fun read():JsonObject {
            checkAccess()
            return backend("read",buildJsonObject {})
        }

        repeat(2) {
            val fresh=withTimeoutOrNull(4_000){read()}?:return refused(call,"RULE_SOURCE_CHANGED")
            if(fresh["loginRequired"]==JsonPrimitive(true))return authenticationRequired(call)
            remember(fresh)
            val matches=ruleElements(fresh)
            val index=nativeBrowserNavigationTarget(
                sameSource=rulePage(fresh)&&fresh.string("url")==source,
                completeObservation=fresh["truncated"]==JsonPrimitive(false),
                markerAlreadyVisible=rule.sees(fresh.string("title"),fresh.string("text")),
                matchedControls=matches.map {matchesRule(it,rule,destination)},
            )?.toInt()?:return refused(call,"RULE_LINK_UNPROVEN")
            val ref=matches[index].string("ref")
            if(!Regex("e[0-9]{1,4}").matches(ref))return refused(call,"RULE_LINK_UNPROVEN")
            checkAccess()
            val acted=try {
                backend("act",buildJsonObject {
                    put("action","click");put("snapshotId",fresh.string("snapshotId"));put("ref",ref);put("ruleHref",destination)
                })
            } catch (cancel:CancellationException) { throw cancel }
            catch (_:Exception) { null }
            if(acted?.string("error") in setOf("SNAPSHOT_STALE","ELEMENT_UNAVAILABLE"))return@repeat
            if(acted?.get("loginRequired")==JsonPrimitive(true)){
                ruleEffectUncertain=true
                return authenticationRequired(call)
            }
            val lostReply=acted==null||acted.string("error")=="ACTION_OUTCOME_UNKNOWN"
            if(acted!=null && !lostReply && (acted["performed"] !is JsonObject || acted["error"]!=null))return uncertainRule(call)

            // A dispatched click may replace the renderer before its callback arrives.
            // Only a fresh observation of the bound destination AND its marker settles it.
            var page=acted?:JsonObject(emptyMap())
            val verified=try {
                withTimeoutOrNull(8_000) {
                    repeat(4) {attempt->
                        if(attempt>0)delay(650)
                        if(attempt>0||lostReply)page=read()
                        if(!rulePage(page)||page.string("url")!=destination) {
                            // The renderer may still be showing the exact source while it loads the known link.
                            if(!rulePage(page)||page.string("url")!=source)return@withTimeoutOrNull false
                        } else if(rule.sees(page.string("title"),page.string("text"))) {
                            checkAccess()
                            return@withTimeoutOrNull true
                        }
                    }
                    false
                }
            } catch (cancel:CancellationException) { throw cancel }
            catch (_:Exception) { false }
            if(page["loginRequired"]==JsonPrimitive(true)){
                ruleEffectUncertain=true
                return authenticationRequired(call)
            }
            if(verified!=true)return uncertainRule(call)
            remember(page)
            return ToolResult(call.id,true,compact(JsonObject(page+("navigationRuleStatus" to JsonPrimitive("verified")))),
                VerificationState.VERIFIED,"같은 링크 이동 · 도착 페이지와 새 확인 문구 확인")
        }
        return refused(call,"RULE_SOURCE_CHANGED")
    }

    private fun JsonObject.string(key:String)=(this[key] as? JsonPrimitive)?.takeIf {it.isString}?.content.orEmpty()
    private fun ruleElements(page:JsonObject)=(page["elements"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    private fun rulePage(page:JsonObject)=page["error"]==null&&page["blocked"]!=JsonPrimitive(true)&&
        page["externalHandoffBlocked"]==null&&page["loginRequired"]!=JsonPrimitive(true)&&page.string("snapshotId").isNotBlank()
    private fun matchesRule(item:JsonObject,rule:BrowserNavigationRule,destination:String)=
        item.string("tag")=="a"&&item.string("label")==rule.label&&item.string("ruleHref")==destination&&
            listOf("disabled","editable","sensitive","needsApproval").all {item[it]==JsonPrimitive(false)}
    private fun uncertainRule(call:ToolCall):ToolResult {
        ruleEffectUncertain=true
        last=null
        lastPage=null
        val receipt=buildJsonObject {
            put("navigationRuleStatus","unverified")
            put("hint","같은 동작을 다시 시도하기 전에 현재 페이지를 읽어 결과를 확인하세요.")
        }
        return ToolResult(call.id,false,receipt.toString(),
            VerificationState.UNVERIFIED,"페이지 이동 후 결과 미확인 · 같은 동작을 반복하지 않음")
    }

    private suspend fun observeMedia(call:ToolCall,rule:BrowserMediaRule,verifyAccess:suspend ()->Unit,
        mayOfferPc:Boolean):ToolResult {
        val undefined=NativeMediaFacts(null,null,null,null,null,null,null)
        if(nativeEvaluateMediaRule(rule.expression,undefined)==NativeMediaRuleResult.INVALID)
            return refused(call,"MEDIA_RULE_INVALID")
        fun nowMs()=System.nanoTime()/1_000_000
        val deadline=nowMs()+rule.waitSeconds*1_000L
        var bound:MediaWatch?=null
        var lastResult:ToolResult?=null
        while(true) {
            currentCoroutineContext().ensureActive()
            verifyAccess()
            check(enabled()){"브라우저 사용이 중지됐습니다."}
            val left=deadline-nowMs()
            if(left<=0)return lastResult?:mediaResult(call,null,"observation_timeout")
            val page=withTimeoutOrNull(minOf(4_000L,left)) {
                backend("read",buildJsonObject {put("observeMedia",true)})
            }?:return mediaResult(call,null,"observation_timeout")
            verifyAccess()
            if(!enabled())return refused(call,"RULE_SOURCE_CHANGED")
            if(page["loginRequired"]==JsonPrimitive(true))return authenticationRequired(call)
            if(!rulePage(page))return mediaResult(call,null,"page_unavailable")
            val media=page["media"] as? JsonObject
            val sample=media?.let(BrowserMediaSample::from)
            if(sample==null){
                val status=media?.string("status")?.takeIf {
                    it in setOf("multiple_players","player_unavailable","frame_unavailable","media_scan_limit")
                }
                mediaWatch=null
                remember(page)
                val handoff=if(status in setOf(null,"player_unavailable","frame_unavailable","media_scan_limit")&&
                    mayOfferPc&&catalog==AppBrowserToolCatalog)pcHandoff(page.string("url")) else emptyMap()
                return mediaResult(call,JsonObject(page+handoff),status?:"player_unavailable")
            }
            val url=page.string("url")
            fun MediaWatch.matches()=this.url==url&&player==sample.playerId&&epoch==sample.epoch
            if(bound!=null&&!bound.matches()) {
                mediaWatch=null
                return mediaResult(call,null,"player_changed")
            }
            val watch=bound?:mediaWatch?.takeIf {it.matches()}?:MediaWatch(url,sample.playerId,sample.epoch,BrowserMediaProgress())
            bound=watch
            mediaWatch=watch
            val reading=watch.progress.observe(sample,nowMs())
            val status=reading.status
            val facts=NativeMediaFacts(
                if(status==BrowserMediaStatus.CHECKING)null else status==BrowserMediaStatus.PLAYING,
                if(sample.ended&&status!=BrowserMediaStatus.ENDED)null else status==BrowserMediaStatus.ENDED,
                sample.paused,sample.readyState<3&&!sample.paused&&!sample.ended&&!sample.seeking&&reading.advancing!=true,
                sample.seeking,reading.advancing,sample.durationMs!=null,
            )
            val result=nativeEvaluateMediaRule(rule.expression,facts)
            val remainingBudget=deadline-nowMs()
            remember(page)
            lastResult=mediaResult(call,page,status.name.lowercase(Locale.ROOT),reading,result)
            if(result==NativeMediaRuleResult.MATCHED||status in setOf(BrowserMediaStatus.ENDED,
                    BrowserMediaStatus.PAUSED,BrowserMediaStatus.SEEKING)||remainingBudget<=0||
                sample.ended&&status==BrowserMediaStatus.CHECKING){
                return lastResult
            }
            delay(nativeLessonProbeDelayMs(reading.remainingSeconds?.toUInt(),remainingBudget.toUInt()).toLong())
        }
    }

    private fun mediaResult(call:ToolCall,page:JsonObject?,status:String,reading:BrowserMediaReading?=null,
        ruleResult:NativeMediaRuleResult=NativeMediaRuleResult.UNKNOWN):ToolResult {
        val evidence=buildJsonObject {
            put("playbackStatus",status)
            put("mediaRuleResult",ruleResult.name.lowercase(Locale.ROOT))
            reading?.let {
                put("positionMs",it.sample.positionMs)
                if(catalog==AppBrowserToolCatalog)put("playerSignature",it.sample.playerId+":"+it.sample.epoch)
                it.sample.durationMs?.let {duration->put("durationMs",duration)}
                it.remainingSeconds?.let {seconds->
                    put("remainingPlaybackSeconds",seconds)
                    put("estimatedEndEpochMillis",System.currentTimeMillis()+seconds*1000L)
                }
            }
        }
        val result=JsonObject(page.orEmpty()+("playbackEvidence" to evidence))
        return ToolResult(call.id,true,compact(result),VerificationState.VERIFIED,"브라우저 재생 상태 관찰 · $status")
    }

    private suspend fun recoverPublicPage(call:ToolCall,url:String,pcFallback:BrowserBackend?):ToolResult? {
        if(pcFallback==null)return null
        val recovered=try {
            withTimeoutOrNull(40_000){pcFallback("open",buildJsonObject{put("url",url)})}
        }catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){null}
        if(recovered?.get("loginRequired")==JsonPrimitive(true))return authenticationRequired(call,pcSession=true)
        if(recovered==null||!readablePage(url,recovered))return null
        recoveredTarget=url
        // Reobserve the bound URL. The PC result never supplies a handset action target.
        val handoff=pcHandoff(url)
        return ToolResult(call.id,true,compact(JsonObject(recovered-setOf("snapshotId","elements")+handoff),"PC 백그라운드 브라우저"),
            VerificationState.VERIFIED,"같은 공개 페이지를 PC 백그라운드 브라우저에서 읽음")
    }

    private fun pcHandoff(url:String):Map<String,JsonElement> {
        if(ruleEffectUncertain||ruleRecoveryBlocked||!fallbackUrlAllowed(url)||
            (initialOrigin!=null&&initialOrigin!=origin(url)))return emptyMap()
        return mapOf(
            "pcHandoffUrl" to JsonPrimitive(url),
            "handoffNote" to JsonPrimitive("공개 페이지는 open_pc_browser_page로 새로 열고 PC에서 다시 확인할 수 있습니다. 휴대폰의 로그인이나 동작 상태는 전달되지 않습니다.")
        )
    }

    private fun authenticationRequired(call:ToolCall,shown:Boolean=false,pcSession:Boolean=catalog==PcBrowserToolCatalog):ToolResult {
        last=null
        lastPage=null
        recoveredTarget=null
        val noChange=call.name!=catalog.action
        val code=if(pcSession)PC_AUTH_REQUIRED else PHONE_AUTH_REQUIRED
        return ToolResult(call.id,false,buildJsonObject {
            put("error",code)
            if(pcSession){
                initialOrigin?.let{put("pcBrowserOrigin",it)}
                put("hint","PC 브라우저가 별도 로그인을 요구해요. 사이트가 지원하는 휴대폰 승인 방법을 확인하세요. 휴대폰의 기존 로그인 상태는 PC로 전달되지 않아요.")
            }else{
                put("phoneBrowserVisible",shown)
                initialOrigin?.let{put("phoneBrowserOrigin",it)}
                put("hint","인증은 휴대폰의 집합 앱 브라우저에서 직접 진행해 주세요. 로그인이나 인증값을 채팅에 입력할 필요가 없습니다.")
            }
        }.toString(),if(noChange)VerificationState.FAILED else VerificationState.UNVERIFIED,
            code,effectNotApplied=noChange)
    }

    private fun origin(url:String):String? {
        if(!WebAgentPolicy.urlAllowed(url))return null
        val uri=URI(url)
        if(!uri.scheme.equals("https",true)||uri.port !in setOf(-1,443))return null
        return URI("https",null,uri.host,-1,null,null,null).toASCIIString()
    }

    private fun fallbackUrlAllowed(url:String):Boolean {
        if(origin(url)==null)return false
        val uri=URI(url)
        return uri.rawQuery==null&&uri.rawFragment==null&&uri.rawPath.split('/').none{
            it.length>64||it.lowercase() in setOf("login","signin","auth","oauth","authorize","token","session","verify","otp")
        }
    }

    private fun samePage(url:String,result:JsonObject):Boolean {
        if(result["error"]!=null||result["blocked"]==JsonPrimitive(true))return false
        val actual=result["url"]?.jsonPrimitive?.contentOrNull?:return false
        return fallbackUrlAllowed(actual)&&origin(actual)==origin(url)&&
            URI(actual).rawPath.trimEnd('/')==URI(url).rawPath.trimEnd('/')
    }

    private fun readablePage(url:String,result:JsonObject):Boolean =
        result["loginRequired"]!=JsonPrimitive(true)&&samePage(url,result)&&
            result["text"]?.jsonPrimitive?.contentOrNull?.isNotBlank()==true

    private fun refused(call:ToolCall,error:String,shown:Boolean?=null,attemptedControl:String?=null)=ToolResult(call.id,false,
        browserFailure(error,shown,attemptedControl),
        VerificationState.FAILED,"$where 거부 · $error",effectNotApplied=true)

    private fun browserFailure(error:String,shown:Boolean?=null,attemptedControl:String?=null,navigationError:String?=null)=buildJsonObject {
        put("error",error)
        put("hint",hint(error))
        shown?.let { put("phoneBrowserVisible",it) }
        attemptedControl?.trim()?.take(60)?.takeIf(String::isNotBlank)?.let { put("attemptedControl",it) }
        navigationError?.takeIf { it in setOf("PAGE_TIMEOUT","PAGE_UNREACHABLE","HTTP_ERROR","TLS_ERROR","HTTPS_REQUIRED","NAVIGATION_BLOCKED") }
            ?.let { put("navigationError",it) }
    }.toString()

    private fun hint(code:String)=when(code){
        "SNAPSHOT_STALE"->"페이지가 바뀌었습니다. ${catalog.read}로 다시 읽은 뒤 새 snapshotId와 ref를 사용하세요."
        "FIELD_NOT_ALLOWED","TEXT_NOT_ALLOWED"->"로그인·결제·인증은 휴대폰에서 소유자가 직접 진행해야 합니다."
        "URL_NOT_ALLOWED"->"공개 http(s) 웹페이지만 열 수 있습니다."
        "APPROVAL_MISMATCH"->"승인한 대상과 현재 페이지가 다릅니다. 다시 읽고 다시 요청하세요."
        "ELEMENT_OBSCURED"->"다른 화면 요소가 버튼을 가리고 있거나 버튼을 누를 수 없는 상태입니다. 페이지를 다시 읽고 팝업이나 가림 상태를 확인하세요."
        "ELEMENT_UNAVAILABLE","OPTION_UNKNOWN"->"그 요소를 사용할 수 없습니다. 다시 읽어 ref와 선택지를 확인하세요."
        "PAGE_TIMEOUT"->"페이지 응답이 늦습니다. 다시 읽어 보세요."
        "PAGE_UNREACHABLE","HTTP_ERROR"->"사이트가 응답하지 않습니다. 주소나 연결을 확인하세요."
        "TLS_ERROR"->"사이트의 보안 연결을 확인하지 못했습니다."
        "PC_RECOVERY_UNAVAILABLE"->"현재 요청에서 같은 페이지의 새 조회 결과를 확인하지 못했습니다."
        "ACTION_OUTCOME_UNKNOWN"->"동작이 실행됐는지 확인되지 않았습니다. 다시 실행하기 전에 페이지에서 결과를 확인하세요."
        "RULE_SOURCE_CHANGED","RULE_LINK_UNPROVEN"->"같은 링크와 새 확인 문구를 증명하지 못해 이동하지 않았습니다. 현재 페이지를 다시 읽어 주세요."
        "RULE_EFFECT_PENDING"->"앞선 동작의 결과를 먼저 읽어 확인해 주세요."
        "MEDIA_RULE_INVALID"->"허용된 재생 상태와 논리 연산자만 사용해 읽기 조건을 다시 작성하세요."
        else->"브라우저 요청이 거부됐습니다."
    }

    internal fun requirePdfRef(snapshotId:String,ref:String) {
        val page=lastPage
        check(enabled() && catalog==AppBrowserToolCatalog && page!=null && page.string("snapshotId")==snapshotId &&
            page["elements"]?.jsonArray?.any{it.jsonObject.string("ref")==ref && it.jsonObject["sessionPdf"]==JsonPrimitive(true)}==true) {
            "현재 요청에서 확인한 PDF 링크가 아니에요."
        }
    }

    private fun remember(result:JsonObject){
        val id=result["snapshotId"]?.jsonPrimitive?.contentOrNull?:run{last=null;lastPage=null;return}
        lastPage=result
        val elements=result["elements"]?.jsonArray.orEmpty().mapNotNull{item->runCatching{
            val e=item.jsonObject
            e.getValue("ref").jsonPrimitive.content to Element(e["label"]?.jsonPrimitive?.content.orEmpty(),e["tag"]?.jsonPrimitive?.content.orEmpty(),
                e["type"]?.jsonPrimitive?.content.orEmpty(),e["needsApproval"]?.jsonPrimitive?.booleanOrNull==true)
        }.getOrNull()}.toMap()
        last=Triple(id,result["url"]?.jsonPrimitive?.content.orEmpty(),elements)
    }

    /** Bounded model view: page text plus one line per element. */
    private fun compact(result:JsonObject,browser:String=where):String=buildJsonObject{
        listOf("playbackEvidence","snapshotId","url","title","loginRequired","phoneBrowserVisible","truncated","note","evidence","performed","pageChanged","externalHandoffBlocked","handoffNote","pcHandoffUrl","blocked","message","navigationRuleStatus","navigation")
            .forEach{key->result[key]?.let{put(key,it)}}
        if(catalog==AppBrowserToolCatalog && browser==where && rulePage(result) && result["elements"] is JsonArray){
            val eligible=ruleElements(result).take(120).count{it["sessionPdf"]==JsonPrimitive(true)}
            put("sameSessionPdfCount",eligible)
        }
        put("browser",browser)
        put("text",result["text"]?.jsonPrimitive?.content.orEmpty().take(6000))
        put("elements",JsonArray(result["elements"]?.jsonArray.orEmpty().take(120).mapNotNull{item->runCatching{
            val e=item.jsonObject
            val flags=listOfNotNull(e["type"]?.jsonPrimitive?.content?.takeIf{it.isNotBlank()},
                "same-session-pdf".takeIf{e["sessionPdf"]==JsonPrimitive(true)},
                "needs-owner-approval".takeIf{e["needsApproval"]?.jsonPrimitive?.booleanOrNull==true},
                "owner-only-field".takeIf{e["sensitive"]?.jsonPrimitive?.booleanOrNull==true},
                "disabled".takeIf{e["disabled"]?.jsonPrimitive?.booleanOrNull==true},
                "recoverable-link".takeIf{e.string("ruleHref").isNotBlank()},
                "below-viewport".takeIf{e.string("position")=="below_viewport"},
                e.string("nearby").take(144).takeIf{it.isNotBlank()}?.let{"row:$it"},
                e["options"]?.jsonArray?.joinToString("/"){o->o.jsonPrimitive.content}?.let{"options:"+it.take(300)})
            JsonPrimitive("${e.getValue("ref").jsonPrimitive.content}|${e["tag"]?.jsonPrimitive?.content}|${e["label"]?.jsonPrimitive?.content.orEmpty()}"+
                if(flags.isEmpty())"" else "|"+flags.joinToString(","))
        }.getOrNull()}))
        put("dataOnly",true)
    }.toString()
}
