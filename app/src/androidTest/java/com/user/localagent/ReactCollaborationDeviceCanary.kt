package com.user.localagent

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.collab.CollaborationRoom
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Explicit synthetic data-only message to 'user'. No agent watcher, inference, tools or owner data writes. */
class ReactCollaborationDeviceCanary {
    private fun web(view:View):WebView? {
        if(view is WebView)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)web(view.getChildAt(i))?.let{return it}
        return null
    }
    @Test fun realReactBridgeAndPairedPcRoundTrip()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("collaborationCanaryApproved")=="true")
        val context=instrumentation.targetContext
        val text="협업방 실제 폰 연결 검사 "+UUID.randomUUID().toString().take(8)+" · 실행 요청이나 승인이 아닙니다."
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use{scenario->
            suspend fun js(code:String):String {
                val reply=CompletableDeferred<String>()
                scenario.onActivity{activity->
                    val view=web(activity.window.decorView)
                    if(view==null)reply.complete("null")else view.evaluateJavascript(code){reply.complete(it)}
                }
                return withTimeout(5000){reply.await()}
            }
            suspend fun until(timeout:Long,condition:suspend()->Boolean){withTimeout(timeout){while(!condition())delay(150)}}
            until(30_000){js("Boolean(document.querySelector('select[aria-label=\"다음 요청 모델\"]:not(:disabled)')) && document.getElementById('root').getBoundingClientRect().height>innerHeight*0.8")=="true"}
            js("Array.from(document.querySelectorAll('button')).find(b=>(b.getAttribute('aria-label')||'').includes('메뉴')&&b.getClientRects().length)?.click();true")
            until(10_000){js("Boolean(Array.from(document.querySelectorAll('button')).find(b=>b.textContent.trim()==='협업방'&&b.getClientRects().length))")=="true"}
            js("Array.from(document.querySelectorAll('button')).find(b=>b.textContent.trim()==='협업방'&&b.getClientRects().length).click();true")
            until(60_000){js("Boolean(document.querySelector('textarea[aria-label=\"협업 메시지\"]:not(:disabled)'))")=="true"}
            js("var s=document.querySelector('form[aria-label=\"협업 메시지 작성\"] select');s.value='user';s.dispatchEvent(new Event('change',{bubbles:true}));true")
            val quoted=JsonPrimitive(text).toString()
            js("var t=document.querySelector('textarea[aria-label=\"협업 메시지\"]');Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(t,$quoted);t.dispatchEvent(new Event('input',{bubbles:true}));true")
            until(10_000){js("Boolean(document.querySelector('button[aria-label=\"협업 메시지 보내기\"]:not(:disabled)'))")=="true"}
            js("document.querySelector('button[aria-label=\"협업 메시지 보내기\"]').click();true")
            val room=CollaborationRoom.get(context)
            until(30_000){room.state.value["messages"]?.jsonArray?.any{it.jsonObject["text"]?.jsonPrimitive?.content==text}==true&&room.state.value["sendReceipt"]?.jsonObject?.get("accepted")?.jsonPrimitive?.boolean==true}
            val saved=room.state.value.getValue("messages").jsonArray.filter{it.jsonObject["text"]?.jsonPrimitive?.content==text}
            assertEquals(1,saved.size)
            assertEquals("user",saved.single().jsonObject.getValue("recipient").jsonPrimitive.content)
            assertTrue(saved.single().jsonObject.getValue("dataOnly").jsonPrimitive.boolean)
            assertFalse(saved.single().jsonObject.getValue("executionAuthority").jsonPrimitive.boolean)
            until(10_000){js("document.querySelector('textarea[aria-label=\"협업 메시지\"]').value==='' && document.querySelector('[aria-label=\"협업방 메시지\"]').textContent.includes($quoted)")=="true"}
            instrumentation.sendStatus(0,android.os.Bundle().apply{putString("stream","React/Android/paired-PC data-only message saved, acknowledged and rendered; synthetic recipient=user; inference0.\n")})
        }
    }
}
