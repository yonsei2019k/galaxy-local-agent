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
    private val replies = java.util.concurrent.atomic.AtomicInteger()
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
                    if (mainFrame) {
                        val command = org.json.JSONObject(message.data.orEmpty())
                        when (command.optString("action")) {
                            "ready" -> ready.countDown()
                            "chooseReply" -> if (command.getJSONObject("payload").getString("runId") == "question" &&
                                command.getJSONObject("payload").getInt("index") == 0) replies.incrementAndGet()
                        }
                    }
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
            checkDesignStates(snapshot)
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
            saveDisplay("character")
            evaluate(web, """(() => {
                const intervals = []; let previous = 0;
                function sample(t) {
                    if (previous) intervals.push(t - previous);
                    previous = t;
                    if (intervals.length < 90) requestAnimationFrame(sample);
                    else { intervals.sort((a,b) => a-b); window.frameP95 = intervals[85]; }
                }
                requestAnimationFrame(sample);
            })()""")
            awaitFact(web, "window.frameP95 > 0")
            android.util.Log.i("ZipDesignDevice", "90 frame intervals, p95 ms=" + evaluate(web, "window.frameP95"))
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

    private fun checkDesignStates(snapshot: String) {
        evaluate(web, """window.updateFixture = patch => window.dispatchEvent(new CustomEvent('ziphap:snapshot',
            {detail:{...$snapshot,busy:false,activeRunId:null,...patch}})); true""")
        fun show(mood: String, patch: String) {
            evaluate(web, "window.updateFixture($patch)")
            awaitFact(web, "document.querySelector('.character-button[data-mood=$mood] svg[data-situation=$mood]') != null")
        }
        fun task(phase: String) = "{tasks:[{id:'work',phase:'$phase',label:'확인',detail:'',canCancel:false}]}"
        show("idle", "{}")
        show("greeting", "{messages:[]}")
        show("listening", "{draft:'질문'}")
        show("thinking", "{busy:true}")
        show("working", task("running"))
        show("done", task("completed"))
        show("waiting", task("waiting_goal"))
        show("blocked", task("paused"))
        show("confirm", """{messages:[{id:'q',role:'assistant',text:'이 일정으로 할까요?'}],
            tasks:[{id:'question',phase:'waiting_input',label:'',detail:'일정을 선택해 주세요.',canCancel:false,
              choices:[{label:'이 일정으로 할게요',recommended:true},{label:'다른 일정 볼래요',recommended:false}]}]}""")
        saveDisplay("choice")
        evaluate(web, """(() => { const chip=document.querySelector('.reply-chip'); chip.click(); chip.click(); })()""")
        awaitFact(web, "document.querySelector('.reply-chip').disabled")
        instrumentation.waitForIdleSync()
        assertEquals(1, replies.get())
        evaluate(web, """window.updateFixture({bookingPayment:{id:'p1',seller:'예시 숙소',amount:150000,state:'ready'}})""")
        awaitFact(web, "document.querySelector('svg[data-metal=lock]')?.getBoundingClientRect().height > 0")
        saveDisplay("lock")
        show("thinking", "{busy:true}")
    }

    private fun saveDisplay(name: String) {
        val result = CountDownLatch(1)
        val bitmap = android.graphics.Bitmap.createBitmap(840, 1440, android.graphics.Bitmap.Config.ARGB_8888)
        var code = -1
        android.view.PixelCopy.request(checkNotNull(presentation.window), bitmap, { code = it; result.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue(result.await(5, TimeUnit.SECONDS))
        assertEquals(android.view.PixelCopy.SUCCESS, code)
        val dir = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "design-3d-qa")
        dir.mkdirs()
        java.io.File(dir, "$name.png").outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
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
