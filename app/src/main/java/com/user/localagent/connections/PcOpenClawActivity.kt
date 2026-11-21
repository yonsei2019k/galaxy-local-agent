package com.user.localagent.connections

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.user.localagent.compute.PcComputeSettings
import com.user.localagent.compute.PcConnection
import com.user.localagent.compute.PcTransport
import com.user.localagent.externalagent.ExternalAgentContract
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** An owner-operated controller for the separate PC runtime. No work is executed here. */
class PcOpenClawActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val transport by lazy { PcTransport(this) }
    private val settings by lazy { PcComputeSettings.get(this) }
    private val history by lazy { getSharedPreferences("pc_openclaw_control_v1", MODE_PRIVATE) }
    private lateinit var request: EditText
    private lateinit var providerPicker: Spinner
    private lateinit var cloudConsent: CheckBox
    private lateinit var status: TextView
    private lateinit var answer: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private var poll: Job? = null
    private var busy = false
    private var jobId: String? = null
    private var pairing: String? = null
    private var terminal = false
    private val providerIds = listOf("codex", "claude")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        jobId = history.getString("job", null)
        pairing = history.getString("pair", null)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        fun label(text: String, size: Float = 16f) = TextView(this).apply {
            this.text = text
            textSize = size
            root.addView(this)
        }
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { action() }
            root.addView(this)
        }
        label("PC 집합 에이전트", 24f)
        label("PC에서 OpenClaw가 지정 문서를 처리합니다. 추론은 선택한 구독의 서버에서 수행됩니다.")
        providerPicker = Spinner(this).apply {
            adapter = ArrayAdapter(this@PcOpenClawActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Codex 구독", "Claude 구독"))
            root.addView(this)
        }
        cloudConsent = CheckBox(this).apply {
            text = "이 요청과 PC의 지정 문서를 선택한 구독 서비스로 보냅니다."
            isSaveEnabled = false
            root.addView(this)
        }
        request = EditText(this).apply {
            hint = "PC에서 처리할 문서 작업"
            minLines = 3
            isSaveEnabled = false
            root.addView(this)
        }
        status = label(if (jobId == null) "PC 연결을 확인해 주세요." else "이전 PC 작업 확인 중")
        start = button("PC 작업 시작") { startRequest() }
        button("상태 새로고침") { refresh() }
        stop = button("PC 작업 중지") { runAction { control(stop = true) } }
        answer = label("").apply { setTextIsSelectable(true) }
        updateButtons()
    }

    override fun onStart() {
        super.onStart()
        refresh()
        poll = scope.launch {
            while (isActive) {
                delay(2500)
                if (jobId != null && !terminal && !busy) refresh()
            }
        }
    }

    override fun onStop() {
        poll?.cancel()
        poll = null
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        transport.cancel()
        super.onDestroy()
    }

    private fun startRequest() {
        if (!cloudConsent.isChecked) {
            status.text = "이번 작업의 문서 전송에 동의해 주세요."
            return
        }
        val provider = providerIds.getOrNull(providerPicker.selectedItemPosition) ?: return
        val text = request.text.toString().trim()
        if (text.isEmpty() || text.toByteArray(Charsets.UTF_8).size > 4000) {
            status.text = "요청을 입력해 주세요. 최대 4,000바이트입니다."
            return
        }
        runAction {
            val connection = withContext(Dispatchers.IO) { connect() }
            val id = UUID.randomUUID().toString()
            val pair = pairingKey(connection)
            check(history.edit().putString("job", id).putString("pair", pair).commit())
            jobId = id
            pairing = pair
            terminal = false
            answer.text = ""
            status.text = "PC에 요청 전달 중"
            val payload = buildJsonObject {
                put("jobId", id)
                put("request", text)
                put("provider", provider)
                put("allowCloud", true)
            }
            cloudConsent.isChecked = false
            try {
                render(id, withContext(Dispatchers.IO) {
                    exchange(connection, "/v1/pc-agent/jobs", "POST", id, payload)
                })
                request.text.clear()
            } catch (rejected: StartRejected) {
                clearReference(rejected.message.orEmpty())
            }
        }
    }

    private fun refresh() = runAction {
        if (jobId == null) {
            val data = withContext(Dispatchers.IO) { exchange(connect(), "/v1/pc-agent/capabilities") }
            check(data["version"]?.jsonPrimitive?.content == "1" && data["runtime"]?.jsonPrimitive?.contentOrNull == "openclaw")
            status.text = if (data["configured"]?.jsonPrimitive?.content == "true") {
                val available = data.getValue("providers").jsonArray.map { it.jsonPrimitive.content }
                val names = available.map { if (it == "codex") "Codex" else "Claude" }.joinToString(" · ")
                "OpenClaw PC 작업기 연결됨 · $names"
            } else {
                "PC에서 OpenClaw 작업 설정을 완료해 주세요."
            }
        } else {
            control()
        }
    }

    private suspend fun control(stop: Boolean = false) {
        val id = jobId ?: return
        val expectedPair = pairing
        try {
            render(id, withContext(Dispatchers.IO) {
                val connection = connect()
                if (pairingKey(connection) != expectedPair) throw PairingChanged()
                val path = "/v1/pc-agent/jobs/$id"
                if (stop) exchange(connection, "$path/cancel", "POST", id, buildJsonObject {})
                else exchange(connection, path, owner = id)
            })
        } catch (_: JobMissing) {
            clearReference("PC에 이전 작업 기록이 없습니다. 새 작업을 시작할 수 있습니다.")
        } catch (_: PairingChanged) {
            clearReference("PC 페어링이 바뀌었습니다. 이전 작업의 종료 여부는 해당 PC에서 확인해 주세요.")
        }
    }

    private fun clearReference(message: String) {
        check(history.edit().remove("job").remove("pair").commit())
        jobId = null
        pairing = null
        terminal = false
        answer.text = ""
        status.text = message
    }

    private suspend fun connect(): PcConnection = transport.available() ?: error("PC unavailable")

    private fun pairingKey(config: PcConnection): String =
        MessageDigest.getInstance("SHA-256").digest(
            listOf(config.serverId, config.certificateSha256, config.token).joinToString("\n").toByteArray(),
        ).joinToString("") { "%02x".format(it.toInt() and 255) }

    private suspend fun exchange(
        config: PcConnection, path: String, method: String = "GET", owner: String? = null, body: JsonObject? = null,
    ): JsonObject {
        val expected = pairingKey(config)
        if (settings.snapshot()?.let(::pairingKey) != expected) throw PairingChanged()
        val connection = transport.open(config, path, method, timeout = 7000, computeOwner = owner)
        try {
            return transport.cancellable(connection) {
                if (body != null) {
                    val bytes = body.toString().toByteArray(Charsets.UTF_8)
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use {
                        if (settings.snapshot()?.let(::pairingKey) != expected) throw PairingChanged()
                        it.write(bytes)
                    }
                }
                val code = connection.responseCode
                if (code == 404 && method == "GET" && owner != null) throw JobMissing()
                if (code in setOf(400, 401, 403, 409, 503) && path == "/v1/pc-agent/jobs") {
                    val error = runCatching {
                        val buffer = CharArray(4_096)
                        connection.errorStream?.bufferedReader()?.use { reader ->
                            val count = reader.read(buffer)
                            require(count > 0)
                            val response = ExternalAgentContract.parseObject(String(buffer, 0, count))
                            response["error"]?.jsonPrimitive?.contentOrNull
                        }
                    }.getOrNull()
                    val message = when (error) {
                        "PC_AGENT_BUSY" -> "PC 에이전트가 다른 작업을 처리하고 있습니다."
                        "PC_AGENT_NOT_CONFIGURED", "PC_AGENT_SUBSCRIPTION_NOT_CONFIGURED" ->
                            "PC에서 선택한 구독 설정을 완료해 주세요."
                        "PC_AGENT_CLEANUP_UNCONFIRMED" -> "PC의 이전 작업 종료를 확인해 주세요."
                        else -> "PC가 작업 요청을 받지 않았습니다. 연결과 PC 설정을 확인해 주세요."
                    }
                    throw StartRejected(message)
                }
                check(code in 200..299)
                ExternalAgentContract.parseObject(transport.read(connection, 64_000).toString(Charsets.UTF_8))
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun render(id: String, data: JsonObject) {
        fun field(key: String) = data[key]?.jsonPrimitive?.contentOrNull
        check(field("jobId") == id && field("runtime") == "openclaw" && field("execution") == "pc")
        val provider = field("provider")
        check(provider in providerIds)
        val name = if (provider == "codex") "Codex" else "Claude"
        val state = field("state")
        terminal = state in setOf("RESULT_READY", "FAILED", "CANCELLED")
        status.text = when (state) {
            "RUNNING" -> "OpenClaw가 PC에서 $name 구독으로 작업 중"
            "STOPPING" -> "PC에서 작업을 중지하는 중"
            "RESULT_READY" -> "PC의 분석 답변 도착"
            "CANCELLED" -> "PC 작업 중지됨"
            "FAILED" -> when (field("error")) {
                "PC_AGENT_SUBSCRIPTION_LOGIN_REQUIRED" -> "PC에서 $name 구독 로그인이 필요합니다."
                "PC_AGENT_CLEANUP_UNCONFIRMED" -> "PC 작업 프로세스의 종료를 확인하지 못했습니다."
                else -> "PC에서 $name 작업을 완료하지 못했습니다. OpenClaw 설정과 연결을 확인해 주세요."
            }
            else -> error("Unexpected PC job state")
        }
        answer.text = if (state == "RESULT_READY") checkNotNull(field("answer")) else ""
    }

    private fun updateButtons() {
        start.isEnabled = !busy && (jobId == null || terminal)
        stop.isEnabled = !busy && jobId != null && !terminal
    }

    private fun runAction(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        updateButtons()
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status.text = "PC 응답을 확인하지 못했습니다. 연결을 확인하고 상태를 새로고침해 주세요."
            } finally {
                busy = false
                updateButtons()
            }
        }
    }

    private class StartRejected(message: String) : Exception(message)
    private class JobMissing : Exception()
    private class PairingChanged : Exception()
}
