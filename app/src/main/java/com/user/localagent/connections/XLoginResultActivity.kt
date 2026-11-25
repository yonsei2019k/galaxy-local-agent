package com.user.localagent.connections

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.*

class XLoginResultActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val message=TextView(this).apply {setPadding(32,48,32,32);text="X 계정 연결을 확인하고 있어요."}
        setContentView(message)
        scope.launch {
            val hub=ConnectionHub.get(this@XLoginResultActivity)
            try {
                hub.privateReads.finishX(requireNotNull(intent.data))
                hub.clearCache()
                message.text="X 계정을 연결했어요. 뒤로 가면 북마크를 확인할 수 있어요."
            }catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){message.text="X 계정 연결을 마치지 못했어요. 앱과 자료 연결에서 다시 시작해 주세요."}
        }
    }
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}
