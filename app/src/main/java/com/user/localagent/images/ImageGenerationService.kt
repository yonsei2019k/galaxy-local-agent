package com.user.localagent.images

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.user.localagent.LocalAgentApplication
import com.user.localagent.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Visible, user-started compute; no microphone, network or background screen reading. */
class ImageGenerationService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var observer:Job?=null
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="stop") {(application as LocalAgentApplication).container.imageStudio.cancel();stopSelf();return START_NOT_STICKY}
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("image-generation","이미지 생성",NotificationManager.IMPORTANCE_LOW))
        val stop=PendingIntent.getService(this,7315,Intent(this,ImageGenerationService::class.java).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open=packageManager.getLaunchIntentForPackage(packageName)?.apply {putExtra("open_tab",7);addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)}
        val content=open?.let {PendingIntent.getActivity(this,7315,it,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)}
        fun notification(status:String):Notification=NotificationCompat.Builder(this,"image-generation").setSmallIcon(R.drawable.ic_agent).setContentTitle("ZipHap.Agent · 이미지 생성 중")
            .setContentText(status).setContentIntent(content).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOngoing(true).addAction(0,"중단",stop).build()
        ServiceCompat.startForeground(this,7315,notification("휴대전화에서 만들고 있습니다."),if(android.os.Build.VERSION.SDK_INT>=34)ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if(observer==null)observer=scope.launch {(application as LocalAgentApplication).container.imageStudio.state.collect {state->
            if(state.running)manager.notify(7315,notification(state.phase+if(state.totalSteps>0)" · ${state.step}/${state.totalSteps}" else ""))
        }}
        return START_NOT_STICKY
    }
    override fun onDestroy(){observer?.cancel();scope.cancel();ServiceCompat.stopForeground(this,ServiceCompat.STOP_FOREGROUND_REMOVE);super.onDestroy()}
}
