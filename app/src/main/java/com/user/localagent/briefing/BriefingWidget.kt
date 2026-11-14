package com.user.localagent.briefing

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.user.localagent.LocalAgentApplication
import com.user.localagent.R
import kotlinx.serialization.json.*

class BriefingWidget:AppWidgetProvider() {
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray){updateAll(context)}
    companion object {
        fun updateAll(context:Context) {
            val app=(context.applicationContext as LocalAgentApplication).container
            val rows=runCatching {BriefingAgenda(context,app).items()}.getOrDefault(emptyList())
            val pending=rows.count {it["status"]?.jsonPrimitive?.content=="needs_review"}
            val upcoming=rows.filter {it["status"]?.jsonPrimitive?.content=="registered"}.filter {
                runCatching {java.time.OffsetDateTime.parse(it["startIso"]?.jsonPrimitive?.content).toInstant().toEpochMilli()>System.currentTimeMillis()}.getOrDefault(false)
            }.sortedBy {it["startIso"]?.jsonPrimitive?.content}
            val next=upcoming.firstOrNull()
            val text="다가오는 일정 ${upcoming.size}개 · 확인 필요 ${pending}개"+(next?.let {"\n"+it.getValue("title").jsonPrimitive.content+"\n"+it.getValue("startIso").jsonPrimitive.content}?:"")
            val views=RemoteViews(context.packageName,R.layout.briefing_widget)
            views.setTextViewText(R.id.briefing_widget_text,text)
            views.setOnClickPendingIntent(R.id.briefing_widget_text,PendingIntent.getActivity(context,2302,Intent(context,BriefingReviewActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            val manager=AppWidgetManager.getInstance(context)
            manager.updateAppWidget(manager.getAppWidgetIds(ComponentName(context,BriefingWidget::class.java)),views)
        }
    }
}
