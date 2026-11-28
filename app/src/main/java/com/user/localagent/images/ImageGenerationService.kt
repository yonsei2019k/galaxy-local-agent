package com.user.localagent.images

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.user.localagent.LocalAgentApplication
import com.user.localagent.runtime.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Visible, user-started compute; no microphone, network or background screen reading. */
class ImageGenerationService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var observer:Job?=null
    private var computeWake:android.os.PowerManager.WakeLock?=null
    private var visibleIds=emptySet<Int>()
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="stop") {
            val owner=intent.getStringExtra("image_owner_id");val token=intent.getStringExtra("image_session_token")
            if(owner!=null&&token!=null)ImageSessionRegistry.cancel(owner,token)
            if(ImageSessionRegistry.sessions.value.isEmpty())stopSelf()
            return START_NOT_STICKY
        }
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("image-generation","이미지 생성",NotificationManager.IMPORTANCE_LOW))
        fun notification(status:String,session:ImageSession?=null):Notification {
            val id=session?.notificationId?:7315
            val open=packageManager.getLaunchIntentForPackage(packageName)?.apply {
                if(session?.threadId!=null)putExtra("open_thread_id",session.threadId)else putExtra("open_tab",7)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val content=open?.let {PendingIntent.getActivity(this,id,it,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)}
            val builder=NotificationCompat.Builder(this,"image-generation").setSmallIcon(R.drawable.ic_agent)
                .setContentTitle("집순이 · 이미지 생성 중").setContentText(status).setContentIntent(content)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOngoing(true)
            if(session!=null){
                val stop=PendingIntent.getService(this,id,Intent(this,ImageGenerationService::class.java).setAction("stop")
                    .putExtra("image_owner_id",session.ownerId).putExtra("image_session_token",session.token),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(0,"이 이미지 중단",stop)
            }
            return builder.build()
        }
        ServiceCompat.startForeground(this,7315,notification("이미지 작업을 준비하고 있습니다."),if(android.os.Build.VERSION.SDK_INT>=34)ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if(computeWake?.isHeld!=true){
            computeWake=getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"ZipHap:image-compute").apply{
                setReferenceCounted(false);acquire(com.user.localagent.core.PhoneImageQuality.TIMEOUT_MILLIS+120_000L)
            }
        }
        if(observer==null)observer=scope.launch {ImageSessionRegistry.sessions.flatMapLatest {sessions->
            if(sessions.isEmpty())flowOf(emptyList<Pair<ImageSession,ImageStudioState>>())
            else combine(sessions.values.map {session->session.state.map {session to it}}){it.toList()}
        }.collect {active->
            val next=active.map{it.first.notificationId}.toSet()
            (visibleIds-next).forEach(manager::cancel);visibleIds=next
            if(active.isEmpty())stopSelf()else {
                manager.notify(7315,notification("이미지 ${active.size}개 작업 중 · 개별 알림에서 중단할 수 있습니다."))
                active.forEach{(session,state)->manager.notify(session.notificationId,
                    notification(state.phase+if(state.totalSteps>0)" · ${state.step}/${state.totalSteps}"else"",session))}
            }
        }}
        return START_NOT_STICKY
    }
    override fun onDestroy(){observer?.cancel();scope.cancel();visibleIds.forEach{getSystemService(NotificationManager::class.java).cancel(it)};visibleIds=emptySet();computeWake?.let{if(it.isHeld)it.release()};computeWake=null;ServiceCompat.stopForeground(this,ServiceCompat.STOP_FOREGROUND_REMOVE);super.onDestroy()}
}
