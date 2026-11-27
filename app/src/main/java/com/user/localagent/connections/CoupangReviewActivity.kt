package com.user.localagent.connections

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import com.user.localagent.core.connections.CoupangReviewEvidence as Evidence
import org.json.JSONObject
import kotlin.math.roundToInt

/** The owner's Coupang session and observed product records stay in the app on this phone. */
class CoupangReviewActivity : ComponentActivity() {
    private data class Observation(val title: String)
    private val orderRows = linkedMapOf<Evidence.Variant, Observation>()
    private val reviewRows = linkedMapOf<Evidence.Variant, Observation>()
    private var pageRevision = 0
    private var capturePending = false
    private var scan: Job? = null
    private var chooserTarget: Pair<String, Int>? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var results: LinearLayout

    private val choosePhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val callback = fileCallback
        fileCallback = null
        val current = if (::web.isInitialized) web.url else null
        val samePage = current != null && chooserTarget == (current to pageRevision) && reviewUploadPage(current)
        chooserTarget = null
        callback?.onReceiveValue(uri?.takeIf {
            samePage && it.scheme == "content" && runCatching {
                contentResolver.getType(it)?.startsWith("image/") == true
            }.getOrDefault(false)
        }?.let { arrayOf(it) })
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (10 * resources.displayMetrics.density).roundToInt()
            setPadding(padding, padding, padding, padding)
        }
        setContentView(root)
        fun text(value: String) = TextView(this).apply {
            this.text = value
            root.addView(this)
        }

        text("쿠팡 구매후기").apply {
            textSize = 22f
            typeface = resources.getFont(com.user.localagent.runtime.R.font.jua_regular)
        }
        text("집합 앱 안에서 직접 로그인하세요. 조회한 상품 정보는 이 화면에만 표시됩니다.")
        val navigation = LinearLayout(this).also(root::addView)
        button(navigation, "주문목록") { open(Evidence.ORDERS) }
        button(navigation, "미작성 후기") { open(Evidence.REVIEWABLE) }
        button(navigation, "뒤로") { scan?.cancel(); if (web.canGoBack()) web.goBack() }

        val actions = LinearLayout(this).also(root::addView)
        button(actions, "현재 목록 확인") { capture() }
        button(actions, "대조 결과") { showResults() }
        val scanActions = LinearLayout(this).also(root::addView)
        button(scanActions, "목록 이어서 확인") { startScan() }
        button(scanActions, "확인 중지") {
            scan?.cancel()
            status.text = "확인을 중지했습니다. 지금까지 확인한 상품을 볼 수 있습니다."
        }
        status = text("주문목록과 미작성 후기 화면에서 각각 현재 목록을 확인하세요.")

        web = WebView(this).apply {
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame && !Evidence.navigationAllowed(request.url.toString())

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    pageRevision++
                    if (url == null || !Evidence.navigationAllowed(url)) {
                        view.stopLoading()
                    } else if (Uri.parse(url).host == "login.coupang.com") {
                        orderRows.clear()
                        reviewRows.clear()
                        status.text = "쿠팡에 직접 로그인한 뒤 주문과 리뷰 목록을 다시 확인해 주세요."
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams,
                ): Boolean {
                    val current = webView.url
                    val explicitTypes = params.acceptTypes.filter(String::isNotBlank)
                    if (current == null || !reviewUploadPage(current) || params.mode != FileChooserParams.MODE_OPEN ||
                        explicitTypes.isNotEmpty() && explicitTypes.none { it.startsWith("image/") || it in IMAGE_EXTENSIONS }
                    ) {
                        callback.onReceiveValue(null)
                        return true
                    }
                    if (fileCallback != null) {
                        callback.onReceiveValue(null)
                        return true
                    }
                    chooserTarget = current to pageRevision
                    fileCallback = callback
                    choosePhoto.launch("image/*")
                    return true
                }
            }
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        web.loadUrl(Evidence.ORDERS)
    }

    private fun reviewUploadPage(raw: String): Boolean {
        if (!Evidence.navigationAllowed(raw)) return false
        val uri = Uri.parse(raw)
        return uri.host == "my.coupang.com" && uri.path?.startsWith("/productreview/") == true &&
            Evidence.page(raw) != Evidence.Page.REVIEWABLE
    }

    private fun capture() {
        if (capturePending || scan?.isActive == true) return
        val current = web.url.orEmpty()
        val page = Evidence.page(current)
        if (page == null) {
            status.text = "공식 주문목록 또는 미작성 후기 목록을 열어 주세요. 로그인과 인증은 화면에서 직접 진행합니다."
            return
        }
        val revision = pageRevision
        capturePending = true
        web.evaluateJavascript(CAPTURE_JS) { raw ->
            capturePending = false
            if (isFinishing || isDestroyed || revision != pageRevision || current != web.url) return@evaluateJavascript
            val added = runCatching { recordPage(raw, current, page) }.getOrNull()
            status.text = added ?: "목록을 읽지 못했습니다. 화면이 열린 뒤 다시 확인해 주세요."
        }
    }

    private fun open(url: String) {
        scan?.cancel()
        web.loadUrl(url)
    }

    private suspend fun evaluate(script: String): String? = suspendCancellableCoroutine { pending ->
        web.evaluateJavascript(script) { value ->
            if (pending.isActive) pending.resume(value)
        }
    }

    private fun startScan() {
        if (capturePending || scan?.isActive == true) return
        val url = web.url ?: return
        val page = Evidence.page(url)
        if (page == null) {
            status.text = "공식 주문목록 또는 미작성 후기 목록을 먼저 열어 주세요."
            return
        }
        scan = lifecycleScope.launch {
            status.text = "이 화면에서 볼 수 있는 상품을 확인 중입니다."
            val result = withTimeoutOrNull(90_000) { scanListing(url, page) }
            status.text = result ?: "확인 시간에 도달했습니다. 확인한 상품은 대조 결과에 남아 있습니다."
        }
    }

    private suspend fun scanListing(initial: String, page: Evidence.Page): String {
        var expected = initial
        var expectedRevision = pageRevision
        repeat(180) {
            val data = evaluate(CAPTURE_JS)?.takeIf { it.length <= 180_000 }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return "목록을 읽지 못했습니다. 화면을 다시 확인해 주세요."
            if (web.url != expected || data.optString("url") != expected || pageRevision != expectedRevision) {
                return "화면이 바뀌어 확인을 중지했습니다."
            }
            if (data.optBoolean("blocked")) return "쿠팡 화면에서 직접 로그인하거나 인증해 주세요."
            if (!data.optBoolean("ready")) {
                delay(450)
                return@repeat
            }
            status.text = runCatching { recordPage(data, expected, page) }.getOrNull()
                ?: return "페이지의 상품 정보를 확인하지 못했습니다."
            if (data.optBoolean("limited")) return "현재 화면의 확인 한도에 도달했습니다. 다음 위치에서 이어서 확인해 주세요."
            if (!data.optBoolean("bottom")) {
                evaluate("window.scrollBy(0, Math.max(1, Math.floor(innerHeight * .72))); true")
                delay(400)
                return@repeat
            }
            val next = Evidence.nextPage(expected, data.optString("next"))
                ?: return "현재 페이지의 끝까지 확인했습니다. 쿠팡 목록에서 다음 페이지가 있는지 확인해 주세요."
            val previousRevision = pageRevision
            web.loadUrl(next)
            val arrived = withTimeoutOrNull(12_000) {
                while (web.url != next || pageRevision == previousRevision) {
                    if (pageRevision != previousRevision && web.url?.let(Evidence::page) != page) {
                        return@withTimeoutOrNull false
                    }
                    delay(250)
                }
                true
            }
            if (arrived != true) return "다음 페이지를 확인하지 못했습니다. 현재까지 확인한 상품을 표시합니다."
            expected = next
            expectedRevision = pageRevision
            delay(450)
        }
        return "한 번에 확인할 수 있는 범위에 도달했습니다. 현재 위치에서 이어서 확인해 주세요."
    }

    private fun recordPage(raw: String, current: String, page: Evidence.Page): String {
        require(raw.length <= 180_000)
        return recordPage(JSONObject(raw), current, page)
    }

    private fun recordPage(data: JSONObject, current: String, page: Evidence.Page): String {
        require(data.getString("url") == current)
        if (data.getBoolean("blocked")) return "로그인이나 인증이 필요합니다."
        val target = if (page == Evidence.Page.ORDERS) orderRows else reviewRows
        val rows = data.getJSONArray("links")
        if (rows.length() == 0 && data.getInt("uncertain") == 0) {
            return "현재 화면에 대조할 상품이 없습니다. 목록이나 다른 페이지를 확인해 주세요."
        }
        var unresolved = data.getInt("uncertain")
        var limit = data.getBoolean("limited")
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val variant = Evidence.variant(row.getString("href"))
            if (variant == null) {
                unresolved++
                continue
            }
            if (target.size >= MAX_ITEMS && variant !in target) {
                limit = true
                continue
            }
            val title = row.getString("title").trim().take(160)
            target.putIfAbsent(variant, Observation(title))
        }
        val name = if (page == Evidence.Page.ORDERS) "주문" else "미작성 후기"
        return "$name 옵션 누적 ${target.size}개 · 현재 화면에서 식별 불가 링크 ${unresolved}개" +
            if (limit) " · 조회 한도 도달" else " · 목록 끝까지 확인하려면 아래로 이동해 다시 확인하세요."
    }

    private fun showResults() {
        val dialog = android.app.Dialog(this)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val scroll = ScrollView(this)
        results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        scroll.addView(results)
        dialog.setContentView(scroll)
        val matched = reviewRows.keys.intersect(orderRows.keys)
        label("옵션 대조 ${matched.size}개 · 주문만 확인 ${orderRows.size - matched.size}개 · 미작성 목록만 확인 ${reviewRows.size - matched.size}개")
        label("같은 옵션으로 반복 구매한 건은 링크만으로 구별할 수 없습니다. 확인한 옵션의 수를 표시합니다.")
        if (matched.isEmpty()) label("양쪽 목록에서 상품·옵션·판매 항목 식별자가 일치하는 상품이 아직 없습니다.")
        for (variant in matched) {
            val title = reviewRows.getValue(variant).title.ifBlank { orderRows.getValue(variant).title }
            label(title.ifBlank { "옵션이 일치하는 상품" })
            button(results, "공식 상품 사진 확인") {
                dialog.dismiss()
                open(variant.url)
            }
            button(results, "사용 경험으로 후기 준비") { showDraft(title) }
        }
        for (variant in reviewRows.keys - matched) {
            label("주문 대조 대기 · " + reviewRows.getValue(variant).title.ifBlank { "상품명 확인 필요" })
        }
        button(results, "닫기") { dialog.dismiss() }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun showDraft(title: String) {
        val inputs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 0, 24, 0) }
        fun field(hint: String) = EditText(this).apply {
            this.hint = hint
            isSaveEnabled = false
            filters = arrayOf(android.text.InputFilter.LengthFilter(1000))
            inputs.addView(this)
        }
        val use = field("실제 사용 기간과 용도")
        val plus = field("좋았던 점")
        val minus = field("아쉬웠던 점")
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(title.ifBlank { "후기 준비" })
            .setView(ScrollView(this).apply { addView(inputs) })
            .setNegativeButton("닫기", null)
            .setPositiveButton("후기 초안 복사", null)
            .create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val draft = listOf("사용 경험" to use, "좋았던 점" to plus, "아쉬웠던 점" to minus)
                .mapNotNull { (heading, input) ->
                    input.text.toString().trim().takeIf(String::isNotEmpty)?.let { "$heading\n$it" }
                }.joinToString("\n\n")
            if (draft.isBlank()) {
                use.error = "실제 사용 경험을 입력해 주세요."
                return@setOnClickListener
            }
            val clip = ClipData.newPlainText("쿠팡 후기 초안", draft)
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            requireNotNull(getSystemService(ClipboardManager::class.java)).setPrimaryClip(clip)
            status.text = "초안을 복사했습니다. 미작성 후기 목록에서 같은 상품·옵션을 확인하고 직접 붙여넣어 주세요."
            dialog.dismiss()
        }
    }

    private fun label(value: String) {
        results.addView(TextView(this).apply { text = value; setPadding(0, 10, 0, 2) })
    }

    private fun button(parent: LinearLayout, title: String, click: () -> Unit) {
        parent.addView(Button(this).apply {
            text = title
            setOnClickListener { click() }
        })
    }

    override fun onStop() {
        scan?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        if (::web.isInitialized) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val MAX_ITEMS = 400
        private val IMAGE_EXTENSIONS = setOf(".png", ".jpg", ".jpeg", ".webp", ".heic")
        private val CAPTURE_JS = """
            (() => {
              const url = location.href;
              const blocked = Boolean(document.querySelector('input[type=password], input[autocomplete=one-time-code]'));
              const links = [];
              const reviewPage = location.hostname === 'my.coupang.com';
              const product = href => /^https:\/\/(www|m)\.coupang\.com\/vp\/products\/[0-9]+(?:[?#]|$)/.test(href);
              const visible = el => el.getClientRects().length &&
                getComputedStyle(el).visibility === 'visible' && !el.closest('[aria-hidden=true],[hidden]');
              const normalize = value => value.replace(/\s+/g, ' ').trim();
              const reviewAction = el => visible(el) &&
                /^(리뷰|상품평|상품후기|구매후기)\s*작성(하기)?$/.test(normalize(el.innerText || el.getAttribute('aria-label') || ''));
              const rowEvidence = link => {
                for (let card = link.parentElement, depth = 0; card && depth < 6; card = card.parentElement, depth++) {
                  if (['MAIN','BODY','HTML','NAV'].includes(card.tagName)) break;
                  const productLinks = new Set(Array.from(card.querySelectorAll('a[href]'))
                    .filter(a => visible(a) && product(a.href)).map(a => a.href));
                  if (productLinks.size !== 1 || !productLinks.has(link.href)) continue;
                  if (reviewPage) {
                    if (Array.from(card.querySelectorAll('button,a,[role=button]')).filter(reviewAction).length === 1) return true;
                  } else {
                    const text = normalize(card.innerText || '');
                    if (/배송\s*완료|구매\s*확정/.test(text) && !/반품|취소/.test(text.replace(/반품 신청|취소 신청/g, ''))) return true;
                  }
                }
                return false;
              };
              let limited = false;
              let uncertain = 0;
              let visited = 0;
              if (!blocked) for (const link of document.querySelectorAll('a[href]')) {
                if (!product(link.href) || !visible(link)) continue;
                const rect = link.getBoundingClientRect();
                if (rect.bottom <= 0 || rect.top >= innerHeight) continue;
                if (++visited > 120) { limited = true; break; }
                if (!rowEvidence(link)) { uncertain++; continue; }
                const title = link.innerText || link.querySelector('img')?.alt || '';
                links.push({href: link.href.slice(0, 500), title: normalize(title).slice(0, 160)});
              }
              const nextLinks = Array.from(document.querySelectorAll('a[href]'))
                .filter(a => visible(a) && (a.rel === 'next' || /^(다음|다음 페이지)$/.test(normalize(a.innerText || a.getAttribute('aria-label') || ''))))
                .map(a => a.href);
              const next = [...new Set(nextLinks)];
              const bottom = window.scrollY + innerHeight >= document.documentElement.scrollHeight - 5;
              return {url, blocked, limited, uncertain, links, bottom, next: next.length === 1 ? next[0] : '',
                ready: document.readyState === 'complete'};
            })()
        """.trimIndent()
    }
}
