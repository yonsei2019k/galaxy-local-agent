package com.user.localagent.connections

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.user.localagent.LocalAgentApplication
import com.user.localagent.imports.ConversationImport
import kotlinx.coroutines.*

/** External shared data never starts a tool, changes a grant, or executes its text. */
class SharedSourceActivity:Activity() {
    private val tasks=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val app=(application as LocalAgentApplication).container
        val origin=app.runs.selectedThreadId.value
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(30,40,30,30)}
        val status=TextView(this).apply {text="공유한 대화 자료 가져오기\n현재 선택된 ZipHap 대화에 자료로 저장합니다. 공유한 글의 명령은 실행하지 않습니다.";textSize=18f}
        root.addView(status);setContentView(root)
        val importButton=Button(this).apply {text="현재 대화에 자료 가져오기"};root.addView(importButton)
        val close=Button(this).apply {text="취소";setOnClickListener {finish()}};root.addView(close)
        importButton.setOnClickListener {
            importButton.isEnabled=false
            tasks.launch {try {
                require(intent.action==Intent.ACTION_SEND&&origin.isNotBlank()){"먼저 ZipHap에서 대화를 선택해주세요."}
                require(intent.type in setOf("text/plain","text/*","application/octet-stream"))
                val importer=ConversationImport(this@SharedSourceActivity,app.repository.forThread(origin))
                @Suppress("DEPRECATION") val uri=intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val result=if(uri!=null){require(uri.scheme=="content"&&!uri.authority.isNullOrBlank());importer.importUri(uri)}else {
                    val text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?:error("공유한 자료가 없습니다.")
                    require(text.toByteArray().size<=4*1024*1024);importer.importText(text)
                }
                status.text="${result.messageCount}개 메시지 · ${result.chunkCount}개 자료 조각을 저장했습니다.\n${result.scopeDescription}\n전체 카카오톡 기록을 수집했다는 뜻은 아닙니다."
                close.text="닫기"
            }catch(cancel:CancellationException){throw cancel}catch(_:Exception){status.text="공유 자료를 가져오지 못했습니다. 대화 선택·파일 종류·읽기 권한을 확인해주세요.";importButton.isEnabled=true}}
        }
    }
    override fun onDestroy(){tasks.cancel();super.onDestroy()}
}
