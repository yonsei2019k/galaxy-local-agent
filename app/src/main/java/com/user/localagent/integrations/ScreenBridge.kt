package com.user.localagent.integrations

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.user.localagent.R
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Package-bound metadata adapters, not validated third-party application workflows. */
object ScreenAdapters {
    val packages: Set<String> = setOf("com.tionsoft.meettalk.eun", "com.meditong.gw", "com.kakao.talk",
        "org.telegram.messenger", "org.telegram.messenger.web", "com.android.chrome")
    internal val clickClasses = setOf("android.widget.Button", "android.widget.ImageButton", "android.widget.TextView",
        "android.widget.ImageView", "androidx.appcompat.widget.AppCompatButton", "androidx.appcompat.widget.AppCompatImageButton",
        "androidx.appcompat.widget.AppCompatTextView")
    internal val editClasses = setOf("android.widget.EditText", "androidx.appcompat.widget.AppCompatEditText",
        "com.google.android.material.textfield.TextInputEditText")
    internal val forbidden = listOf("password", "passwd", "credential", "keyguard", "permission", "authorize", "oauth",
        "biometric", "fingerprint", "certificate", "install", "uninstall", "device_admin", "security", "dialog", "popup",
        "two_factor", "2fa", "otp", "pin_code", "pincode", "login", "sign_in", "signin", "unlock", "consent", "payment", "purchase")
}

data class ScreenBridgeState(val systemEnabled: Boolean, val connected: Boolean, val allowedPackages: Set<String>)
data class ScreenNodeSchema(val viewId: String, val className: String, val canClick: Boolean, val canSetText: Boolean)
data class ScreenSchema(val packageName: String, val installedVersion: Long, val snapshotVersion: Long,
    val nodes: List<ScreenNodeSchema>, val truncated: Boolean)
enum class ScreenAction { SET_TEXT, CLICK }
data class ScreenActionRequest(val packageName: String, val installedVersion: Long, val viewId: String,
    val snapshotVersion: Long, val action: ScreenAction, val value: String? = null)
data class PreparedScreenAction(val id: String, val request: ScreenActionRequest, val expiresAtElapsedMillis: Long)
data class ScreenActionResult(val performed: Boolean, val message: String)

class ScreenBridgeController(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("screen_bridge_v1", Context.MODE_PRIVATE)
    fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    fun setAllowed(packageName: String, allowed: Boolean) {
        require(packageName in ScreenAdapters.packages) { "Unsupported package" }
        synchronized(CONSENT_LOCK) { prefs.edit().putBoolean(packageName, allowed).putLong("consent_revision", consentRevision() + 1).apply() }
        // Revocation is checked again on every operation even if no service callback occurs.
    }
    fun state(): ScreenBridgeState {
        val enabled = context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                val service = it.resolveInfo.serviceInfo
                service.packageName == context.packageName && service.name == ScreenBridgeService::class.java.name
            }
        return ScreenBridgeState(enabled, ScreenBridgeService.current() != null,
            ScreenAdapters.packages.filter { prefs.getBoolean(it, false) }.toSet())
    }
    internal fun consentRevision() = prefs.getLong("consent_revision", 0)
    internal fun allowed(packageName: String) = packageName in ScreenAdapters.packages && prefs.getBoolean(packageName, false)
    fun inspectVisibleSchema(): ScreenSchema = connected().inspectVisibleSchema()
    fun prepare(request: ScreenActionRequest): PreparedScreenAction = connected().prepare(request)
    /** Only call after the user has reviewed this exact prepared request in the confirmation UI. */
    fun approve(preparedId: String): String = connected().approve(preparedId)
    fun execute(preparedId: String, approvalToken: String): ScreenActionResult = connected().execute(preparedId, approvalToken)
    fun revokePending() { ScreenBridgeService.current()?.revokePending() }
    /** User-triggered receiver calls this without launching an Activity or changing target focus. */
    fun showInspector() = connected().showInspector()
    fun closeInspector() { ScreenBridgeService.current()?.closeInspector() }
    /** User explicitly enables this notification as an entry point while another app is foreground. */
    fun postInspectorNotification(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("screen-inspector", "Optional screen schema inspector", NotificationManager.IMPORTANCE_LOW))
        if (manager.getNotificationChannel("screen-inspector")?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val inspect = PendingIntent.getBroadcast(context, 8420, Intent(context, ScreenInspectorReceiver::class.java)
            .setAction(ScreenInspectorReceiver.ACTION_INSPECT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try { manager.notify("screen-inspector-entry", 0, NotificationCompat.Builder(context, "screen-inspector")
            .setSmallIcon(R.drawable.ic_agent).setContentTitle("선택한 앱 화면 검사")
            .setContentText("메타데이터만 확인 · 동작마다 직접 승인")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOngoing(true).setContentIntent(inspect)
            .addAction(0, "현재 앱 화면 검사", inspect).build()) } catch (_: SecurityException) { return false }
        return true
    }
    fun cancelInspectorNotification() { context.getSystemService(NotificationManager::class.java).cancel("screen-inspector-entry", 0) }
    private fun connected() = ScreenBridgeService.current() ?: error("화면 연결 서비스가 비활성화되어 있습니다.")
    companion object { private val CONSENT_LOCK = Any() }
}

/** Never reads node text/contentDescription or event payloads. No logging, networking, gestures, or global actions. */
class ScreenBridgeService : AccessibilityService() {
    private val version = AtomicLong(1)
    private var schema: ScreenSchema? = null
    private var prepared: PreparedScreenAction? = null
    private var approvalHash: ByteArray? = null
    private var preparedConsentRevision: Long = -1
    private val controller by lazy { ScreenBridgeController(this) }
    private var inspector: LinearLayout? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    override fun onServiceConnected() { active = WeakReference(this); invalidate() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Ignore only our own inspector's metadata events; never read text or content descriptions.
        if (event?.packageName?.toString() != packageName) invalidate()
    }
    override fun onInterrupt() { closeInspector(); invalidate() }
    override fun onDestroy() { mainHandler.removeCallbacksAndMessages(null); closeInspector(); invalidate(); if (active.get() === this) active.clear(); super.onDestroy() }
    fun revokePending() { requireMainThread(); prepared = null; approvalHash = null }
    private fun invalidate() { version.incrementAndGet(); schema = null; prepared = null; approvalHash = null }
    private fun requireMainThread() { check(Looper.myLooper() == Looper.getMainLooper()) { "Screen bridge must be called on the main thread" } }
    private fun activeRoot(packageName: String? = null): AccessibilityNodeInfo {
        requireMainThread()
        check(controller.state().systemEnabled) { "System accessibility permission is no longer enabled" }
        val users = getSystemService(UserManager::class.java)
        check(users.isUserUnlocked) { "The device must be unlocked" }
        var root = rootInActiveWindow ?: error("활성 앱 화면을 찾을 수 없습니다.")
        var window = root.window ?: error("Unknown foreground window")
        // A nonfocusable inspector may be touch-active while the target retains input focus.
        // Never substitute an app window for a foreign/system overlay or another focused app.
        if (inspector != null && window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && root.packageName?.toString() == this.packageName) {
            val focused = windows.filter { it.displayId == Display.DEFAULT_DISPLAY && it.isFocused }
            val target = focused.singleOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?: error("No unambiguous focused target application")
            check(focused.size == 1) { "Another window has input focus" }
            root = target.root ?: error("Focused target root unavailable")
            window = target
        }
        val actual = root.packageName?.toString() ?: error("Unknown foreground package")
        check(controller.allowed(actual) && (packageName == null || actual == packageName)) { "Foreground package is not enabled or has changed" }
        check(window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused && window.displayId == Display.DEFAULT_DISPLAY) { "Only the foreground focused application window is supported" }
        check(window.isActive || ownOverlayIsActive()) { "Target window is not active" }
        return root
    }
    private fun ownOverlayIsActive(): Boolean = inspector != null && windows.any {
        it.isActive && it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.root?.packageName?.toString() == packageName
    }
    private fun installedVersion(packageName: String): Long = packageManager.getPackageInfo(packageName, 0).longVersionCode
    fun inspectVisibleSchema(): ScreenSchema {
        val root = activeRoot()
        val packageName = root.packageName.toString()
        val snapshot = version.get()
        val nodes = mutableListOf<ScreenNodeSchema>()
        var visited = 0
        var truncated = false
        fun visit(node: AccessibilityNodeInfo, depth: Int, blockedAncestor: Boolean) {
            if (visited >= 256 || depth > 16) { truncated = true; return }
            visited++
            if (node.packageName?.toString() != packageName) return
            val blocked = blockedAncestor || sensitive(node)
            val id = node.viewIdResourceName
            val cls = node.className?.toString().orEmpty()
            if (!blocked && node.isVisibleToUser && node.isEnabled && validId(packageName, id)) {
                val actions = node.actionList.map { it.id }.toSet()
                nodes += ScreenNodeSchema(id!!, cls.take(160), node.isClickable && cls in ScreenAdapters.clickClasses && AccessibilityNodeInfo.ACTION_CLICK in actions,
                    node.isEditable && cls in ScreenAdapters.editClasses && AccessibilityNodeInfo.ACTION_SET_TEXT in actions)
            }
            val childCount = node.childCount
            if (childCount > 64) truncated = true
            for (index in 0 until childCount.coerceAtMost(64)) node.getChild(index)?.let { visit(it, depth + 1, blocked) }
        }
        visit(root, 0, false)
        check(snapshot == version.get()) { "화면이 변경되었습니다. 다시 확인해주세요." }
        val duplicates = nodes.groupingBy { it.viewId }.eachCount().filterValues { it > 1 }.keys
        val safe = nodes.map { if (it.viewId in duplicates || truncated) it.copy(canClick = false, canSetText = false) else it }
        return ScreenSchema(packageName, installedVersion(packageName), snapshot, safe, truncated).also { schema = it; prepared = null; approvalHash = null }
    }
    fun prepare(request: ScreenActionRequest): PreparedScreenAction {
        val root = activeRoot(request.packageName)
        val current = schema ?: error("먼저 현재 화면 스키마를 확인해주세요.")
        check(!current.truncated && request.snapshotVersion == version.get() && current.snapshotVersion == request.snapshotVersion &&
            current.packageName == request.packageName && current.installedVersion == request.installedVersion && installedVersion(request.packageName) == request.installedVersion) { "Snapshot or installed app version has changed" }
        val metadata = current.nodes.singleOrNull { it.viewId == request.viewId } ?: error("Unknown or ambiguous view ID")
        when (request.action) {
            ScreenAction.SET_TEXT -> require(metadata.canSetText && request.value != null && request.value.length in 1..2000)
            ScreenAction.CLICK -> require(metadata.canClick && request.value == null)
        }
        checkNode(root, request)
        return PreparedScreenAction(UUID.randomUUID().toString(), request, SystemClock.elapsedRealtime() + 30_000).also { prepared = it; approvalHash = null; preparedConsentRevision = controller.consentRevision() }
    }
    fun approve(preparedId: String): String {
        requireMainThread()
        val pending = prepared ?: error("No prepared action")
        check(pending.id == preparedId && pending.expiresAtElapsedMillis >= SystemClock.elapsedRealtime() && pending.request.snapshotVersion == version.get()) { "Prepared action expired or changed" }
        check(preparedConsentRevision == controller.consentRevision()) { "Package consent changed" }
        activeRoot(pending.request.packageName)
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
        approvalHash = digest(token)
        return token
    }
    fun execute(preparedId: String, approvalToken: String): ScreenActionResult {
        requireMainThread()
        val pending = prepared
        val expected = approvalHash
        // Consume approval before every check; failures never leave a replayable token.
        prepared = null; approvalHash = null
        if (pending == null || expected == null || approvalToken.length > 100 || pending.id != preparedId || !MessageDigest.isEqual(expected, digest(approvalToken)))
            return ScreenActionResult(false, "유효한 일회용 승인이 필요합니다.")
        return try {
            check(pending.expiresAtElapsedMillis >= SystemClock.elapsedRealtime() && preparedConsentRevision == controller.consentRevision()) { "Approval expired or consent changed" }
            val request = pending.request
            check(request.snapshotVersion == version.get() && installedVersion(request.packageName) == request.installedVersion) { "Screen or app version changed" }
            val root = activeRoot(request.packageName)
            val node = checkNode(root, request)
            val performed = when (request.action) {
                ScreenAction.CLICK -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ScreenAction.SET_TEXT -> node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, request.value)
                })
            }
            invalidate()
            ScreenActionResult(performed, if (performed) "선택한 화면 동작을 요청했습니다. 앱에서 결과를 확인해주세요." else "앱이 해당 동작을 지원하지 않습니다.")
        } catch (_: Exception) { ScreenActionResult(false, "화면·앱 버전·권한이 변경되었거나 지원하지 않는 요소입니다. 다시 확인해주세요.") }
    }
    /** Nonfocusable accessibility overlay; only explicit user clicks prepare/approve/execute. */
    fun showInspector() {
        requireMainThread()
        closeInspector()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(android.graphics.Color.rgb(22, 29, 42))
        }
        inspector = panel
        val bounds = resources.displayMetrics
        val width = minOf(dp(360), bounds.widthPixels - dp(24)).coerceAtLeast(dp(200))
        val height = minOf(dp(440), bounds.heightPixels - dp(80)).coerceAtLeast(dp(240))
        val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = dp(8); y = dp(64) }
        try { getSystemService(WindowManager::class.java).addView(panel, params) }
        catch (error: RuntimeException) { inspector = null; throw error }
        showInspectorMessage("알림창이 닫힌 뒤 현재 앱의 요소 ID만 확인합니다.")
        // Inspect only; this never prepares, approves, or executes an action.
        mainHandler.postDelayed({ if (inspector === panel) refreshInspector() }, 350)
    }
    fun closeInspector() {
        requireMainThread()
        inspector?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        inspector = null
        prepared = null; approvalHash = null
    }
    private fun refreshInspector() {
        val panel = inspector ?: return
        val captured = runCatching { inspectVisibleSchema() }.getOrNull()
        if (captured == null) {
            showInspectorMessage("허용한 앱을 앞에 두고 알림창을 닫은 다음 새로고침해주세요.")
            return
        }
        panel.removeAllViews()
        val name = runCatching { packageManager.getApplicationInfo(captured.packageName, 0).loadLabel(packageManager).toString().take(80) }.getOrDefault(captured.packageName)
        panel.addView(label("$name · v${captured.installedVersion}\n${captured.packageName}", 15f))
        panel.addView(label("스냅샷 ${captured.snapshotVersion} · 텍스트 수집 없음" + if (captured.truncated) " · 항목이 많아 실행 불가" else "", 11f))
        val scroll = ScrollView(this)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        captured.nodes.forEach { metadata ->
            rows.addView(label("${metadata.viewId}\n${metadata.className}", 11f))
            rows.addView(Button(this).apply {
                text = if (metadata.canClick) "이 ID 클릭 준비" else "클릭 미지원"
                isEnabled = metadata.canClick
                setOnClickListener {
                    val request = ScreenActionRequest(captured.packageName, captured.installedVersion, metadata.viewId,
                        captured.snapshotVersion, ScreenAction.CLICK)
                    val action = runCatching { prepare(request) }.getOrNull()
                    if (action == null) showInspectorMessage("화면이 변경되었거나 지원하지 않는 요소입니다. 다시 검사해주세요.")
                    else showClickConfirmation(action)
                }
            })
        }
        if (captured.nodes.isEmpty()) rows.addView(label("현재 화면에 지원하는 요소 ID가 없습니다.", 13f))
        scroll.addView(rows)
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addInspectorControls(panel)
    }
    private fun showClickConfirmation(action: PreparedScreenAction) {
        val panel = inspector ?: return
        panel.removeAllViews()
        panel.addView(label("한 번의 클릭 승인", 17f))
        panel.addView(label("앱: ${action.request.packageName}\n버전: ${action.request.installedVersion}\nID: ${action.request.viewId}\n동작: CLICK", 13f),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        panel.addView(label("대상 화면과 결과를 직접 확인하세요. 클릭은 전송·삭제·다운로드일 수 있습니다. 30초 후 만료됩니다.", 12f))
        panel.addView(Button(this).apply {
            text = "이 ID 한 번 클릭 승인"
            setOnClickListener {
                isEnabled = false
                val result = runCatching { val token = approve(action.id); execute(action.id, token) }.getOrNull()
                showInspectorMessage(result?.message ?: "승인이 만료되었거나 화면이 변경되었습니다. 다시 검사해주세요.")
            }
        })
        panel.addView(Button(this).apply { text = "취소하고 다시 검사"; setOnClickListener { revokePending(); refreshInspector() } })
        panel.addView(Button(this).apply { text = "닫기"; setOnClickListener { closeInspector() } })
    }
    private fun showInspectorMessage(message: String) {
        val panel = inspector ?: return
        panel.removeAllViews()
        panel.addView(label("화면 요소 검사", 17f))
        panel.addView(label(message, 13f), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addInspectorControls(panel)
    }
    private fun addInspectorControls(panel: LinearLayout) {
        panel.addView(Button(this).apply { text = "현재 화면 새로고침"; setOnClickListener { revokePending(); refreshInspector() } })
        panel.addView(Button(this).apply { text = "닫기"; setOnClickListener { closeInspector() } })
    }
    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(android.graphics.Color.WHITE); setPadding(0, dp(3), 0, dp(3))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun checkNode(root: AccessibilityNodeInfo, request: ScreenActionRequest): AccessibilityNodeInfo {
        require(validId(request.packageName, request.viewId))
        val matches = root.findAccessibilityNodeInfosByViewId(request.viewId)
        check(matches.size == 1) { "Unknown or ambiguous node" }
        val node = matches.single()
        check(node.refresh() && node.packageName?.toString() == request.packageName && node.isVisibleToUser && node.isEnabled) { "Node is stale or disabled" }
        var ancestor: AccessibilityNodeInfo? = node
        var depth = 0
        while (ancestor != null && depth++ < 24) { check(!sensitive(ancestor)) { "Security or dialog controls are unsupported" }; ancestor = ancestor.parent }
        check(ancestor == null) { "Unknown node ancestry" }
        val cls = node.className?.toString().orEmpty()
        val capabilities = node.actionList.map { it.id }.toSet()
        when (request.action) {
            ScreenAction.CLICK -> check(node.isClickable && cls in ScreenAdapters.clickClasses && AccessibilityNodeInfo.ACTION_CLICK in capabilities)
            ScreenAction.SET_TEXT -> check(node.isEditable && cls in ScreenAdapters.editClasses && AccessibilityNodeInfo.ACTION_SET_TEXT in capabilities && request.value != null && request.value.length in 1..2000)
        }
        return node
    }
    private fun sensitive(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        val inputClass = node.inputType and InputType.TYPE_MASK_CLASS
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        if ((inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)) return true
        val metadata = (node.viewIdResourceName.orEmpty() + " " + node.className?.toString().orEmpty()).lowercase(java.util.Locale.ROOT)
        return ScreenAdapters.forbidden.any { it in metadata }
    }
    private fun validId(packageName: String, viewId: String?) = viewId != null && viewId.length <= 220 &&
        viewId.startsWith("$packageName:id/") && viewId.substringAfter(":id/").matches(Regex("[A-Za-z0-9_]+")) &&
        ScreenAdapters.forbidden.none { it in viewId.lowercase(java.util.Locale.ROOT) }
    private fun digest(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    companion object {
        @Volatile private var active = WeakReference<ScreenBridgeService>(null)
        internal fun current(): ScreenBridgeService? = active.get()
    }
}





