package com.user.localagent.inference

import android.content.Context
import androidx.annotation.Keep
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Text-only Qwen3.5 GGUF runtime backed by pinned official llama.cpp CPU code.
 * Each request uses the GGUF's actual Jinja template and resets native KV/recurrent state;
 * only the caller's bounded role history is used. No tools or external code run natively.
 */
class LlamaCppEngine(context: Context) : InferenceEngine {
    private val modelsDirectory = File(context.applicationContext.filesDir, "models").canonicalFile
    private val mutex = Mutex()
    private val handleLock = Any()
    @Volatile private var nativeHandle = 0L
    @Volatile private var loadedPath: String? = null
    private var loadedOptions: InferenceOptions? = null
    override val residentModelPath: String? get() = loadedPath
    private val mutableState = MutableStateFlow("모델 없음")
    override val state: StateFlow<String> = mutableState.asStateFlow()
    private val mutableMetrics = MutableStateFlow<InferenceMetrics?>(null)
    override val lastMetrics: StateFlow<InferenceMetrics?> = mutableMetrics.asStateFlow()

    override suspend fun load(path: String, options: InferenceOptions) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(options.backend == "CPU") { "Qwen GGUF 실행은 CPU만 지원합니다." }
            require(!options.audioEnabled && !options.visionEnabled) { "현재 Qwen 실행 엔진은 텍스트만 지원합니다." }
            require(options.contextSize == CONTEXT_TOKENS) { "Qwen CPU 실행은 검증된 4096 토큰 문맥을 사용합니다." }
            require(options.maxOutputTokens in 1..MAX_OUTPUT_TOKENS) { "출력 토큰 제한은 1–2048이어야 합니다." }
            val file = File(path).canonicalFile
            require(file.parentFile == modelsDirectory && file.isFile && file.extension.equals("gguf", true)) {
                "앱 모델 폴더에 검증된 GGUF 모델이 필요합니다."
            }
            if (loadedPath == file.path && nativeHandle != 0L) {
                loadedOptions = options
                return@withLock
            }
            release()
            mutableState.value = "Qwen 모델 로딩 중 · CPU"
            val created = try { LlamaNative.nativeCreate() } catch (error: Throwable) {
                mutableState.value = "Qwen 네이티브 라이브러리 초기화 실패"
                throw error
            }
            check(created != 0L) { "네이티브 추론 엔진을 만들 수 없습니다." }
            synchronized(handleLock) { nativeHandle = created }
            try {
                nativeCall(created) { LlamaNative.nativeLoad(created, file.path, CONTEXT_TOKENS, CPU_THREADS) }
                currentCoroutineContext().ensureActive()
                loadedPath = file.path
                loadedOptions = options
                mutableState.value = "준비됨 · Qwen CPU"
            } catch (error: Throwable) {
                runCatching { release() }
                mutableState.value = if (error is CancellationException) "모델 로딩 중지됨" else "Qwen 모델 초기화 실패"
                throw error
            }
        }
    }

    override fun generate(prompt: String): Flow<String> = generateChat(prompt, "", emptyList())

    override fun generateChat(
        userText: String,
        systemInstruction: String,
        history: List<InferenceTurn>,
    ): Flow<String> = channelFlow {
        val request = validatedRequest(userText, systemInstruction, history)
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val handle = nativeHandle
            check(handle != 0L && loadedPath != null) { "Qwen GGUF 모델을 먼저 로드해주세요." }
            val options = checkNotNull(loadedOptions)
            mutableMetrics.value = null
            mutableState.value = "Qwen 답변 생성 중 · CPU"
            LlamaNative.nativeResetCancellation(handle)
            val sink = NativeTokenSink { piece -> isActive && trySend(piece).isSuccess }
            try {
                val measured = nativeCall(handle) {
                    LlamaNative.nativeGenerate(
                        handle, request.system, request.roles, request.texts, request.user,
                        options.thinking, options.maxOutputTokens, sink,
                    )
                }
                currentCoroutineContext().ensureActive()
                if (measured.size == 5 && measured.all { it.isFinite() && it >= 0.0 }) {
                    mutableMetrics.value = InferenceMetrics(
                        timeToFirstTokenSeconds = measured[0],
                        prefillTokens = measured[1].toInt(),
                        decodeTokens = measured[2].toInt(),
                        prefillTokensPerSecond = measured[3],
                        decodeTokensPerSecond = measured[4],
                    )
                }
            } finally {
                mutableState.value = if (loadedPath == null) "모델 없음" else "준비됨 · Qwen CPU"
            }
        }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    /** Actual model-tokenizer count including Jinja formatting; no character/token estimate. */
    suspend fun countChatTokens(
        userText: String,
        systemInstruction: String,
        history: List<InferenceTurn> = emptyList(),
    ): Int = withContext(Dispatchers.IO) {
        val request = validatedRequest(userText, systemInstruction, history)
        mutex.withLock {
            val handle = nativeHandle
            check(handle != 0L && loadedPath != null) { "Qwen GGUF 모델을 먼저 로드해주세요." }
            LlamaNative.nativeResetCancellation(handle)
            nativeCall(handle) {
                LlamaNative.nativeCountTokens(handle, request.system, request.roles, request.texts, request.user, checkNotNull(loadedOptions).thinking)
            }
        }
    }

    override fun cancel() {
        synchronized(handleLock) {
            val handle = nativeHandle
            if (handle != 0L) runCatching { LlamaNative.nativeCancel(handle) }
        }
    }

    override suspend fun unload() {
        this@LlamaCppEngine.cancel()
        withContext(Dispatchers.IO) { mutex.withLock { release() } }
    }

    private fun release() {
        val previous = synchronized(handleLock) {
            nativeHandle.also { nativeHandle = 0L }
        }
        loadedPath = null
        loadedOptions = null
        mutableMetrics.value = null
        if (previous != 0L) LlamaNative.nativeDestroy(previous)
        mutableState.value = "모델 메모리 해제됨"
    }

    /** Cancellation is registered before entering blocking JNI; CPU/load abort callbacks poll it. */
    private suspend fun <T> nativeCall(handle: Long, operation: () -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { LlamaNative.nativeCancel(handle) } }
        if (continuation.isActive) {
            try { continuation.resumeWith(Result.success(operation())) }
            catch (error: Throwable) { continuation.resumeWith(Result.failure(error)) }
        }
    }

    private data class Request(val user: String, val system: String, val roles: Array<String>, val texts: Array<String>)
    private fun validatedRequest(user: String, system: String, history: List<InferenceTurn>): Request {
        require(user.isNotBlank()) { "요청을 입력해주세요." }
        require(history.size <= MAX_HISTORY_TURNS) { "대화 이력이 너무 깁니다." }
        val turns = history.toList()
        var totalBytes = 0L
        fun checkText(text: String) {
            require(text.length <= MAX_INPUT_BYTES && '\u0000' !in text) { "텍스트 입력 제한을 넘었습니다." }
            totalBytes += text.toByteArray(Charsets.UTF_8).size
            require(totalBytes <= MAX_INPUT_BYTES) { "전체 입력이 256KiB 제한을 넘었습니다. 문서를 나눠주세요." }
        }
        checkText(user)
        checkText(system)
        turns.forEach { turn ->
            require(turn.role in setOf("user", "assistant", "model")) { "대화 이력 역할이 유효하지 않습니다." }
            checkText(turn.text)
        }
        return Request(user, system, turns.map { if (it.role == "model") "assistant" else it.role }.toTypedArray(), turns.map { it.text }.toTypedArray())
    }

    companion object {
        private const val CPU_THREADS = 4
        private const val CONTEXT_TOKENS = 4096
        private const val MAX_OUTPUT_TOKENS = 2048
        private const val MAX_INPUT_BYTES = 256 * 1024
        private const val MAX_HISTORY_TURNS = 64
    }
}

/** JNI calls remain instance methods with fixed names, retained by release shrinkers. */
@Keep
private object LlamaNative {
    init { System.loadLibrary("ziphap_llama") }
    external fun nativeCreate(): Long
    external fun nativeLoad(handle: Long, path: String, contextTokens: Int, threads: Int)
    external fun nativeResetCancellation(handle: Long)
    external fun nativeCancel(handle: Long)
    external fun nativeDestroy(handle: Long)
    external fun nativeCountTokens(handle: Long, system: String, roles: Array<String>, texts: Array<String>, user: String, thinking: Boolean): Int
    external fun nativeGenerate(handle: Long, system: String, roles: Array<String>, texts: Array<String>, user: String, thinking: Boolean, maxOutputTokens: Int, sink: NativeTokenSink): DoubleArray
}

@Keep
private class NativeTokenSink(private val deliver: (String) -> Boolean) {
    @Keep fun onToken(text: String): Boolean = deliver(text)
}

