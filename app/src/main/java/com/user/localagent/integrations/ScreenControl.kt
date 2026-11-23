package com.user.localagent.integrations

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.Path
import android.accessibilityservice.GestureDescription
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
import com.user.localagent.core.ScreenPlaybackEvidence
import com.user.localagent.core.ScreenPlaybackProgress
import com.user.localagent.core.ScreenLessonControl
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolReadPolicy
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import uniffi.ziphap_android.NativeScreenDispatchDecision
import uniffi.ziphap_android.NativeScreenRole
import uniffi.ziphap_android.NativeScreenTarget
import uniffi.ziphap_android.nativeScreenDispatchDecision
import uniffi.ziphap_android.nativeScreenSnapshotId
import uniffi.ziphap_android.nativeLessonProbeDelayMs

/**
 * Agent control of the foreground app through the owner-enabled accessibility service.
 * The owner turns it on twice: Android's accessibility consent and the in-app switch.
 * Every action re-reads the screen, refuses a changed snapshot, and returns the screen after the action.
 */
class ScreenControl(context:Context) {
    private val context=context.applicationContext
    private val prefs=this.context.getSharedPreferences("screen_control_v1",Context.MODE_PRIVATE)

    fun enabled():Boolean=prefs.getBoolean("enabled",false)
    fun setEnabled(value:Boolean){
        val edit=prefs.edit().putBoolean("enabled",value)
        if(!value)edit.remove("window_image_revision")
        edit.apply()
    }
    fun visualBinding():String?=prefs.getString("window_image_revision",null)
        ?.takeIf {Build.VERSION.SDK_INT>=34&&enabled()}
    fun setVisualEnabled(value:Boolean){
        val edit=prefs.edit()
        if(value)edit.putString("window_image_revision",UUID.randomUUID().toString())
        else edit.remove("window_image_revision")
        edit.apply()
    }
    internal var capturedVisualBinding:String?=null
    internal var isRunLive:()->Boolean={false}
    private var lastImage:ImageObservation?=null
    private var playbackWait:PlaybackWait?=null
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
    private data class Element(
        val ref:String,val role:NativeScreenRole,val label:String,val node:AccessibilityNodeInfo,
        val sensitive:Boolean,val browserUi:Boolean,val webContent:Boolean,
    )
    private data class Snapshot(val packageName:String,val texts:List<String>,val elements:List<Element>,
        val truncated:Boolean,val evidence:ScreenReadEvidence,val windowBounds:Rect,
        val webBounds:Rect?,val videoBounds:List<Rect>,val protectedForm:Boolean,
        val playback:ScreenPlaybackEvidence?,val interactionRevision:UUID,
        val browserPageDigest:String?,val disabledContentButtons:List<ScreenLessonControl>,val lessonHeadingDigest:String?,
    ) {
        val id get()=evidence.snapshotId
    }
    private data class ImageTarget(
        val ref:String,val label:String,val bounds:Rect,val fingerprint:ByteArray,val nativeRef:String?,
        val play:Boolean,val recoveryKey:String?,
    )
    private data class ImageObservation(val screenId:String,val bounds:Rect,val expiresAt:Long,val targets:List<ImageTarget>)
    private data class PlaybackScope(val request:ScreenObservationWait,val window:Int,val video:List<Rect>,
        val revision:UUID,val browserPageDigest:String?,val headingDigest:String?)
    private data class PlaybackWait(val scope:PlaybackScope,val progress:ScreenPlaybackProgress=ScreenPlaybackProgress(),
        var approvedAdvance:Pair<String,String>?=null)
    private data class LessonRead(val next:ScreenLessonControl?,val remaining:Int?,val endEpoch:Long?,val probeStatus:String)
    private data class Observation(val screen:Snapshot,val status:String?=null,val lesson:LessonRead?=null)

    suspend fun execute(call:ToolCall,approvedSnapshot:String?=null):ToolResult=try {
        requireReady()
        val read=if(call.name==ScreenControlToolCatalog.READ) {
            lastImage=null
            observe(call)
        } else null
        val result=when(call.name){
            ScreenControlToolCatalog.READ->{
                read?.let {(screen,status,lesson)->
                    val imageRequested=call.arguments[ScreenControlToolCatalog.IMAGE]?.jsonPrimitive?.booleanOrNull==true
                    val image=if(imageRequested)inspectImage(screen) else null
                    lastImage=image
                    render(screen,waitStatus=status,image=image,imageRequested=imageRequested,lesson=lesson)
                }?:ownAppView()
            }
            ScreenControlToolCatalog.ACTION->act(call,approvedSnapshot)
            else->error("지원하지 않는 화면 도구입니다.")
        }
        val summary=if(call.name==ScreenControlToolCatalog.READ) {
            if(call.arguments[ScreenControlToolCatalog.IMAGE]?.jsonPrimitive?.booleanOrNull==true){
                val code=Json.parseToJsonElement(result).jsonObject["imageStatus"]?.jsonPrimitive?.content
                    ?:"not_target_window"
                "화면 이미지 · $code · 후보 ${lastImage?.targets?.size?:0}개"
            } else if(call.arguments["requirePlaybackEnd"]?.jsonPrimitive?.booleanOrNull==true) {
                when(read?.status) {
                    "ready"->ScreenControlToolCatalog.PLAYBACK_WAIT_READY
                    "pending"->ScreenControlToolCatalog.PLAYBACK_WAIT_PENDING
                    else->"현재 화면 접근성 정보 조회"
                }
            } else "현재 화면 접근성 정보 조회"
        } else if(isImageRef(call.arguments)) {
            when(Json.parseToJsonElement(result).jsonObject["playbackStatus"]?.jsonPrimitive?.content) {
                "advancing"->"이미지 버튼 클릭 · 재생 시간 증가 확인"
                "unverified"->"이미지 버튼 클릭 · 재생 확인 대기"
                else->"이미지 버튼 클릭 · 결과 화면 확인"
            }
        } else "동작 후 화면을 다시 읽음 · 동작 유형 "+call.arguments.getValue("action").jsonPrimitive.content
        val evidence=read?.screen?.evidence?.let { observed->
            val visualKeys=lastImage?.targets.orEmpty().mapNotNull {target->
                target.recoveryKey?.let { target.ref to it }
            }
            if(visualKeys.isEmpty()) observed else observed.copy(targets=observed.targets+visualKeys)
        }
        ToolResult(call.id,true,result,VerificationState.VERIFIED,summary,screenReadEvidence=evidence)
    }catch(error:Throwable){
        if(error is kotlinx.coroutines.CancellationException)throw error
        if(error is SnapshotChanged)error.failure(call.id)
        else if(error is SetupRequired)error.failure(call.id)
        else ToolResult(call.id,false,error.message?:"화면 제어 실패",VerificationState.FAILED,"화면 동작을 실행하지 않았거나 확인하지 못함",
            effectNotApplied=error is ScreenRefused)
    }

    private suspend fun observe(call:ToolCall):Observation? {
        val request=ScreenObservationWait.from(call.arguments)
        if(request!=null&&call.arguments[ScreenControlToolCatalog.IMAGE]?.jsonPrimitive?.booleanOrNull==true)
            throw ScreenRefused("화면 이미지 확인과 화면 변화 대기는 각각 요청해야 합니다.")
        suspend fun read()=try { snapshotWhenAvailable() }
            catch(own:OwnAppForeground) { null }
        var current=read()?:run {
            if(request!=null)throw ScreenRefused("기다릴 강의 화면을 먼저 열어야 합니다.")
            return null
        }
        if(request==null)return Observation(current)
        if(request.automaticLesson&&(current.packageName!="com.android.chrome"||current.videoBounds.size!=1||
                current.truncated||current.webBounds==null))
            throw ScreenRefused("현재 크롬 페이지에서 하나의 영상 영역을 확인할 수 없습니다.")
        val originalWindow=current.evidence.windowId
        val deadline=SystemClock.elapsedRealtime()+request.seconds*1000L
        val proof=if(request.requirePlaybackEnd){
            fun scopeFor(screen:Snapshot)=PlaybackScope(request.copy(seconds=0),screen.evidence.windowId,
                screen.videoBounds,screen.interactionRevision,screen.browserPageDigest,screen.lessonHeadingDigest)
            val currentScope=scopeFor(current)
            PlaybackWait(currentScope).let {candidate->
                (playbackWait?.takeIf {it.scope==candidate.scope}?:candidate).also {playbackWait=it}
            }
        }else {
            playbackWait=null
            null
        }
        var lastClockChangeAt=SystemClock.elapsedRealtime()
        var lastClock:ScreenPlaybackEvidence?=null
        while(true){
            val enabled=current.elements.filter {it.role==NativeScreenRole.TAP&&!it.sensitive&&
                (proof==null||!it.browserUi)}
            val buttons=enabled.map {it.label}
            var status=request.status(current.packageName,current.evidence.windowId==originalWindow,
                current.texts+current.elements.filterNot {it.sensitive}.map {it.label},buttons)
            if(status==ScreenObservationWait.Status.CONTEXT_CHANGED){
                playbackWait=null
                throw ScreenRefused("대기 중 원래 강의 화면을 확인할 수 없었습니다.")
            }
            var lesson:LessonRead?=null
            if(proof!=null){
                if(!matchesPlaybackScope(proof.scope,current)||request.automaticLesson&&current.truncated){
                    playbackWait=null
                    throw ScreenRefused("대기 중 동영상이 표시된 영역이 바뀌었습니다.")
                }
                val clock=current.playback?:readVisiblePlaybackClock(current)
                val now=SystemClock.elapsedRealtime()
                if(clock!=null&&clock!=lastClock){lastClock=clock;lastClockChangeAt=now}
                val candidate=if(request.automaticLesson)ScreenLessonControl.choose(
                    enabled.filter {it.webContent&&!ScreenControlToolCatalog.isCommitLabel(it.label)}
                        .map {lessonControl(current,it)}+current.disabledContentButtons,request.button)
                    else null
                val nextDisabled=if(request.automaticLesson)candidate!=null&&candidate.ref==null else
                    request.button !in buttons&&current.disabledContentButtons.count {it.label==request.button}==1
                val ready=proof.progress.observe(clock,now,
                    nextEnabled=if(request.automaticLesson)candidate?.ref!=null else status==ScreenObservationWait.Status.READY,
                    nextDisabled=nextDisabled,adaptive=request.automaticLesson)
                status=if(ready)ScreenObservationWait.Status.READY else ScreenObservationWait.Status.PENDING
                if(request.automaticLesson){
                    proof.approvedAdvance=candidate?.ref?.takeIf {ready}?.let {current.id to it}
                    val remaining=proof.progress.remainingSeconds()
                    val probeStatus=when {
                        ready->"finished"
                        clock==null->"clock_unavailable"
                        remaining!=null->"playing"
                        else->"checking_playback"
                    }
                    lesson=LessonRead(candidate?.takeIf {ready},remaining,
                        remaining?.let {System.currentTimeMillis()+it*1000L},probeStatus)
                    if(!ready&&now-lastClockChangeAt>=20_000&&clock?.let {it.elapsed<it.duration}!=false)
                        return Observation(current,"pending",lesson.copy(probeStatus=if(clock==null)"clock_unavailable" else "not_advancing"))
                }
            }
            val remaining=deadline-SystemClock.elapsedRealtime()
            if(status==ScreenObservationWait.Status.READY||remaining<=0)
                return Observation(current,status.name.lowercase(Locale.ROOT),lesson)
            delay(if(request.automaticLesson)nativeLessonProbeDelayMs(lesson?.remaining?.toUInt(),remaining.toUInt()).toLong()
                else minOf(2000L,remaining))
            current=try {read()} catch (refused:ScreenRefused) {
                playbackWait=null
                throw refused
            } ?:run {
                playbackWait=null
                throw ScreenRefused("대기 중 집합 화면으로 돌아왔습니다. 원래 강의 화면을 다시 확인해야 합니다.")
            }
        }
    }

    private fun matchesPlaybackScope(scope:PlaybackScope,screen:Snapshot):Boolean =
        !screen.protectedForm&&screen.evidence.windowId==scope.window&&screen.videoBounds==scope.video&&
            screen.interactionRevision==scope.revision&&screen.browserPageDigest==scope.browserPageDigest&&
            (!scope.request.automaticLesson||screen.lessonHeadingDigest==scope.headingDigest)

    private fun besidePlayer(control:Rect,video:Rect?):Boolean = video!=null&&
        (control.top>=video.bottom&&control.top-video.bottom<=video.height()/2&&
            control.centerX() in video.centerX()..video.right||
        control.left>=video.right&&control.left-video.right<=video.width()/4&&
            control.centerY() in video.top..video.bottom)

    private fun lessonControl(screen:Snapshot,element:Element):ScreenLessonControl =
        ScreenLessonControl(element.label,element.ref,
            besidePlayer(Rect().also(element.node::getBoundsInScreen),screen.videoBounds.singleOrNull()))

    private fun checkLessonAdvance(screen:Snapshot,target:Element){
        if(target.webContent)checkLessonAdvance(screen,lessonControl(screen,target))
    }

    private fun checkLessonAdvance(screen:Snapshot,target:ScreenLessonControl){
        val watching=playbackWait?.takeIf {it.scope.request.automaticLesson&&matchesPlaybackScope(it.scope,screen)}?:return
        if(ScreenLessonControl.isCandidate(target,watching.scope.request.button)&&
            watching.approvedAdvance!=(screen.id to target.ref))
            throw ScreenRefused("같은 강의의 재생 종료와 다음 버튼을 확인한 뒤 이동할 수 있습니다.")
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
        if(action=="tap"&&isImageRef(call.arguments)){
            val image=requireImageTarget(screen,call.arguments)
            if(!image.play)checkLessonAdvance(screen,lessonImageControl(screen,image))
            return !image.play to describe(call,app,image.label)
        }
        if(action=="tap")checkLessonAdvance(screen,requireTapTarget(target,call.arguments))
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
        val imageTap=action=="tap"&&isImageRef(a)
        val imagePlay=if(imageTap)tapImage(a,requested,approvedSnapshot) else {
            onMain {
                lastImage=null
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
            null
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
        val playback=imagePlay?.let {before->
            if(samePlaybackContext(before,after))verifyPlayback(after) else after to "unverified"
        }
        val latest=playback?.first?:after
        return render(latest,action,requested!=latest.id,
            expectedChange=if(imageTap)a["expectedChange"]?.jsonPrimitive?.content else null,
            playbackStatus=playback?.second)
    }

    private suspend fun verifyPlayback(first:Snapshot):Pair<Snapshot,String> {
        var current=first
        var previous:Pair<ScreenPlaybackEvidence,Long>?=null
        return withTimeoutOrNull(10_000) {
            repeat(4) {attempt->
                if(attempt>0){
                    delay(1500)
                    current=try {snapshotWhenAvailable()} catch (_:ScreenRefused) {return@withTimeoutOrNull null}
                }
                if(!isRunLive()||!samePlaybackContext(first,current))return@withTimeoutOrNull null
                val clock=current.playback?:readVisiblePlaybackClock(current)?:return@repeat
                val now=SystemClock.elapsedRealtime()
                if(previous?.let {(before,at)->clock.advancedFrom(before,now-at)}==true)
                    return@withTimeoutOrNull current to "advancing"
                previous=clock to now
            }
            null
        } ?: (current to "unverified")
    }

    private fun samePlaybackContext(first:Snapshot,second:Snapshot):Boolean =
        !second.protectedForm&&second.interactionRevision==first.interactionRevision&&
            second.evidence.windowId==first.evidence.windowId&&second.packageName==first.packageName&&
            second.videoBounds==first.videoBounds&&second.browserPageDigest==first.browserPageDigest

    private fun isImageRef(args:JsonObject)=args["ref"]?.jsonPrimitive?.content?.startsWith("v")==true

    private fun lessonImageControl(screen:Snapshot,target:ImageTarget):ScreenLessonControl =
        ScreenLessonControl(target.label,target.ref,besidePlayer(
            Rect(target.bounds).apply {offset(screen.windowBounds.left,screen.windowBounds.top)},
            screen.videoBounds.singleOrNull()))

    private fun visualBlock(screen:Snapshot):String?=when {
        !isRunLive()||capturedVisualBinding==null||visualBinding()!=capturedVisualBinding->"image_access_not_enabled_for_run"
        screen.protectedForm->"protected_form"
        screen.truncated->"incomplete_accessibility_view"
        screen.packageName!="com.android.chrome"||screen.webBounds==null->"not_chrome_page"
        else->null
    }

    private suspend fun capture(screen:Snapshot):android.graphics.Bitmap? {
        if(Build.VERSION.SDK_INT<34||visualBlock(screen)!=null)return null
        val bitmap=withContext(Dispatchers.Main.immediate) {
            val bridge=service()
            val foreground=bridge.windows.singleOrNull {it.displayId==Display.DEFAULT_DISPLAY&&it.isFocused&&
                it.type==AccessibilityWindowInfo.TYPE_APPLICATION}
            if(foreground==null||foreground.id!=screen.evidence.windowId||bridge.screenInteractionRevision!=screen.interactionRevision||
                foreground.root?.packageName?.toString()!=screen.packageName||
                context.getSystemService(KeyguardManager::class.java).isKeyguardLocked||
                visualBlock(screen)!=null)null else try {
                ScreenWindowImage.capture(bridge,screen.evidence.windowId)
            } catch (_: SecurityException) {
                // An Android service without the new capture capability can still supply the AX read.
                null
            }
        }?:return null
        if(bitmap.width!=screen.windowBounds.width()||bitmap.height!=screen.windowBounds.height()){
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    private suspend fun readVisiblePlaybackClock(screen:Snapshot):ScreenPlaybackEvidence? {
        val region=screen.videoBounds.singleOrNull()?:return null
        if(screen.webBounds?.contains(region)!=true)return null
        val image=capture(screen)?:return null
        try {
            val area=Rect(region).apply {offset(-screen.windowBounds.left,-screen.windowBounds.top)}
            val clock=ScreenPlaybackEvidence.from(ScreenWindowImage.lines(image,area).map {it.text})?:return null
            return clock.takeIf {samePlaybackContext(screen,snapshotWhenAvailable())&&visualBlock(screen)==null}
        } catch (error:RuntimeException) {
            if(error is kotlinx.coroutines.CancellationException)throw error
            return null
        } finally {image.recycle()}
    }

    private suspend fun inspectImage(screen:Snapshot):ImageObservation? {
        val image=capture(screen)?:return null
        try {
            val web=checkNotNull(screen.webBounds)
            fun relative(bounds:Rect)=Rect(bounds).apply {offset(-screen.windowBounds.left,-screen.windowBounds.top)}
            val crop=relative(web)
            val candidates=mutableListOf<ImageTarget>()
            fun add(label:String,bounds:Rect,nativeRef:String?,play:Boolean) {
                val fingerprint=ScreenWindowImage.fingerprint(image,bounds)
                val recoveryKey=screen.browserPageDigest?.takeIf {play}?.let {page->
                    val digest=MessageDigest.getInstance("SHA-256")
                    digest.update(listOf("image-play",capturedVisualBinding,page,screen.interactionRevision,
                        screen.windowBounds,screen.videoBounds,bounds,nativeRef!=null).joinToString("|").toByteArray(Charsets.UTF_8))
                    digest.digest(fingerprint).joinToString("") {"%02x".format(it.toInt() and 255)}
                }
                candidates+=ImageTarget("v"+UUID.randomUUID().toString().replace("-","").take(11),label,bounds,
                    fingerprint,nativeRef,play,recoveryKey)
            }
            val unlabeled=onMain {
                screen.elements.filter {it.webContent&&!it.sensitive&&it.role==NativeScreenRole.TAP&&it.label.isBlank()}
                    .mapNotNull { element ->
                        val bounds=relative(Rect().also(element.node::getBoundsInScreen))
                        (element.ref to bounds).takeIf {crop.contains(bounds)&&bounds.width()>=24&&bounds.height()>=24}
                    }
            }
            val nativeTaps=onMain {
                screen.elements.filter {it.role==NativeScreenRole.TAP&&!it.sensitive&&it.label.isNotBlank()}
                    .map {relative(Rect().also(it.node::getBoundsInScreen))}
            }
            withContext(Dispatchers.Default) {
                val visibleVideo=screen.videoBounds.map(::relative).filter {crop.contains(it)}
                val play=visibleVideo.mapNotNull {video->
                    ScreenWindowImage.playPoint(image,video)
                        ?.takeUnless {candidate->nativeTaps.any {it.contains(candidate.centerX(),candidate.centerY())}}
                }.singleOrNull()
                if(play!=null){
                    val boundButtons=unlabeled.filter {it.second.contains(play.centerX(),play.centerY())}
                    if(boundButtons.size<=1)add("▶ 동영상 재생",play,boundButtons.singleOrNull()?.first,true)
                }
                if(unlabeled.isNotEmpty()){
                    val lines=try {ScreenWindowImage.lines(image,crop)}
                    catch (error:RuntimeException) {
                        if(error is kotlinx.coroutines.CancellationException)throw error
                        emptyList()
                    }
                    for ((ref,bounds) in unlabeled.take(16)) {
                        if(play!=null&&Rect.intersects(play,bounds))continue
                        val line=lines.singleOrNull {it.text.isNotBlank()&&bounds.contains(it.bounds)}?:continue
                        // Each OCR line must identify exactly one unnamed accessibility target.
                        if(unlabeled.count {it.second.contains(line.bounds)}==1)add(line.text,bounds,ref,false)
                    }
                }
            }
            if(snapshotWhenAvailable().id!=screen.id)throw SnapshotChanged(afterApproval=false)
            return ImageObservation(screen.id,screen.windowBounds,SystemClock.elapsedRealtime()+90_000,candidates)
        } finally { image.recycle() }
    }

    private fun requireImageTarget(screen:Snapshot,args:JsonObject):ImageTarget {
        val observation=lastImage
        if(visualBlock(screen)!=null||observation==null||observation.screenId!=screen.id||
            observation.bounds!=screen.windowBounds||observation.expiresAt<=SystemClock.elapsedRealtime())
            throw ScreenRefused("화면 이미지가 만료되었거나 권한이 없습니다. 화면을 다시 확인하세요.")
        val target=observation.targets.singleOrNull {it.ref==args["ref"]?.jsonPrimitive?.content}
        if(target==null||!ScreenControlToolCatalog.matchesTapLabel(target.label,args["expectedLabel"]?.jsonPrimitive?.content)||
            !ScreenControlToolCatalog.matchesTapArea(false,args["targetArea"]?.jsonPrimitive?.content)||
            args["expectedChange"]?.jsonPrimitive?.content.isNullOrBlank())
            throw ScreenRefused("이미지에서 본 버튼과 클릭 뒤 확인할 결과를 지정해야 합니다.")
        return target
    }

    private suspend fun tapImage(args:JsonObject,requested:String,approved:String?):Snapshot? {
        val screen=try {snapshotWhenAvailable()} catch(refused:ScreenRefused) {
            if(approved!=null)throw SnapshotChanged(afterApproval=true)
            throw refused
        }
        requireCurrentSnapshot(requested,screen.id,approved)
        val target=requireImageTarget(screen,args)
        if(!target.play&&approved!=requested)throw ScreenRefused("이미지에서 읽은 버튼은 클릭 전에 확인이 필요합니다.")
        val frame=capture(screen)?:throw ScreenRefused("현재 창의 이미지를 다시 확인할 수 없습니다.")
        val same=try {withContext(Dispatchers.Default){
            ScreenWindowImage.fingerprint(frame,target.bounds).contentEquals(target.fingerprint)
        }} finally {frame.recycle()}
        if(!same)throw SnapshotChanged(afterApproval=approved!=null)
        withContext(Dispatchers.Main) {
            val bridge=service()
            val current=snapshot(bridge)
            requireCurrentSnapshot(requested,current.id,approved)
            if(requireImageTarget(current,args)!==target)throw SnapshotChanged(afterApproval=approved!=null)
            if(!target.play)checkLessonAdvance(current,lessonImageControl(current,target))
            val x=target.bounds.centerX()+current.windowBounds.left
            val y=target.bounds.centerY()+current.windowBounds.top
            val windows=bridge.windows.filter {it.displayId==Display.DEFAULT_DISPLAY}
            val targetLayer=windows.singleOrNull {it.id==current.evidence.windowId}?.layer
                ?:throw ScreenRefused("대상 창의 위치를 다시 확인해야 합니다.")
            val obscured=windows.any { window->
                window.id!=current.evidence.windowId&&window.layer>=targetLayer&&
                    Rect().also(window::getBoundsInScreen).contains(x,y)&&
                    (window.type!=AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY||
                        window.root?.packageName?.toString()!=context.packageName)
            }
            if(obscured||bridge.screenIndicatorCovers(x,y))
                throw ScreenRefused("다른 창이 버튼을 가리고 있어 누르지 않았습니다.")
            lastImage=null
            if(target.nativeRef!=null){
                val element=current.elements.single {it.ref==target.nativeRef}
                check(element.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)){"화면의 버튼을 누르지 못했습니다."}
            } else {
                val path=Path().apply {moveTo(x.toFloat(),y.toFloat())}
                val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,50)).build()
                val settled=withTimeoutOrNull(1800) {
                    suspendCancellableCoroutine { continuation ->
                        val accepted=bridge.dispatchGesture(gesture,object:AccessibilityService.GestureResultCallback(){
                            override fun onCompleted(description:GestureDescription){if(continuation.isActive)continuation.resume(true)}
                            override fun onCancelled(description:GestureDescription){if(continuation.isActive)continuation.resume(false)}
                        },null)
                        if(!accepted&&continuation.isActive)
                            continuation.resumeWith(Result.failure(ScreenRefused("Android가 터치를 접수하지 않았습니다.")))
                    }
                }
                check(settled==true){"터치 결과를 확인하지 못했습니다."}
            }
        }
        return screen.takeIf {target.play}
    }

    private fun perform(screen:Snapshot,action:String,a:JsonObject) {
        val ref=a["ref"]?.jsonPrimitive?.content
        val target=ref?.let{r->screen.elements.firstOrNull{it.ref==r}}
        when(action){
            "tap"->{
                val checked=requireTapTarget(target,a)
                checkLessonAdvance(screen,checked)
                check(checked.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)){"요소를 누르지 못했습니다."}
                if(playbackWait?.approvedAdvance==(screen.id to checked.ref))playbackWait=null
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

    private fun requireTapTarget(target:Element?,arguments:JsonObject):Element {
        val expected=(arguments["expectedLabel"] as? JsonPrimitive)?.takeIf {it.isString}?.content
        val area=(arguments["targetArea"] as? JsonPrimitive)?.takeIf {it.isString}?.content
        if(target==null||target.role!=NativeScreenRole.TAP||target.sensitive||
            !ScreenControlToolCatalog.matchesTapLabel(target.label,expected))
            throw ScreenRefused("누를 버튼의 실제 이름과 번호가 일치하지 않아 실행하지 않았습니다. 화면을 다시 읽어 같은 요소를 확인하세요.")
        if(!ScreenControlToolCatalog.matchesTapArea(target.browserUi,area))
            throw ScreenRefused("웹페이지 안의 버튼과 크롬 자체 조작 버튼을 혼동해 누르지 않았습니다. 현재 페이지를 다시 확인하세요.")
        return target
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
        val windowBounds=Rect().also(window::getBoundsInScreen)
        val webAreas=mutableListOf<Rect>();val videoAreas=mutableListOf<Rect>();val videoLabels=mutableListOf<String>()
        val disabledContentButtons=mutableListOf<Pair<String,Rect>>()
        val headings=mutableListOf<String>()
        var browserPage:String?=null
        var protectedForm=false
        var visited=0;var truncated=false;var textBudget=6000
        fun visit(node:AccessibilityNodeInfo,depth:Int,sensitiveAncestor:Boolean,webAncestor:Boolean,videoAncestor:Boolean) {
            if(visited>=600||depth>30){truncated=true;return}
            visited++
            if(!node.isVisibleToUser)return
            val sensitive=sensitiveAncestor||sensitive(node)
            val className=node.className?.toString()
            val webContent=webAncestor||className=="android.webkit.WebView"
            if(sensitive)protectedForm=true
            if(className=="android.webkit.WebView") {
                Rect().also(node::getBoundsInScreen).takeIf {it.intersect(windowBounds)}?.let(webAreas::add)
            }
            val mediaRole=node.extras.getCharSequence("AccessibilityNodeInfo.roleDescription")?.toString()
            val video=webContent&&!sensitive&&(className in setOf("android.widget.VideoView","android.webkit.HTMLVideoElement")||
                mediaRole in setOf("video","비디오","동영상"))
            if(video){
                val area=Rect().also(node::getBoundsInScreen)
                if(windowBounds.contains(area)&&area.width()>=80&&area.height()>=80)videoAreas+=area
            }
            val browserUi=ScreenControlToolCatalog.isBrowserUi(packageName,webContent)
            val label=labelOf(node)
            if(webContent&&!sensitive&&node.isHeading&&label.isNotBlank()&&headings.size<12)headings+=label.take(120)
            if(browserUi&&node.viewIdResourceName=="com.android.chrome:id/url_bar")
                browserPage=node.text?.toString()?.takeIf {it.isNotBlank()}
            if(webContent&&!sensitive&&!node.isEnabled&&
                (node.isClickable||className=="android.widget.Button")&&disabledContentButtons.size<48) {
                label.ifBlank {singleVisibleButtonLabel(node)}.take(120).takeIf {it.isNotBlank()}
                    ?.let {disabledContentButtons+=it to Rect().also(node::getBoundsInScreen)}
            }
            if(!sensitive&&(video||videoAncestor)&&videoLabels.size<32){
                val qualified=mediaRole?.takeIf {
                    it.lowercase(Locale.ROOT) in setOf("elapsed time","remaining time","경과 시간","남은 시간")
                }?.let {"$it: $label"}
                videoLabels+=listOfNotNull(label,node.contentDescription?.toString(),node.stateDescription?.toString(),qualified)
                    .filter {it.isNotBlank()}.map {it.take(120)}.distinct().take(32-videoLabels.size)
            }
            val actions=node.actionList.map{it.id}.toSet()
            val role=when {
                node.isEditable&&AccessibilityNodeInfo.ACTION_SET_TEXT in actions->NativeScreenRole.EDIT
                node.isClickable&&AccessibilityNodeInfo.ACTION_CLICK in actions&&node.isEnabled->NativeScreenRole.TAP
                node.isScrollable->NativeScreenRole.SCROLL
                else->null
            }
            if(role!=null&&elements.size<150){
                val shown=if(node.isPassword)"[보호된 입력칸]" else label.ifBlank {
                    if(role==NativeScreenRole.TAP)singleVisibleButtonLabel(node) else descendantLabel(node)
                }.take(120)
                elements+=Element("e${elements.size+1}",role,shown,node,sensitive||node.isPassword,browserUi,webContent)
            } else if(label.isNotBlank()&&!node.isPassword&&textBudget>0){
                val piece=label.take(200);texts+=piece;textBudget-=piece.length
            }
            for(index in 0 until node.childCount.coerceAtMost(80))node.getChild(index)?.let{visit(it,depth+1,sensitive,webContent,video||videoAncestor)}
            if(node.childCount>80)truncated=true
        }
        visit(root,0,false,false,false)
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
            for (part in listOf(node.packageName,node.className,resourceId,uniqueId,element.browserUi,element.webContent)) {
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
        val revision=service.screenInteractionRevision
        val pageDigest=browserPage?.let {hash(it).hex()}
        val layout=hash(listOf(windowBounds,webAreas.distinct(),videoAreas.distinct(),protectedForm,revision,pageDigest,
            disabledContentButtons).toString())
        val id=checkNotNull(nativeScreenSnapshotId(snapshotNonce,packageDigest,window.id,truncated,
            texts.take(599).map(::hash)+listOf(layout),targets)){"화면 관측을 확인하지 못했습니다."}
        return Snapshot(packageName,texts,elements,truncated,ScreenReadEvidence(id,packageDigest.hex(),window.id,targetKeys),
            windowBounds,webAreas.distinct().singleOrNull(),videoAreas.distinct(),protectedForm,
            if(videoAreas.distinct().size==1)ScreenPlaybackEvidence.from(videoLabels) else null,revision,
            pageDigest,disabledContentButtons.map {(label,bounds)->
                ScreenLessonControl(label,null,besidePlayer(bounds,videoAreas.distinct().singleOrNull()))
            },headings.takeIf {it.isNotEmpty()}?.let {hash(it.joinToString("\n")).hex()})
    }

    private fun render(screen:Snapshot,action:String?=null,changed:Boolean=false,waitStatus:String?=null,
        image:ImageObservation?=null,imageRequested:Boolean=false,expectedChange:String?=null,
        playbackStatus:String?=null,lesson:LessonRead?=null):String=buildJsonObject {
        waitStatus?.let {put("waitStatus",it)}
        if(action!=null){put("lastAction",action);put("screenChanged",changed)}
        if(expectedChange!=null){put("expectedChange",expectedChange);put("effectStatus","check_current_screen")}
        playbackStatus?.let {put("playbackStatus",it)}
        lesson?.let {result->
            put("playbackProbeStatus",result.probeStatus)
            result.remaining?.let {put("remainingPlaybackSeconds",it)}
            result.endEpoch?.let {put("estimatedEndEpochMillis",it)}
            result.next?.let { next->
                put("nextLessonControl",buildJsonObject {put("ref",next.ref);put("label",next.label)})
            }
        }
        screen.playback?.let {put("mediaElapsedSeconds",it.elapsed);put("mediaDurationSeconds",it.duration)}
        if(imageRequested)put("imageStatus",when {
            image==null->visualBlock(screen)?:"capture_unavailable"
            image.targets.isEmpty()->"no_reliable_candidate"
            else->"candidates_found"
        })
        put("untrustedScreenData",true);put("packageName",screen.packageName);put("app",appLabel(screen.packageName))
        put("snapshotId",screen.id);put("truncated",screen.truncated)
        put("texts",JsonArray(screen.texts.map(::JsonPrimitive)))
        put("elements",buildJsonArray{screen.elements.forEach{e->add(buildJsonObject{
            put("ref",e.ref);put("role",e.role.name.lowercase(Locale.ROOT));put("label",e.label)
            if(e.browserUi)put("targetArea","browser_ui")
            if(e.sensitive||(e.role==NativeScreenRole.TAP&&e.label.isBlank()))put("blocked",true)
        })}
            image?.targets?.forEach { target->add(buildJsonObject {
                put("ref",target.ref);put("role","tap");put("label",target.label);put("source","image")
                put("targetArea","content");put("requiresOwner",!target.play)
            })}
        })
    }.toString()

    private fun labelOf(node:AccessibilityNodeInfo):String=
        listOfNotNull(node.text?.toString(),node.contentDescription?.toString(),node.hintText?.toString())
            .firstOrNull{it.isNotBlank()}?.replace(Regex("\\s+")," ")?.trim().orEmpty()
    private fun singleVisibleButtonLabel(node:AccessibilityNodeInfo):String {
        val labels=linkedSetOf<String>()
        fun collect(parent:AccessibilityNodeInfo,depth:Int){
            if(depth==4||labels.size>1)return
            for(i in 0 until parent.childCount.coerceAtMost(12)){
                val child=parent.getChild(i)?:continue
                if(!child.isVisibleToUser||child.isClickable||child.isEditable||sensitive(child))continue
                val label=labelOf(child)
                if(label.isNotBlank())labels+=label else collect(child,depth+1)
                if(labels.size>1)return
            }
        }
        collect(node,0)
        return labels.singleOrNull().orEmpty()
    }

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
