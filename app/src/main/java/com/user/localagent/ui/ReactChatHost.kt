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
import kotlinx.serialization.json.JsonPrimitive

/** React is packaged offline. Remote websites never share this WebView or its native UI message bridge. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ReactChatHost(snapshot:String,onCommand:(ReactUiCommand)->Unit,onUnavailable:()->Unit,modifier:Modifier=Modifier){
    val command by rememberUpdatedState(onCommand)
    val unavailable by rememberUpdatedState(onUnavailable)
    val currentSnapshot by rememberUpdatedState(snapshot)
    fun publish(view:WebView){
        val quoted=JsonPrimitive(currentSnapshot).toString()
        val height=view.height/view.resources.displayMetrics.density
        val viewport=if(height>0)"document.documentElement.style.setProperty('--ziphap-host-height','${height}px');"else ""
        view.evaluateJavascript("$viewport window.dispatchEvent(new CustomEvent('ziphap:snapshot',{detail:JSON.parse($quoted)}));",null)
    }
    AndroidView(modifier=modifier,factory={context->
        WebView(context).apply{
            // A WRAP_CONTENT WebView can report a zero CSS viewport inside AndroidView even
            // when Compose gives its native view a full-height frame.
            layoutParams=android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(android.graphics.Color.rgb(248,247,243))
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
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean=
                    request.url.scheme!="https"||request.url.host!="appassets.androidplatform.net"||!request.url.path.orEmpty().startsWith("/assets/react/")
                override fun onPageFinished(view:WebView,url:String){
                    if(url=="https://appassets.androidplatform.net/assets/react/index.html")publish(view)
                }
                override fun onReceivedError(view:WebView,request:WebResourceRequest,error:android.webkit.WebResourceError){if(request.isForMainFrame)unavailable()}
            }
            if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)){
                post{unavailable()}
            }else{
                WebViewCompat.addWebMessageListener(this,"ziphapNative",setOf("https://appassets.androidplatform.net")){view,message,origin,mainFrame,_->
                    if(mainFrame&&origin==Uri.parse("https://appassets.androidplatform.net")){
                        ReactBridgePolicy.parse(message.data.orEmpty())?.let{value->
                            if(value.action=="ready")publish(view) else command(value)
                        }
                    }
                }
                loadUrl("https://appassets.androidplatform.net/assets/react/index.html")
            }
        }
    },update={view->publish(view)},onRelease={view->view.stopLoading();view.removeAllViews();view.destroy()})
}
