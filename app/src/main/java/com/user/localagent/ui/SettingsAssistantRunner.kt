package com.user.localagent.ui

import android.content.Context
import com.user.localagent.AppContainer
import com.user.localagent.compute.PcComputeSettings
import com.user.localagent.core.ConversationPrivacy
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import com.user.localagent.externalagent.*
import com.user.localagent.models.AsrCatalog
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal enum class SettingsLink(val label: String) {
    CONNECTIONS("연결 설정 열기"), PRIVACY("개인정보와 권한 열기"), AUTOMATION("자동화 설정 열기"),
    UPDATES("업데이트 설정 열기"), VOICE("음성 설정 열기"), MANUAL("수동 설정 열기"),
}

internal data class SettingsReply(val text: String, val link: SettingsLink? = null, val followUp: Boolean = false)

internal class SettingsAssistantRunner(context: Context, private val app: AppContainer) {
    private val context = context.applicationContext
    private val pc = PcComputeSettings.get(context)
    private val external = ExternalAgentSettings.get(context)

    suspend fun ask(question: String, clarification: Pair<String, String>?): SettingsReply {
        val grant = external.capture()
        if (!grant.allowsPersonalData && ConversationPrivacy.sensitiveText(question)) {
            return SettingsReply("개인정보와 계정 관련 설정은 이 기기의 설정 화면에서 확인할 수 있어요.", SettingsLink.PRIVACY)
        }
        if (!grant.enabled || pc.snapshot() == null) {
            return SettingsReply(
                "설정 대화를 쓰려면 연결된 PC와 Codex 사용 허용이 필요해요. 수동 설정에서는 음성, 이미지, 연결과 권한을 바로 바꿀 수 있어요.",
                SettingsLink.CONNECTIONS,
            )
        }
        if (app.imageStudio.state.value.running || app.runs.busy.value) {
            return SettingsReply("다른 작업이 실행 중이에요. 작업이 끝난 뒤 다시 물어보거나 수동 설정을 열 수 있어요.")
        }
        val pcRevision = pc.captureTextAuthority().revision
        val routing = pc.state.value
        val asr = app.models.asrSelection.value
        val availableVoice = AsrCatalog.modelIds.filter(app.models::asrReady).toMutableList()
        if (app.models.deviceAsrStatus.value.available && asr.language != "auto") availableVoice.add(AsrCatalog.DEVICE_MODEL)
        val prior = clarification
            ?.takeUnless { !grant.allowsPersonalData && (ConversationPrivacy.sensitiveText(it.first) || ConversationPrivacy.sensitiveText(it.second)) }
            ?.let { JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.second))).toString() } ?: "null"
        val request = """
            Act only as the ZipHap settings assistant. Reply in plain Korean for a nontechnical user.
            Answer informational questions without suggesting a write. Ask a short question if the setting or value is ambiguous.
            The current user request is the JSON string on the final line. It alone may authorize one change.
            If the user is answering the last clarification, use this pair of prior request and question to interpret it: $prior
            Only for an explicit request to apply one supported value, call suggest_setting once:
            language = ko, en, auto (audio transcription; auto unavailable with asr-mlkit);
            voice = $availableVoice (only these ready methods; asr-mlkit is the built-in on-device method);
            image = pc or phone (preferred processor for future image work).
            Current transcription language: ${asr.language}; method: ${asr.modelId}; prefer PC for images: ${routing.preferPcImages}.
            Other settings including accounts, permissions, downloads, data, and purchases use the existing settings screens.
            For a link use setting=open, value=connections/privacy/automation/updates/voice/manual. This only displays a button.
            For a method requiring a download or an availability check, offer open=voice.
            Never say a setting was saved. The phone writes it and displays its own verification.
            For a valid suggestion, finish with reported_done. For follow-up questions ask the user directly.
            ${JsonPrimitive(question)}
        """.trimIndent()
        check(grant.allowsPersonalData || !ConversationPrivacy.sensitiveText(request))
        val id = UUID.randomUUID().toString()
        val tools = setOf(SettingsProposal.TOOL)
        val capture = ExternalAgentCapture(
            id, id, id, null, "settings:$id", grant.revision, ExternalAgentContract.sha(request),
            tools, allowExternalAgent = true, expiresAfterMs = 100_000, pcWorkspaceProfile = "phone-only", requirePlan = true,
            allowCloudPersonalData = grant.allowsPersonalData,
        )
        fun live(): Boolean = external.capture() == grant && pc.captureTextAuthority().revision == pcRevision &&
            !app.runs.busy.value && !app.imageStudio.state.value.running
        val client = PcExternalAgentClient(capture, PcAgentBridgeTransport(context, id, beforeLaunchUpload = { check(live()) }))
        var proposal: SettingsProposal? = null
        var duplicate = false
        var started = false
        var closed = false
        val controller = ExternalAgentController(
            capture, client,
            current = {
                ExternalAgentLiveAuthority(id, id, id, null, capture.projectBinding, grant.revision, live(), tools,
                    cancelled = !currentCoroutineContext().isActive)
            },
            execute = { call ->
                if (proposal == null) proposal = SettingsProposal.parse(call.arguments) else duplicate = true
                if (duplicate) ToolResult(call.id, false, "Only one suggestion is allowed.", VerificationState.FAILED, effectNotApplied = true)
                else ToolResult(call.id, true, "Phone recorded a suggestion only.", VerificationState.VERIFIED)
            },
            recordActual = { },
            onPlan = { live() },
        )
        val report = try {
            withTimeout(75_000) {
                controller.start()
                started = true
                client.launchProvider(request, beforeLaunch = { check(live()) })
                while (true) {
                    check(live())
                    val status = client.providerStatus()
                    if (status["state"]?.jsonPrimitive?.content == "FINISHED") {
                        val result = status["report"] as? JsonObject ?: error("SETTINGS_PROVIDER_REPORT")
                        controller.close()
                        closed = true
                        return@withTimeout result
                    }
                    controller.pumpOnce()
                    check(controller.phase in setOf(ExternalAgentController.Phase.ACTIVE, ExternalAgentController.Phase.RESULT_PENDING))
                    delay(300)
                }
                @Suppress("UNREACHABLE_CODE") error("SETTINGS_PROVIDER_LOOP")
            }
        } catch (_: TimeoutCancellationException) {
            return SettingsReply("설정 도우미의 응답을 기다리다 시간이 지났어요. 다시 물어보거나 수동 설정을 열 수 있어요.")
        } finally {
            if (started && !closed) withContext(NonCancellable) {
                withTimeoutOrNull(4_000) { runCatching { client.cancelProvider() } }
                withTimeoutOrNull(4_000) { runCatching { controller.cancel() } }
            }
        }

        currentCoroutineContext().ensureActive()
        check(live())
        val state = report["state"]?.jsonPrimitive?.content
        if (state == "needs_input") return SettingsReply(report.getValue("question").jsonPrimitive.content.take(300), followUp = true)
        check(state == "reported_done" && !duplicate)
        val change = proposal ?: return SettingsReply(report.getValue("answer").jsonPrimitive.content.take(2400))
        return when (change.setting) {
            "open" -> {
                val link = SettingsLink.entries.single { it.name.equals(change.value, ignoreCase = true) }
                SettingsReply("아래 버튼에서 해당 설정을 열 수 있어요.", link)
            }
            "language" -> {
                if (asr.modelId == AsrCatalog.DEVICE_MODEL && change.value == "auto") {
                    SettingsReply("기기 내 음성 AI는 한국어와 영어를 지원해요. 자동 감지를 쓰려면 음성 인식 방법을 먼저 바꿔 주세요.", SettingsLink.VOICE)
                } else {
                    app.models.selectAsrLanguage(change.value, expected = asr, allowed = ::live)
                    check(app.models.storedAsrSelection().language == change.value)
                    val label = mapOf("ko" to "한국어", "en" to "영어", "auto" to "자동 감지").getValue(change.value)
                    SettingsReply("음성 인식 언어를 저장했어요. 현재 선택 · $label")
                }
            }
            "voice" -> {
                if (change.value !in availableVoice) {
                    SettingsReply("이 음성 인식 방법을 쓰려면 모델을 내려받거나 기기 지원 여부를 확인해 주세요.", SettingsLink.VOICE)
                } else {
                    app.models.selectAsr(change.value, expected = asr, allowed = ::live)
                    check(app.models.storedAsrSelection().modelId == change.value)
                    val label = AsrCatalog.descriptors[change.value]?.title ?: "기기 내 음성 AI"
                    SettingsReply("음성 인식 방법을 저장했어요. 현재 선택 · $label")
                }
            }
            "image" -> {
                val preferPc = change.value == "pc"
                withContext(Dispatchers.IO) {
                    check(live())
                    val revision = checkNotNull(pc.updateRoutingIfUnchanged(pcRevision, routing.automaticText, preferPc))
                    check(pc.storedImagePreference(revision) == preferPc)
                }
                SettingsReply("다음 이미지 작업에서 ${if (preferPc) "PC" else "휴대폰"}을 우선 사용하도록 저장했어요.")
            }
            else -> error("SETTINGS_PROPOSAL")
        }
    }
}
