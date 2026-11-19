package com.user.localagent.briefing

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.user.localagent.runtime.R

object BriefingNotifications {
    fun show(context:Context,registered:Int,review:Int) {
        BriefingWidget.updateAll(context)
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("briefing-results","일정·업무 분석 결과",NotificationManager.IMPORTANCE_DEFAULT))
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val intent=Intent(context,BriefingReviewActivity::class.java)
        val pending=PendingIntent.getActivity(context,2301,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(2301,NotificationCompat.Builder(context,"briefing-results").setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("새 자료 분석 결과").setContentText("일정 등록 ${registered}개 · 확인 필요 ${review}개")
            .setContentIntent(pending).addAction(0,"확인·수정",pending).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
    }
}
