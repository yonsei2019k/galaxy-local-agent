package com.user.localagent.tools

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.user.localagent.LocalAgentApplication
import com.user.localagent.automation.AssistantNotificationListener
import com.user.localagent.compute.PcComputeSettings
import com.user.localagent.connections.ConsumerApps
import com.user.localagent.core.*
import com.user.localagent.core.connections.ConnectionCatalog
import com.user.localagent.core.connections.PcToolCatalog
import com.user.localagent.integrations.ScreenBridgeController
import com.user.localagent.workspace.ProjectSelection
import com.user.localagent.workspace.ProjectWorkspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Metadata only: no provider records, transport health, model preparation, consent UI or writes. */
class AgentCapabilities(context:Context) {
    private val context=context.applicationContext

    suspend fun report(allowedTools:Set<String>,workspace:ProjectWorkspace):String {
        val allowedSnapshot=allowedTools.toSet()
        return withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val checks=linkedMapOf<String,CapabilityCheck>()
        fun check(key:String,read:()->Boolean):Boolean {
            val result=observe(read)
            checks[key]=result
            return result==CapabilityCheck.GRANTED
        }
        val grants=linkedSetOf<String>()
        listOf(Manifest.permission.READ_SMS,Manifest.permission.READ_CALL_LOG,Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR,Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS).forEach {permission->
            if(check(permission){ContextCompat.checkSelfPermission(context,permission)==PackageManager.PERMISSION_GRANTED})grants+=permission
        }
        if(check("notification_access"){AssistantNotificationListener.granted(context)})grants+="notification_access"
        if(check("usage_access"){
            context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,Process.myUid(),context.packageName)==AppOpsManager.MODE_ALLOWED
        })grants+="usage_access"
        if(check("screen_access"){ScreenBridgeController(context).state().systemEnabled})grants+="screen_access"
        // System access alone never proves per-app content consent or an adapter's actual behavior.
        checks["kakao_screen_access"]=CapabilityCheck.UNCONFIRMED

        val handlers=linkedSetOf<String>()
        val candidates=mapOf(
            "calendar" to Intent(Intent.ACTION_INSERT,CalendarContract.Events.CONTENT_URI),
            "alarm" to Intent(AlarmClock.ACTION_SET_ALARM),
            "sms" to Intent(Intent.ACTION_SENDTO,Uri.parse("smsto:")),
            "email" to Intent(Intent.ACTION_SENDTO,Uri.parse("mailto:")),
            "maps" to Intent(Intent.ACTION_VIEW,Uri.parse("geo:0,0")),
            "browser" to Intent(Intent.ACTION_VIEW,Uri.parse("https://example.com")))
        candidates.forEach {(key,intent)->if(check("handler:$key"){intent.resolveActivity(context.packageManager)!=null})handlers+=key}
        // Querying installed packages does not open any app or establish a version-bound workflow.
        val consumerHandlers=readOrNull {ConsumerApps(context).installed().map {"consumer:${it.id}"}.toSet()}
        handlers+=consumerHandlers.orEmpty()
        val pc=readOrNull {PcComputeSettings.get(context).state.value}
        // This is an existing process's verification history, never a new hash/readiness claim.
        val cachedVerified=readOrNull {
            (context as? LocalAgentApplication)?.container?.models?.models?.value?.count {it.verified}
        }
        val environment=CapabilityEnvironment(grantedPermissions=grants,installedHandlers=handlers,apiLevel=Build.VERSION.SDK_INT)
        currentCoroutineContext().ensureActive()
        AgentCapabilityReport.render(allowedSnapshot,environment,checks,readOrNull {workspace.state.value},
            pc?.paired,pc?.automaticText,pc?.preferPcImages,cachedVerified)
        }
    }

    private fun observe(read:()->Boolean):CapabilityCheck=try {
        if(read())CapabilityCheck.GRANTED else CapabilityCheck.NOT_GRANTED
    }catch(cancel:CancellationException){throw cancel}catch(_:Exception){CapabilityCheck.UNCONFIRMED}
    private fun <T> readOrNull(read:()->T):T?=try {read()}catch(cancel:CancellationException){throw cancel}catch(_:Exception){null}
}

internal enum class CapabilityCheck { GRANTED, NOT_GRANTED, UNCONFIRMED }

/** Pure bounded projection, shared by unit tests. No caller-supplied names/IDs are echoed. */
internal object AgentCapabilityReport {
    const val MAX_TOOLS=120
    const val MAX_CHARS=24_000

    fun render(allowedTools:Set<String>,environment:CapabilityEnvironment,checks:Map<String,CapabilityCheck>,
        workspace:ProjectSelection?,paired:Boolean?,automaticText:Boolean?,preferPcImages:Boolean?,cachedVerified:Int?):String {
        val registered=ToolRegistry.definitions.keys
        val allowed=registered.intersect(allowedTools)
        // No live verification was performed by this observer. Never inherit caller-supplied remote claims.
        val observedEnvironment=environment.copy(installedModels=emptySet(),validatedConnectors=emptySet(),validatedCapabilities=emptySet())
        val capabilities=ToolCapabilityCatalog.snapshot(observedEnvironment).associateBy {it.id}
        val tools=allowed.sorted().take(MAX_TOOLS).map {name->
            val capability=capabilities.getValue(name)
            val external=name in ConnectionCatalog.tools||name in PcToolCatalog.names
            val requirements=capability.requirements.toMutableList()
            if(external)requirements+="현재 요청의 계정·연결 허용과 실제 연결 확인 필요"
            val uncertainPermission=requirements.any {checks[it]==CapabilityCheck.UNCONFIRMED}
            val availability=when {
                external||uncertainPermission->CapabilityAvailability.UNVALIDATED
                capability.availability==CapabilityAvailability.REQUIRES_MODEL->CapabilityAvailability.UNVALIDATED
                else->capability.availability
            }
            buildJsonObject {
                put("name",name);put("availability",availability.name);put("allowedInCurrentRequest",true)
                put("requirements",JsonArray(requirements.take(6).map {JsonPrimitive(it.take(120))}))
                put("requiresConfirmation",capability.requiresConfirmation)
                put("limitation",capability.limitation.take(240));put("executionVerified",false)
            }
        }.toMutableList()
        fun output():String=buildJsonObject {
            put("status","ok");put("registeredToolCount",registered.size);put("allowedToolCount",allowed.size)
            put("tools",JsonArray(tools));put("reportedToolCount",tools.size);put("omittedToolCount",allowed.size-tools.size)
            put("partial",tools.size<allowed.size);put("untrustedData",true);put("checksDoNotGrantPermission",true)
            put("executionVerified",false);put("availabilityScope","tool_prerequisites_not_inference_or_completion")
            put("permissionChecks",buildJsonObject {
                // Allow only fixed public permission/handler keys; never exception text or arbitrary input.
                checks.filterKeys {it in PUBLIC_CHECK_KEYS}.toSortedMap().forEach {(key,value)->put(key,value.name)}
            })
            put("workspace",buildJsonObject {
                put("status",if(workspace==null)"UNCONFIRMED"else"SELECTION_SNAPSHOT")
                put("name",workspace?.name?.let(::safeName)?:"미확인")
                put("external",workspace?.external?.let(::JsonPrimitive)?:JsonNull)
                put("writable",workspace?.writable?.let(::JsonPrimitive)?:JsonNull)
                put("accessRevalidated",false)
            })
            put("models",buildJsonObject {
                put("verification","UNCONFIRMED");put("cachedVerifiedRecordCount",cachedVerified?.coerceIn(0,100)?.let(::JsonPrimitive)?:JsonNull)
                put("freshHashVerificationPerformed",false);put("runtimeReadyVerified",false)
                put("limitation","기존 검증 기록은 현재 파일 무결성·선택 모델·실행 준비의 새 검증이 아닙니다.")
            })
            put("pairedPc",buildJsonObject {
                put("configurationRegistered",paired?.let(::JsonPrimitive)?:JsonNull)
                put("automaticTextConfigured",automaticText?.let(::JsonPrimitive)?:JsonNull)
                put("preferPcImagesConfigured",preferPcImages?.let(::JsonPrimitive)?:JsonNull)
                put("reachabilityVerified",false);put("modelReadyVerified",false);put("healthChecked",false)
            })
            put("connectorsVerified",false);put("networkRequested",false);put("settingsChanged",false)
            put("limitation","등록·현재 요청 허용·전제 상태·실제 실행은 다릅니다. 권한과 범위는 실행 직전에 다시 검사합니다.")
        }.toString()
        var result=output()
        while(result.length>MAX_CHARS&&tools.isNotEmpty()){tools.removeAt(tools.lastIndex);result=output()}
        check(result.length<=MAX_CHARS)
        return result
    }

    private fun safeName(raw:String):String {
        val value=raw.filterNot {it.isISOControl()}.take(120)
        // Provider display names are untrusted and may themselves contain a path/address.
        if(value.isBlank()||Regex("[/:\\\\@]|(?:\\d{1,3}\\.){3}\\d{1,3}").containsMatchIn(value))return "선택한 프로젝트"
        return value
    }
    private val PUBLIC_CHECK_KEYS=setOf(
        "android.permission.READ_SMS","android.permission.READ_CALL_LOG","android.permission.READ_CONTACTS",
        "android.permission.READ_CALENDAR","android.permission.WRITE_CALENDAR","android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_EXTERNAL_STORAGE","android.permission.RECORD_AUDIO","android.permission.POST_NOTIFICATIONS",
        "notification_access","usage_access","screen_access","kakao_screen_access",
        "handler:calendar","handler:alarm","handler:sms","handler:email","handler:maps","handler:browser")
}
