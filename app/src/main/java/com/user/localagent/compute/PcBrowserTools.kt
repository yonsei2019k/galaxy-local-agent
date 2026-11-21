package com.user.localagent.compute

import android.content.Context
import com.user.localagent.core.*
import kotlinx.serialization.json.*

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
        fun pc(context:Context)=BrowserAgentTools(PcBrowserToolCatalog,"PC 백그라운드 브라우저",{BrowserAgentSettings.pc(context).enabled()}){operation,body->
            val transport=PcTransport(context)
            val config=transport.available(waitForRestart=true)?:error("PC에 연결하지 못했습니다. 집 밖이면 휴대폰의 Tailscale을 켜주세요.")
            transport.jsonWithStatus(config,"/v1/browser/$operation",body,85_000).second
        }
        fun app(context:Context)=BrowserAgentTools(AppBrowserToolCatalog,"앱 브라우저",{BrowserAgentSettings.app(context).enabled()}){operation,body->
            val web=com.user.localagent.integrations.AppWebAgent.get(context)
            when(operation){
                "open"->web.open(body.getValue("url").jsonPrimitive.content)
                "read"->web.read()
                else->web.act(body,body[APP_APPROVED_SNAPSHOT]?.jsonPrimitive?.contentOrNull)
            }
        }
    }
    private data class Element(val label:String,val tag:String,val type:String,val needsApproval:Boolean)
    private var last:Triple<String,String,Map<String,Element>>?=null // snapshotId, url, elements

    fun review(call:ToolCall):Pair<Boolean,String?> {
        if(call.name!=catalog.action)return false to null
        catalog.validate(call)
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

    suspend fun execute(call:ToolCall,approvedSnapshot:String?=null):ToolResult {
        catalog.validate(call)
        check(enabled()){"설정에서 '$where 에이전트'가 꺼져 있습니다."}
        val (operation,body)=when(call.name){
            catalog.open->"open" to buildJsonObject{put("url",call.arguments.getValue("url"))}
            catalog.read->"read" to buildJsonObject{}
            else->{
                val arguments=if(catalog==AppBrowserToolCatalog){
                    val modelArguments=call.arguments-APP_APPROVED_SNAPSHOT
                    if(approvedSnapshot==null)modelArguments else modelArguments+(APP_APPROVED_SNAPSHOT to JsonPrimitive(approvedSnapshot))
                }else call.arguments
                "act" to JsonObject(arguments)
            }
        }
        var result=backend(operation,body)
        // The owner already approved this exact control against the same snapshot; confirm it to the browser once.
        if(result["status"]?.jsonPrimitive?.contentOrNull=="approval_required"&&call.name==catalog.action){
            if(catalog==AppBrowserToolCatalog)result=buildJsonObject{put("error","APPROVAL_MISMATCH")}
            else{
                val approval=result.getValue("approvalId").jsonPrimitive.content
                require(Regex("[0-9a-f]{32}").matches(approval))
                result=backend(operation,JsonObject(call.arguments+("approvalId" to JsonPrimitive(approval))))
            }
        }
        val error=result["error"]?.jsonPrimitive?.contentOrNull
        if(error!=null){
            if(catalog==AppBrowserToolCatalog&&error=="ACTION_OUTCOME_UNKNOWN"){
                last=null
                return ToolResult(call.id,false,buildJsonObject{put("error",error);put("hint",hint(error))}.toString(),
                    VerificationState.UNVERIFIED,"$where 동작 결과 미확인")
            }
            if(error=="SNAPSHOT_STALE")last=null
            return ToolResult(call.id,false,buildJsonObject{put("error",error);put("hint",hint(error))}.toString(),VerificationState.FAILED,"$where 거부 · $error",effectNotApplied=true)
        }
        remember(result)
        return ToolResult(call.id,true,compact(result),VerificationState.VERIFIED,
            if(result["performed"]==null)"$where 페이지를 실제로 읽음" else "$where 동작 후 페이지를 다시 읽음 · 사이트의 최종 처리는 미보증")
    }

    private fun hint(code:String)=when(code){
        "SNAPSHOT_STALE"->"페이지가 바뀌었습니다. ${catalog.read}로 다시 읽은 뒤 새 snapshotId와 ref를 사용하세요."
        "FIELD_NOT_ALLOWED","TEXT_NOT_ALLOWED"->"로그인·결제·인증 칸은 소유자가 직접 입력해야 합니다. 소유자에게 요청하세요."
        "URL_NOT_ALLOWED"->"공개 http(s) 웹페이지만 열 수 있습니다."
        "APPROVAL_MISMATCH"->"승인한 대상과 현재 페이지가 다릅니다. 다시 읽고 다시 요청하세요."
        "ELEMENT_UNAVAILABLE","OPTION_UNKNOWN"->"그 요소를 사용할 수 없습니다. 다시 읽어 ref와 선택지를 확인하세요."
        "PAGE_TIMEOUT"->"페이지 응답이 늦습니다. 다시 읽어 보세요."
        "ACTION_OUTCOME_UNKNOWN"->"동작이 실행됐는지 확인되지 않았습니다. 다시 실행하기 전에 페이지에서 결과를 확인하세요."
        else->"브라우저 요청이 거부됐습니다."
    }

    private fun remember(result:JsonObject){
        val id=result["snapshotId"]?.jsonPrimitive?.contentOrNull?:run{last=null;return}
        val elements=result["elements"]?.jsonArray.orEmpty().mapNotNull{item->runCatching{
            val e=item.jsonObject
            e.getValue("ref").jsonPrimitive.content to Element(e["label"]?.jsonPrimitive?.content.orEmpty(),e["tag"]?.jsonPrimitive?.content.orEmpty(),
                e["type"]?.jsonPrimitive?.content.orEmpty(),e["needsApproval"]?.jsonPrimitive?.booleanOrNull==true)
        }.getOrNull()}.toMap()
        last=Triple(id,result["url"]?.jsonPrimitive?.content.orEmpty(),elements)
    }

    /** Bounded model view: page text plus one line per element. */
    private fun compact(result:JsonObject):String=buildJsonObject{
        listOf("snapshotId","url","title","loginRequired","truncated","note","evidence","performed","externalHandoffBlocked","handoffNote","blocked","message")
            .forEach{key->result[key]?.let{put(key,it)}}
        put("browser",where)
        put("text",result["text"]?.jsonPrimitive?.content.orEmpty().take(6000))
        put("elements",JsonArray(result["elements"]?.jsonArray.orEmpty().take(120).mapNotNull{item->runCatching{
            val e=item.jsonObject
            val flags=listOfNotNull(e["type"]?.jsonPrimitive?.content?.takeIf{it.isNotBlank()},
                "needs-owner-approval".takeIf{e["needsApproval"]?.jsonPrimitive?.booleanOrNull==true},
                "owner-only-field".takeIf{e["sensitive"]?.jsonPrimitive?.booleanOrNull==true},
                "disabled".takeIf{e["disabled"]?.jsonPrimitive?.booleanOrNull==true},
                e["options"]?.jsonArray?.joinToString("/"){o->o.jsonPrimitive.content}?.let{"options:"+it.take(300)})
            JsonPrimitive("${e.getValue("ref").jsonPrimitive.content}|${e["tag"]?.jsonPrimitive?.content}|${e["label"]?.jsonPrimitive?.content.orEmpty()}"+
                if(flags.isEmpty())"" else "|"+flags.joinToString(","))
        }.getOrNull()}))
        put("dataOnly",true)
    }.toString()
}
