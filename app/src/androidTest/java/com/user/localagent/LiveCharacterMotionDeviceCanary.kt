package com.user.localagent

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil
import kotlin.math.floor

/** Compare pixels only inside the actual character SVG. No full screen, text, saved image or owner setting. */
@RunWith(AndroidJUnit4::class)
class LiveCharacterMotionDeviceCanary {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val header = ".character-button > svg.character"
    private val preview = ".character-preview > svg.character"

    @Test fun actualHeaderAndPreviewProduceDifferentVisibleFrames() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("c090LiveCharacter") == "true")
        val context = instrumentation.targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            val headerChanges = measure(scenario, header)
            check(js(scenario, "document.querySelector('.character-button')?.click(); true") == "true")
            try {
                val previewChanges = measure(scenario, preview)
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("c090LiveMotion", "headerChangedPixels=$headerChanges previewChangedPixels=$previewChanges")
                })
            } finally {
                js(scenario, "document.querySelector('dialog.character-sheet[open] .sheet-close')?.click(); true")
            }
        }
    }

    private suspend fun measure(scenario: ActivityScenario<MainActivity>, selector: String): Int {
        val bounds = withTimeout(25_000) {
            var stable: Rect? = null
            while (stable == null) {
                val candidate = characterBounds(scenario, selector)
                delay(350)
                if (candidate != null && candidate == characterBounds(scenario, selector)) stable = candidate
            }
            stable
        }
        val first = frame(scenario, bounds)
        var changed = 0
        val observed = mutableSetOf(first.contentHashCode())
        try {
            repeat(18) {
                delay(150)
                check(characterBounds(scenario, selector) == bounds) { "Character moved outside its checked crop" }
                val next = frame(scenario, bounds)
                try {
                    val different = first.indices.count { first[it] != next[it] }
                    if (different > 2) observed.add(next.contentHashCode())
                    changed = maxOf(changed, different)
                } finally {
                    next.fill(0)
                }
            }
        } finally {
            first.fill(0)
        }
        assertTrue("Fewer than three distinct rendered character frames", observed.size >= 3)
        return changed
    }

    private suspend fun characterBounds(scenario: ActivityScenario<MainActivity>, selector: String): Rect? {
        val geometry = js(scenario, """
            (() => {
              const el = document.querySelector('$selector');
              if (!el || el.dataset.animated !== 'true' || el.dataset.paused === 'true') return null;
              if (!el.closest('button') || matchMedia('(prefers-reduced-motion: reduce)').matches) return null;
              const box = el.getBoundingClientRect(), viewport = window.visualViewport;
              if (!viewport || viewport.scale !== 1 || viewport.offsetLeft !== 0 || viewport.offsetTop !== 0 ||
                  box.width < 20 || box.width > 180 || Math.abs(box.width-box.height) > 2 ||
                  box.left < 0 || box.top < 0 || box.right > innerWidth || box.bottom > innerHeight) return null;
              const target = document.elementFromPoint(box.left + box.width/2, box.top + box.height/2);
              if (!target || !el.contains(target)) return null;
              return [box.left+box.width*0.12,box.top+box.height*0.12,box.right-box.width*0.12,box.bottom-box.height*0.12,innerWidth];
            })()
        """.trimIndent())
        if (geometry == "null") return null
        val dimensions = JSONArray(geometry)
        var rect: Rect? = null
        scenario.onActivity { activity ->
            val web = chat(activity.window.decorView) ?: return@onActivity
            if (!web.hasWindowFocus() || !web.isShown) return@onActivity
            val coordinates = IntArray(2)
            web.getLocationInWindow(coordinates)
            val scale = web.width / dimensions.getDouble(4)
            check(scale in 0.5..6.0)
            val crop = Rect(ceil(dimensions.getDouble(0) * scale).toInt(), ceil(dimensions.getDouble(1) * scale).toInt(),
                floor(dimensions.getDouble(2) * scale).toInt(), floor(dimensions.getDouble(3) * scale).toInt())
            check(!crop.isEmpty && Rect(0, 0, web.width, web.height).contains(crop))
            crop.offset(coordinates[0], coordinates[1])
            rect = crop
        }
        return rect
    }

    private suspend fun frame(scenario: ActivityScenario<MainActivity>, bounds: Rect): IntArray {
        val result = CompletableDeferred<Int>()
        val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        try {
            scenario.onActivity { activity ->
                check(activity.hasWindowFocus())
                check(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
                PixelCopy.request(activity.window, Rect(bounds), bitmap, { result.complete(it) }, Handler(Looper.getMainLooper()))
            }
            assertEquals(PixelCopy.SUCCESS, withTimeout(5_000) { result.await() })
            return IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun js(scenario: ActivityScenario<MainActivity>, code: String): String {
        val result = CompletableDeferred<String>()
        scenario.onActivity { activity ->
            val web = chat(activity.window.decorView)
            if (web == null) result.complete("null") else web.evaluateJavascript(code) { result.complete(it ?: "null") }
        }
        return withTimeout(5_000) { result.await() }
    }

    private fun chat(view: View): WebView? {
        if (view is WebView && view.isShown && view.url == "https://appassets.androidplatform.net/assets/react/index.html") return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            chat(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
