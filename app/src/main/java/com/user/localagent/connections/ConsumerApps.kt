package com.user.localagent.connections

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.user.localagent.core.ToolCall
import com.user.localagent.core.connections.ConsumerApp
import com.user.localagent.core.connections.ConsumerAppCatalog

class ConsumerApps(private val context:Context) {
    fun installed():Set<ConsumerApp> = ConsumerApp.entries.filter {app->
        runCatching {context.packageManager.getPackageInfo(app.packageName,0);true}.getOrDefault(false)
    }.toSet()
    fun prepare(call:ToolCall):Intent {
        val target=ConsumerAppCatalog.target(call)
        if (call.name == com.user.localagent.core.connections.CoupangReviewEvidence.OPEN_TOOL) {
            return Intent(context, CoupangReviewActivity::class.java)
        }
        if(target.uri==null)return context.packageManager.getLaunchIntentForPackage(target.app.packageName)
            ?:error("${target.app.label} 앱이 설치되어 있는지 확인해주세요.")
        val uri=Uri.parse(target.uri)
        val intent=Intent(Intent.ACTION_VIEW,uri)
        if(uri.scheme=="https")intent.addCategory(Intent.CATEGORY_BROWSABLE)
        val specific=Intent(intent).setPackage(target.app.packageName)
        if(specific.resolveActivity(context.packageManager)!=null)return specific
        if(uri.scheme=="https"&&intent.resolveActivity(context.packageManager)!=null)return intent
        error("${target.app.label}의 현재 버전에서 이 화면을 열 수 없습니다.")
    }
}
