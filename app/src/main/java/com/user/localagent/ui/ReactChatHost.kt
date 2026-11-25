package com.user.localagent.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

internal data class NativeChatSlot(val frame:Rect,val visible:Rect)

/** Compose retains ownership of credentials and action decisions. The page supplies only their on-screen rectangle. */
@Composable
internal fun NativeChatOverlay(slot:NativeChatSlot?,fitVisibleHeight:Boolean,content:@Composable ()->Unit) {
    Layout(content={
        Layout(content=content,modifier=Modifier.clipToBounds()){ children,_ ->
            val frame=slot?.frame ?: Rect.Zero
            val visible=slot?.visible ?: Rect.Zero
            val height=if(fitVisibleHeight)visible.height else frame.height
            val top=if(fitVisibleHeight)0 else (frame.top-visible.top).roundToInt()
            val card=children.single().measure(Constraints.fixed(frame.width.roundToInt().coerceIn(0,4000),
                height.roundToInt().coerceIn(0,4000)))
            layout(visible.width.roundToInt().coerceIn(0,4000),visible.height.roundToInt().coerceIn(0,4000)) {
                card.place((frame.left-visible.left).roundToInt(),top)
            }
        }
    },measurePolicy={ children, available ->
        val card=children.single().measure(available.copy(minWidth=0,minHeight=0))
        layout(available.maxWidth,available.maxHeight) {
            if(slot!=null)card.place(slot.visible.left.roundToInt(),slot.visible.top.roundToInt())
        }
    })
}

/** React is packaged offline. Remote websites never share this WebView or its native UI message bridge. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ReactChatHost(snapshot:String,onCommand:(ReactUiCommand)->Unit,onUnavailable:()->Unit,modifier:Modifier=Modifier,
    onFirstReady:()->Unit={},nativeSlotId:String?=null,onNativeSlot:(NativeChatSlot?)->Unit={}){
    val firstReady by rememberUpdatedState(onFirstReady)
    val command by rememberUpdatedState(onCommand)
    val unavailable by rememberUpdatedState(onUnavailable)
    val currentSnapshot by rememberUpdatedState(snapshot)
    val receiveNativeSlot by rememberUpdatedState(onNativeSlot)
    var host by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    val photoScope=rememberCoroutineScope()
    val photoLoader=remember {StayPhotoLoader()}
    val photoRequests=remember {mutableSetOf<Triple<String,String,Int>>()}
    LaunchedEffect(host,nativeSlotId,ready) {
        val view=host
        val id=nativeSlotId
        if(view==null||id==null||!ready){receiveNativeSlot(null);return@LaunchedEffect}
        val quoted=JsonPrimitive(id).toString()
        val geometry="""(() => {
            const node = Array.from(document.querySelectorAll('[data-native-website-slot], [data-native-approval-slot]'))
                .find(item => (item.dataset.nativeWebsiteSlot ?? item.dataset.nativeApprovalSlot) === $quoted);
            const pane = node?.closest('.conversation');
            if (!pane || document.querySelector('dialog[open]')) return null;
            const r = node.getBoundingClientRect(), p = pane.getBoundingClientRect();
            return [r.left,r.top,r.right,r.bottom,Math.max(r.left,p.left),Math.max(r.top,p.top),
                Math.min(r.right,p.right),Math.min(r.bottom,p.bottom),innerWidth];
        })()"""
        while(true) {
            val bounds=kotlinx.coroutines.suspendCancellableCoroutine<NativeChatSlot?> { continuation ->
                view.evaluateJavascript(geometry){ raw ->
                    if(!continuation.isActive)return@evaluateJavascript
                    val slot=runCatching {
                        val a=JSONArray(raw)
                        val scale=(view.width/a.getDouble(8)).toFloat()
                        fun rect(i:Int)=Rect((a.getDouble(i)*scale).toFloat(),(a.getDouble(i+1)*scale).toFloat(),
                            (a.getDouble(i+2)*scale).toFloat(),(a.getDouble(i+3)*scale).toFloat())
                        val visible=rect(4)
                        if(scale.isFinite()&&scale>0f&&visible.width>0f&&visible.height>0f)
                            NativeChatSlot(rect(0),visible) else null
                    }.getOrNull()
                    continuation.resumeWith(Result.success(slot))
                }
            }
            receiveNativeSlot(bounds)
            delay(40)
        }
    }
    LaunchedEffect(ready){
        if(!ready){delay(20_000);unavailable()}
    }
    fun publish(view:WebView){
        val quoted=JsonPrimitive(currentSnapshot).toString()
        val height=view.height/view.resources.displayMetrics.density
        val viewport=if(height>0)"document.documentElement.style.setProperty('--ziphap-host-height','${height}px');"else ""
        view.evaluateJavascript("$viewport window.dispatchEvent(new CustomEvent('ziphap:snapshot',{detail:JSON.parse($quoted)}));",null)
    }
    AndroidView(modifier=modifier,factory={context->
        WebView(context).apply{
            host=this
            // A WRAP_CONTENT WebView can report a zero CSS viewport inside AndroidView even
            // when Compose gives its native view a full-height frame.
            layoutParams=android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(android.graphics.Color.rgb(247,248,252))
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=false
            settings.allowFileAccess=false;settings.allowContentAccess=false
            settings.javaScriptCanOpenWindowsAutomatically=false;settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture=true;settings.setGeolocationEnabled(false)
            settings.blockNetworkLoads=true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this,false)
            val loader=WebViewAssetLoader.Builder().addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(context)).build()
            addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->publish(this)}
            webViewClient=object:WebViewClient(){
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse?=
                    loader.shouldInterceptRequest(request.url)?:WebResourceResponse("text/plain","UTF-8",403,"Blocked",emptyMap(),java.io.ByteArrayInputStream(byteArrayOf()))
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                    val url=request.url
                    if(url.scheme=="https"&&url.host=="appassets.androidplatform.net"&&url.path.orEmpty().startsWith("/assets/react/"))return false
                    if(request.isForMainFrame&&request.hasGesture()&&request.method=="GET"&&
                        url.scheme in setOf("https","http")&&!url.host.isNullOrBlank()&&
                        !url.encodedAuthority.orEmpty().contains('@')&&url.host!="appassets.androidplatform.net"){
                        runCatching { context.startActivity(com.user.localagent.connections.CommerceListingIntent.prepare(context,url)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    return true
                }
                override fun onPageFinished(view:WebView,url:String){
                    if(url=="https://appassets.androidplatform.net/assets/react/index.html")publish(view)
                }
                override fun onReceivedError(view:WebView,request:WebResourceRequest,error:android.webkit.WebResourceError){if(request.isForMainFrame)unavailable()}
                override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse){if(request.isForMainFrame)unavailable()}
                override fun onRenderProcessGone(view:WebView,detail:android.webkit.RenderProcessGoneDetail):Boolean {
                    unavailable()
                    return true
                }
            }
            if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)){
                post{unavailable()}
            }else{
                WebViewCompat.addWebMessageListener(this,"ziphapNative",setOf("https://appassets.androidplatform.net")){view,message,origin,mainFrame,_->
                    if(mainFrame&&origin==Uri.parse("https://appassets.androidplatform.net")){
                        ReactBridgePolicy.parse(message.data.orEmpty())?.let{value->
                            if(value.action=="ready"){
                                if(!ready){ready=true;firstReady()}
                                publish(view)
                            } else if(value.action=="stayPhoto"&&ready){
                                val id=value.payload.getValue("messageId").jsonPrimitive.content
                                val index=value.payload.getValue("index").jsonPrimitive.int
                                val candidate=StayPhotoLoader.candidate(currentSnapshot,value.threadId,value.branchId,id,index)
                                val key=Triple(value.threadId,id,index)
                                fun reply(result:StayPhotoLoader.Thumbnail?){
                                    if(host!==view)return
                                    val response=buildJsonObject {
                                        put("messageId",id);put("index",index)
                                        result?.let {put("src",it.data);put("representative",it.representative)}
                                    }
                                    view.evaluateJavascript("window.dispatchEvent(new CustomEvent('ziphap:stayPhoto',{detail:$response}));",null)
                                }
                                if(key !in photoRequests){
                                    if(candidate==null||photoRequests.size>=2)reply(null)
                                    else {
                                        photoRequests.add(key)
                                        photoScope.launch {
                                            val result=try {withTimeoutOrNull(35_000){photoLoader.load(candidate)}}
                                            catch(cancel:CancellationException){throw cancel}
                                            catch(_:Exception){null}
                                            finally {photoRequests.remove(key)}
                                            if(StayPhotoLoader.candidate(currentSnapshot,value.threadId,value.branchId,id,index)==candidate)
                                                reply(result)
                                        }
                                    }
                                }
                            } else if(value.action!="stayPhoto")command(value)
                        }
                    }
                }
                loadUrl("https://appassets.androidplatform.net/assets/react/index.html")
            }
        }
    },update={view->publish(view)},onRelease={view->if(host===view)host=null;view.stopLoading();view.removeAllViews();view.destroy()})
}
