package com.user.localagent

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Renders the installed chat assets on a private display with an isolated origin and one synthetic snapshot. */
@RunWith(AndroidJUnit4::class)
class CharacterWebViewDeviceCanary {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val origin = "https://c090-fixture.invalid"
    private lateinit var reader: ImageReader
    private lateinit var display: VirtualDisplay
    private lateinit var presentation: Presentation
    private lateinit var web: WebView

    @Test fun installedAssetsRenderFontsStickersAndMovingCharacter() {
        val context = instrumentation.targetContext
        val ready = CountDownLatch(1)
        try {
            instrumentation.runOnMainSync {
                reader = ImageReader.newInstance(840, 1440, PixelFormat.RGBA_8888, 2)
                reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                display = checkNotNull(dm.createVirtualDisplay("C090 synthetic render", 840, 1440, 320, reader.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION))
                presentation = Presentation(context, display.display)
                web = WebView(presentation.context)
                web.settings.javaScriptEnabled = true
                web.settings.allowFileAccess = false
                web.settings.allowContentAccess = false
                web.settings.blockNetworkLoads = true
                val assets = WebViewAssetLoader.Builder().setDomain("c090-fixture.invalid")
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                        assets.shouldInterceptRequest(request.url)
                            ?: WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                }
                WebViewCompat.addWebMessageListener(web, "ziphapNative", setOf(origin)) { _, message, _, mainFrame, _ ->
                    if (mainFrame && org.json.JSONObject(message.data.orEmpty()).optString("action") == "ready") ready.countDown()
                }
                presentation.setContentView(web)
                presentation.show()
                web.loadUrl("$origin/assets/react/index.html")
            }
            assertTrue("Installed React bundle did not initialize", ready.await(20, TimeUnit.SECONDS))
            val snapshot = """
                ({threadId:'fixture', branchId:'branch', ready:true, draft:'', busy:true,
                  status:'', modelId:'auto', modelLabel:'점검', composerEpoch:0, activeRunId:'run', actionEpoch:1,
                  singleConversation:true, character:{variant:'bear',color:'butter',expr:'basic'},
                  messages:[{id:'message',role:'user',text:'합성 확인 ✅'}], tasks:[],
                  threads:[{id:'fixture',title:'합성 대화',selected:true}],models:[{id:'auto',label:'점검'}]})
            """.trimIndent()
            evaluate(web, "window.dispatchEvent(new CustomEvent('ziphap:snapshot',{detail:$snapshot}))")
            awaitFact(web, "document.querySelector('.character-button[data-mood=thinking]') != null")
            awaitFact(web, """
                (() => {
                  const sticker = document.querySelector('[data-sticker=check]');
                  return !!sticker?.querySelector('svg') && sticker.getBoundingClientRect().width > 0
                    && sticker.querySelector('.sticker-source')?.textContent === '✅';
                })()
            """.trimIndent())
            evaluate(web, """
                Promise.all([document.fonts.load('16px "SUIT Variable"', '집순이'),document.fonts.load('16px Jua','집순이')])
                  .then(arrays => { window.fixtureFonts = arrays.every(rows => rows.length && rows.every(f => f.status === 'loaded')); });
                true
            """.trimIndent())
            awaitFact(web, "window.fixtureFonts === true && getComputedStyle(document.body).fontFamily.includes('SUIT')")
            evaluate(web, "document.querySelector('.character-button').click()")
            awaitFact(web, "document.querySelector('.character-preview .character--full-motion')?.getBoundingClientRect().width > 100")
            assertEquals("Device prefers reduced motion", "false", evaluate(web, "matchMedia('(prefers-reduced-motion: reduce)').matches"))
            awaitFact(web, "document.querySelector('.character-preview svg')?.dataset.paused === 'false'")
            evaluate(web, """
                (() => {
                  const figure = document.querySelector('.character-preview [data-layer=figure]');
                  window.fixtureTransforms = new Set();
                  function frame() {
                    window.fixtureTransforms.add(getComputedStyle(figure).transform);
                    if (window.fixtureTransforms.size < 3) requestAnimationFrame(frame);
                  }
                  requestAnimationFrame(frame);
                })()
            """.trimIndent())
            awaitFact(web, "window.fixtureTransforms?.size >= 3")
        } finally {
            instrumentation.runOnMainSync {
                if (::web.isInitialized) web.stopLoading()
                if (::presentation.isInitialized) presentation.dismiss()
                if (::web.isInitialized) web.destroy()
                if (::display.isInitialized) display.release()
                if (::reader.isInitialized) reader.close()
            }
        }
    }

    private fun awaitFact(web: WebView, predicate: String) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 12_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (evaluate(web, predicate) == "true") return
            Thread.sleep(100)
        }
        fail("Missing synthetic rendering observation: $predicate")
    }

    private fun evaluate(web: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it.orEmpty(); latch.countDown() } }
        assertTrue("JS callback timed out", latch.await(5, TimeUnit.SECONDS))
        return result
    }
}
