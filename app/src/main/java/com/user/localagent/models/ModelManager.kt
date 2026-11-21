package com.user.localagent.models

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

data class ModelCandidate(val id: String, val title: String, val repository: String, val runtime: String)
@Serializable data class ModelDescriptor(val id: String, val title: String, val url: String, val sha256: String, val sizeBytes: Long, val runtime: String, val revision: String, val fileName: String)
@Serializable data class InstalledModel(val id: String, val title: String, val path: String, val sha256: String, val verified: Boolean, val runtime: String)
data class DownloadProgress(val title: String, val bytes: Long, val total: Long, val phase: String = "download")

class ModelManager(private val context: Context) {
    val candidates = listOf(
        ModelCandidate("gemma-e2b", "Gemma 4 E2B · HauhauCS Aggressive IQ3_M", "HauhauCS/Gemma-4-E2B-Uncensored-HauhauCS-Aggressive", "gguf"),
        ModelCandidate("gemma-e2b-media", "Gemma 4 E2B · 음성·사진 전용", "litert-community/gemma-4-E2B-it-litert-lm", "litertlm"),
        ModelCandidate("gemma-e4b", "Gemma 4 E4B · HauhauCS Aggressive Q4_K_M", "HauhauCS/Gemma-4-E4B-Uncensored-HauhauCS-Aggressive", "gguf"),
        ModelCandidate("gemma-e2b-mmproj", "Gemma 4 E2B · 음성·사진 지원 파일", "HauhauCS/Gemma-4-E2B-Uncensored-HauhauCS-Aggressive", "gguf"),
        ModelCandidate("gemma-e4b-mmproj", "Gemma 4 E4B · 음성·사진 지원 파일", "HauhauCS/Gemma-4-E4B-Uncensored-HauhauCS-Aggressive", "gguf"),
        ModelCandidate("asr-qwen-06b", "Qwen3-ASR 0.6B · 가벼운 음성 인식", "ggml-org/Qwen3-ASR-0.6B-GGUF", "gguf"),
        ModelCandidate("asr-qwen-06b-mmproj", "Qwen3-ASR 0.6B · 음성 입력 지원 파일", "ggml-org/Qwen3-ASR-0.6B-GGUF", "gguf"),
        ModelCandidate("asr-qwen-17b", "Qwen3-ASR 1.7B · 정확도 우선 음성 인식", "ggml-org/Qwen3-ASR-1.7B-GGUF", "gguf"),
        ModelCandidate("asr-qwen-17b-mmproj", "Qwen3-ASR 1.7B · 음성 입력 지원 파일", "ggml-org/Qwen3-ASR-1.7B-GGUF", "gguf"),
        ModelCandidate("qwen-06b", "Qwen3 0.6B · 가벼운 CPU 대안", "litert-community/Qwen3-0.6B", "litertlm"),
        ModelCandidate("qwen-2b", "Qwen3.5 2B · CPU 대안", "unsloth/Qwen3.5-2B-GGUF", "gguf"),
        ModelCandidate("qwen-4b", "Qwen3.5 4B · 품질 비교", "unsloth/Qwen3.5-4B-GGUF", "gguf"),
    )
    // Generic artifacts are explicit. Alphabetical/CPU-substring sorting can select GPU-only or NPU bundles.
    private val genericFiles = mapOf(
        "gemma-e2b" to "Gemma-4-E2B-Uncensored-HauhauCS-Aggressive-IQ3_M.gguf",
        "gemma-e2b-media" to "gemma-4-E2B-it.litertlm",
        "gemma-e4b" to "Gemma-4-E4B-Uncensored-HauhauCS-Aggressive-Q4_K_M.gguf",
        "gemma-e2b-mmproj" to "mmproj-Gemma-4-E2B-Uncensored-HauhauCS-Aggressive-f16.gguf",
        "gemma-e4b-mmproj" to "mmproj-Gemma-4-E4B-Uncensored-HauhauCS-Aggressive-f16.gguf",
        "asr-qwen-06b" to "Qwen3-ASR-0.6B-Q8_0.gguf",
        "asr-qwen-06b-mmproj" to "mmproj-Qwen3-ASR-0.6B-bf16.gguf",
        "asr-qwen-17b" to "Qwen3-ASR-1.7B-Q8_0.gguf",
        "asr-qwen-17b-mmproj" to "mmproj-Qwen3-ASR-1.7B-bf16.gguf",
        "qwen-06b" to "Qwen3-0.6B.litertlm",
        "qwen-2b" to "Qwen3.5-2B-Q4_K_M.gguf",
        "qwen-4b" to "Qwen3.5-4B-Q4_K_M.gguf",
    )
    private val directory = File(context.filesDir, "models").apply { check(isDirectory || mkdirs()) }
    private val index = File(directory, "installed.json")
    private val selected = context.getSharedPreferences("model-selection", Context.MODE_PRIVATE)
    private val mutableModels = MutableStateFlow<List<InstalledModel>>(emptyList())
    val models: StateFlow<List<InstalledModel>> = mutableModels
    private val mutableSelected = MutableStateFlow<InstalledModel?>(null)
    val selectedModel: StateFlow<InstalledModel?> = mutableSelected
    private val mutableProgress = MutableStateFlow<DownloadProgress?>(null)
    val progress: StateFlow<DownloadProgress?> = mutableProgress
    private val mutex = Mutex()
    private data class Verification(val size: Long, val modified: Long, val expected: String)
    private val verificationCache = mutableMapOf<String, Verification>()

    private val mutableMediaSelection = MutableStateFlow(MediaSelection(
        selected.getString("audio-id", "gemma-e2b") ?: "gemma-e2b",
        selected.getString("vision-id", "gemma-e2b") ?: "gemma-e2b"))
    val mediaSelection: StateFlow<MediaSelection> = mutableMediaSelection.asStateFlow()

    fun mediaReady(model: InstalledModel): Boolean = model.id in GemmaMediaCatalog.modelIds && model.runtime == "gguf" && model.verified &&
        mutableModels.value.any { it.id == model.id + "-mmproj" && it.verified && File(it.path).isFile }

    fun mediaModel(purpose: String): InstalledModel {
        require(purpose in setOf("audio", "vision"))
        val id = if (purpose == "audio") mediaSelection.value.audio.takeUnless { GemmaMediaCatalog.imageOnly(it) }
            ?: MediaSelection().audio else mediaSelection.value.vision
        return mutableModels.value.firstOrNull { it.id == id && mediaReady(it) }
            ?: error("모델 화면에서 선택한 Gemma의 음성·사진 지원 파일을 먼저 내려받아주세요.")
    }

    suspend fun selectMedia(purpose: String, id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(purpose in setOf("audio", "vision"))
            val model = mutableModels.value.firstOrNull { it.id == id } ?: error("모델 없음")
            require(purpose == "vision" || !GemmaMediaCatalog.imageOnly(model.id, model.sha256))
            require(mediaReady(model)) { "검증된 Gemma 모델과 음성·사진 지원 파일이 필요합니다." }
            check(selected.edit().putString("$purpose-id", id).commit())
            mutableMediaSelection.value = if (purpose == "audio") mutableMediaSelection.value.copy(audio=id) else mutableMediaSelection.value.copy(vision=id)
        }
    }

    fun mediaOptions(model: InstalledModel, audio: Boolean): com.user.localagent.inference.InferenceOptions {
        require(!audio || !GemmaMediaCatalog.imageOnly(model.id, model.sha256))
        require(mediaReady(model))
        val projector = mutableModels.value.single { it.id == model.id + "-mmproj" && it.verified }
        return com.user.localagent.inference.InferenceOptions(contextSize=4096, thinking=false, backend="CPU",
            maxOutputTokens=if(audio)512 else 256, audioEnabled=audio, visionEnabled=!audio,
            projectorPath=projector.path, cpuThreads=if(model.id=="gemma-e4b")2 else 4)
    }

    suspend fun downloadBundle(candidate: ModelCandidate) {
        download(resolve(candidate))
        if (candidate.id in GemmaMediaCatalog.modelIds || candidate.id in AsrCatalog.modelIds) download(resolve(candidates.single { it.id == candidate.id + "-mmproj" }))
    }

    /** Called only for the owner's explicit legacy cleanup after both new media models pass QA. */
    suspend fun deleteLegacyGemma(): Long = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(GemmaMediaCatalog.modelIds.all { id -> mutableModels.value.any { it.id == id && mediaReady(it) } })
            val legacy = Regex("gemma-e[24]b(?:-media)?-[a-f0-9]{12}\\.litertlm(?:\\.part)?")
            var bytes = 0L
            directory.listFiles().orEmpty().filter { it.isFile && legacy.matches(it.name) }.forEach { file ->
                bytes += file.length(); deleteFixedFile(file); verificationCache.remove(file.canonicalPath)
            }
            saveIndex(mutableModels.value.filterNot { it.id == "gemma-e2b-media" || it.id in GemmaMediaCatalog.modelIds && it.runtime == "litertlm" })
            require(directory.listFiles().orEmpty().none { legacy.matches(it.name) })
            bytes
        }
    }

    private val mutableAsrSelection=MutableStateFlow(storedAsrSelection())
    internal fun storedAsrSelection()=AsrSelection(
        selected.getString("asr-id",AsrCatalog.DEFAULT_MODEL)?:AsrCatalog.DEFAULT_MODEL,
        selected.getString("asr-language","ko")?:"ko")
    val asrSelection:StateFlow<AsrSelection> = mutableAsrSelection.asStateFlow()
    private val mutableDeviceAsrStatus=MutableStateFlow(DeviceAsrStatus())
    val deviceAsrStatus:StateFlow<DeviceAsrStatus> = mutableDeviceAsrStatus.asStateFlow()
    suspend fun prepareDeviceAsr(download:Boolean=false) {
        mutableDeviceAsrStatus.value=com.user.localagent.inference.SystemAsrEngine.prepare(asrSelection.value.language,download)
    }
    fun asrReady(id:String):Boolean = id in AsrCatalog.modelIds &&
        mutableModels.value.any {it.id==id&&it.verified&&File(it.path).isFile} &&
        mutableModels.value.any {it.id=="$id-mmproj"&&it.verified&&File(it.path).isFile}
    fun asrModel():InstalledModel {
        val id=asrSelection.value.modelId
        if(id==AsrCatalog.DEVICE_MODEL)return InstalledModel(id,"기기 내 음성 AI · ML Kit",AsrCatalog.DEVICE_PATH,"",true,"aicore")
        require(asrReady(id)){"모델 화면에서 선택한 전용 음성 인식 모델과 지원 파일을 먼저 내려받아주세요."}
        return mutableModels.value.single {it.id==id&&it.verified}
    }
    fun asrOptions(model:InstalledModel):com.user.localagent.inference.InferenceOptions {
        if(model.id==AsrCatalog.DEVICE_MODEL)return com.user.localagent.inference.InferenceOptions(contextSize=2048,audioEnabled=true)
        require(asrReady(model.id))
        val projector=mutableModels.value.single {it.id==model.id+"-mmproj"&&it.verified}
        return com.user.localagent.inference.InferenceOptions(contextSize=2048,thinking=false,backend="CPU",maxOutputTokens=512,
            audioEnabled=true,projectorPath=projector.path,cpuThreads=if(model.id=="asr-qwen-17b")2 else 4)
    }
    suspend fun selectAsr(id:String, expected:AsrSelection?=null, allowed:()->Boolean={true})=withContext(Dispatchers.IO){mutex.withLock {
        check(allowed() && (expected == null || asrSelection.value == expected))
        require(if(id==AsrCatalog.DEVICE_MODEL)mutableDeviceAsrStatus.value.available&&asrSelection.value.language!="auto" else asrReady(id)){"음성 모델의 다운로드 또는 기기 내 AI의 지원 확인이 필요합니다."}
        check(selected.edit().putString("asr-id",id).commit())
        mutableAsrSelection.value=mutableAsrSelection.value.copy(modelId=id)
    }}
    suspend fun selectAsrLanguage(language:String, expected:AsrSelection?=null, allowed:()->Boolean={true})=withContext(Dispatchers.IO){mutex.withLock {
        check(allowed() && (expected == null || asrSelection.value == expected))
        require(language in setOf("ko","en","auto"))
        require(language!="auto"||asrSelection.value.modelId!=AsrCatalog.DEVICE_MODEL){"기기 내 음성 AI는 한국어 또는 영어를 지정해주세요."}
        check(selected.edit().putString("asr-language",language).commit())
        mutableAsrSelection.value=mutableAsrSelection.value.copy(language=language)
        mutableDeviceAsrStatus.value=DeviceAsrStatus()
    }}

    suspend fun refresh() = withContext(Dispatchers.IO) { mutex.withLock { refreshInternal() } }

    private suspend fun refreshInternal() {
        val values = try {
            if (!index.exists()) emptyList() else {
                val text = index.inputStream().use { decodeUtf8(readBounded(it, MAX_METADATA_BYTES)) }
                Json.decodeFromString<List<InstalledModel>>(text)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            mutableModels.value = emptyList()
            clearSelection()
            throw IllegalStateException("모델 목록 파일을 읽을 수 없습니다. 기존 모델 파일은 유지됩니다.", error)
        }
        require(values.size <= 100 && values.map { it.id }.distinct().size == values.size) { "모델 목록이 유효하지 않습니다." }
        // Keep the original multimodal E2B when replacing its chat weights with GGUF.
        // The alias retains the original file in place; no weights are copied or deleted.
        val legacyMedia = values.firstOrNull { it.id == "gemma-e2b" && it.runtime == "litertlm" }
        val current = if (legacyMedia == null) values else {
            values.filterNot { it.id == legacyMedia.id } +
                if (values.any { it.id == "gemma-e2b-media" }) emptyList() else
                    listOf(legacyMedia.copy(id = "gemma-e2b-media", title = candidates.first { it.id == "gemma-e2b-media" }.title))
        }
        val checked = mutableListOf<InstalledModel>()
        try {
            for (model in current) {
                currentCoroutineContext().ensureActive()
                val file = managedFile(model) ?: continue
                if (!file.isFile) continue
                // A stored verified flag is not proof. Hash once per process/file identity.
                val valid = model.verified && HASH.matches(model.sha256) && verifiedFile(file, model.sha256, model.title)
                checked += model.copy(verified = valid)
            }
        } finally {
            mutableProgress.value = null
        }
        if (checked != values) saveIndex(checked) else mutableModels.value = checked
        val selectedId = selected.getString("id", null)
        mutableSelected.value = checked.find { it.id == selectedId && it.verified && GemmaMediaCatalog.supportsText(it.id,it.sha256) && it.runtime in setOf("litertlm", "gguf") }
        if (selectedId != null && mutableSelected.value == null) clearSelection()
    }

    suspend fun resolve(candidate: ModelCandidate): ModelDescriptor = withContext(Dispatchers.IO) {
        require(candidate in candidates && candidate.runtime in setOf("litertlm", "gguf")) { "지원하지 않는 실행 엔진입니다." }
        AsrCatalog.descriptors[candidate.id]?.let {return@withContext it}
        val expectedName = genericFiles.getValue(candidate.id)
        val connection = connection("https://huggingface.co/api/models/${candidate.repository}?blobs=true")
        val root = try {
            connection.inputStream.use { Json.parseToJsonElement(decodeUtf8(readBounded(it, MAX_METADATA_BYTES))).jsonObject }
        } finally { connection.disconnect() }
        require(root["id"]?.jsonPrimitive?.content == candidate.repository) { "모델 저장소 응답이 일치하지 않습니다." }
        val revision = root.getValue("sha").jsonPrimitive.content
        require(COMMIT.matches(revision)) { "배포 커밋 정보가 유효하지 않습니다." }
        val file = root.getValue("siblings").jsonArray.map { it.jsonObject }
            .singleOrNull { it["rfilename"]?.jsonPrimitive?.content == expectedName }
            ?: error("CPU/GPU 일반 모델 파일 $expectedName 이 배포 목록에 없습니다.")
        val lfs = file["lfs"]?.jsonObject ?: error("배포자가 제공하는 SHA-256 정보가 없습니다.")
        val hash = (lfs["sha256"] ?: lfs["oid"])?.jsonPrimitive?.content?.removePrefix("sha256:") ?: error("SHA-256 누락")
        val size = (lfs["size"] ?: file["size"])?.jsonPrimitive?.longOrNull ?: error("모델 크기 누락")
        require(HASH.matches(hash) && size in 1..MAX_MODEL_BYTES) { "모델 크기 또는 SHA-256 정보가 유효하지 않습니다." }
        ModelDescriptor(candidate.id, candidate.title, "https://huggingface.co/${candidate.repository}/resolve/$revision/$expectedName", hash, size, candidate.runtime, revision, expectedName)
    }

    suspend fun download(descriptor: ModelDescriptor): InstalledModel = withContext(Dispatchers.IO) {
        mutex.withLock {
            validateDescriptor(descriptor)
            val destination = File(directory, "${descriptor.id}-${descriptor.sha256.take(12)}.${descriptor.runtime}")
            val partial = File(destination.path + ".part")
            try {
                if (destination.isFile && verifiedFile(destination, descriptor.sha256, descriptor.title)) return@withLock register(descriptor, destination)
                var existing = if (partial.isFile) partial.length() else 0L
                if (existing > descriptor.sizeBytes) {
                    deleteFixedFile(partial)
                    existing = 0L
                }
                // A fully written partial needs only hashing/rename, not an invalid Range EOF request.
                if (existing < descriptor.sizeBytes) {
                    ensureFree(descriptor.sizeBytes - existing)
                    val stream = connection(descriptor.url, existing)
                    try {
                        if (stream.responseCode == HttpURLConnection.HTTP_OK && existing > 0) {
                            ensureFree(descriptor.sizeBytes)
                            existing = 0
                        }
                        if (stream.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                            val range = RANGE.matchEntire(stream.getHeaderField("Content-Range").orEmpty()) ?: error("다운로드 재개 범위가 없습니다.")
                            require(range.groupValues[1].toLong() == existing && range.groupValues[2].toLong() == descriptor.sizeBytes - 1 && range.groupValues[3].toLong() == descriptor.sizeBytes) { "다운로드 재개 범위가 일치하지 않습니다." }
                        }
                        val length = stream.contentLengthLong
                        require(length < 0 || length == descriptor.sizeBytes - existing) { "다운로드 응답 크기가 일치하지 않습니다." }
                        require(stream.contentEncoding == null || stream.contentEncoding.equals("identity", true)) { "압축된 모델 다운로드 응답은 지원하지 않습니다." }
                        mutableProgress.value = DownloadProgress(descriptor.title, existing, descriptor.sizeBytes)
                        stream.inputStream.use { input ->
                            java.io.FileOutputStream(partial, existing > 0).use { output ->
                                val buffer = ByteArray(BUFFER_BYTES)
                                var bytes = existing
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    bytes += count
                                    require(bytes <= descriptor.sizeBytes) { "모델 크기가 예상과 다릅니다." }
                                    output.write(buffer, 0, count)
                                    mutableProgress.value = DownloadProgress(descriptor.title, bytes, descriptor.sizeBytes)
                                }
                                output.fd.sync()
                            }
                        }
                    } finally { stream.disconnect() }
                }
                require(partial.isFile && partial.length() == descriptor.sizeBytes) { "모델 다운로드가 완료되지 않았습니다. 다시 시도하면 이어서 받습니다." }
                val hash = sha256(partial, descriptor.title)
                if (hash != descriptor.sha256) {
                    deleteFixedFile(partial)
                    error("모델 SHA-256 검증에 실패했습니다. 손상된 부분 파일을 제거했습니다.")
                }
                currentCoroutineContext().ensureActive()
                check(partial.renameTo(destination)) { "모델 파일을 확정할 수 없습니다." }
                rememberVerified(destination, descriptor.sha256)
                register(descriptor, destination)
            } finally { mutableProgress.value = null }
        }
    }

    suspend fun importModel(uri: Uri, title: String, expectedSha256: String? = null): InstalledModel = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) { "파일 선택기로 고른 모델 파일이 필요합니다." }
            val expected = expectedSha256?.lowercase(Locale.ROOT)
            require(expected == null || HASH.matches(expected)) { "예상 SHA-256은 64자리 16진수여야 합니다." }
            val id = UUID.randomUUID().toString()
            var output = File(directory, "$id.litertlm")
            val partial = File(output.path + ".part")
            var committed = false
            try {
                val hint = runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (cursor.moveToFirst() && column >= 0 && !cursor.isNull(column)) cursor.getLong(column).takeIf { it >= 0 } else null
                    }
                }.getOrNull()
                require(hint == null || hint in 1..MAX_MODEL_BYTES) { "가져올 모델 크기는 1바이트–8GiB여야 합니다." }
                ensureFree(hint ?: BUFFER_BYTES.toLong())
                context.contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(partial).use { destination ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var total = 0L
                        var nextSpaceCheck = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_MODEL_BYTES) { "가져올 모델이 8GiB 제한을 넘었습니다." }
                            if (total >= nextSpaceCheck) {
                                ensureFree(BUFFER_BYTES.toLong())
                                nextSpaceCheck = total + 8 * 1024 * 1024
                            }
                            destination.write(buffer, 0, count)
                        }
                        require(total > 0) { "모델 파일이 비어 있습니다." }
                        destination.fd.sync()
                    }
                } ?: error("선택한 파일을 열 수 없습니다.")
                val hash = sha256(partial, title)
                require(expected == null || hash == expected) { "가져온 모델 해시가 일치하지 않습니다." }
                currentCoroutineContext().ensureActive()
                val magic=ByteArray(4)
                val runtime=partial.inputStream().use {input->if(input.read(magic)==4 && magic.contentEquals(byteArrayOf(0x47,0x47,0x55,0x46)))"gguf" else "litertlm"}
                output=File(directory,"$id.$runtime")
                check(partial.renameTo(output)) { "가져온 모델 파일을 확정할 수 없습니다." }
                val model = InstalledModel(id, title.take(200).ifBlank { "가져온 로컬 모델" }, output.path, hash, expected != null, runtime)
                if (model.verified) rememberVerified(output, hash)
                saveIndex(mutableModels.value + model)
                committed = true
                model
            } finally {
                mutableProgress.value = null
                if (!committed) {
                    partial.delete()
                    output.delete()
                    verificationCache.remove(output.canonicalPath)
                }
            }
        }
    }

    /** User supplies a publisher hash obtained separately; an observed local digest alone grants no trust. */
    suspend fun verifyImported(id: String, expectedSha256: String): InstalledModel = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(importId(id)) { "배포 모델의 검증 실패는 공식 파일을 다시 받아 해결해주세요." }
            val expected = expectedSha256.lowercase(Locale.ROOT)
            require(HASH.matches(expected)) { "예상 SHA-256은 64자리 16진수여야 합니다." }
            val model = mutableModels.value.find { it.id == id } ?: error("모델 없음")
            val file = managedFile(model)?.takeIf { it.isFile } ?: error("모델 파일이 없습니다.")
            try {
                val actual = sha256(file, model.title)
                if (actual != expected) {
                    saveIndex(mutableModels.value.map { if (it.id == id) it.copy(verified = false) else it })
                    verificationCache.remove(file.canonicalPath)
                    error("원본 SHA-256과 일치하지 않습니다. 모델을 선택할 수 없습니다.")
                }
                val verified = model.copy(sha256 = expected, verified = true)
                rememberVerified(file, expected)
                saveIndex(mutableModels.value.map { if (it.id == id) verified else it })
                verified
            } finally { mutableProgress.value = null }
        }
    }

    suspend fun select(id: String) = withContext(Dispatchers.IO) {
        require(GemmaMediaCatalog.supportsText(id)) { "이 모델은 대화에서 사용할 수 없습니다." }
        mutex.withLock {
            val model = mutableModels.value.find { it.id == id } ?: error("모델 없음")
            require(GemmaMediaCatalog.supportsText(model.id,model.sha256))
            require(model.runtime in setOf("litertlm", "gguf")) { "지원하지 않는 실행 엔진입니다." }
            require(model.verified && HASH.matches(model.sha256)) { "원본 SHA-256이 확인되지 않은 모델입니다." }
            val file = managedFile(model) ?: error("모델 경로가 유효하지 않습니다.")
            try {
                if (!verifiedFile(file, model.sha256, model.title)) {
                    saveIndex(mutableModels.value.map { if (it.id == id) it.copy(verified = false) else it })
                    error("모델 파일 손상 감지: 선택을 해제했습니다.")
                }
                check(selected.edit().putString("id", id).commit()) { "모델 선택을 저장할 수 없습니다." }
                mutableSelected.value = model
            } finally { mutableProgress.value = null }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(candidates.any { it.id == id } || importId(id)) { "모델 ID가 유효하지 않습니다." }
            mutableModels.value.find { it.id == id }?.let { model -> managedFile(model)?.let { deleteFixedFile(it); verificationCache.remove(it.canonicalPath) } }
            val matchingPart = Regex("${Regex.escape(id)}-[a-f0-9]{12}\\.(?:litertlm|gguf)\\.part")
            directory.listFiles().orEmpty().filter { it.name == "$id.litertlm.part" || matchingPart.matches(it.name) }.forEach(::deleteFixedFile)
            saveIndex(mutableModels.value.filterNot { it.id == id })
        }
    }

    private fun validateDescriptor(value: ModelDescriptor) {
        val candidate = candidates.find { it.id == value.id && it.runtime in setOf("litertlm", "gguf") } ?: error("지원하지 않는 다운로드 모델입니다.")
        val name = genericFiles.getValue(candidate.id)
        require(value.runtime == candidate.runtime && value.fileName == name && HASH.matches(value.sha256) && COMMIT.matches(value.revision) && value.sizeBytes in 1..MAX_MODEL_BYTES) { "모델 배포 정보가 유효하지 않습니다." }
        require(value.url == "https://huggingface.co/${candidate.repository}/resolve/${value.revision}/$name") { "고정된 공식 모델 파일 주소가 아닙니다." }
    }

    private fun register(descriptor: ModelDescriptor, file: File): InstalledModel {
        val model = InstalledModel(descriptor.id, descriptor.title, file.path, descriptor.sha256, true, descriptor.runtime)
        // Replacing an index entry never deletes previously downloaded artifacts automatically.
        saveIndex(mutableModels.value.filterNot { it.id == model.id } + model)
        return model
    }

    private fun managedFile(model: InstalledModel): File? {
        if (model.runtime !in setOf("litertlm", "gguf")) return null
        if (!importId(model.id) && candidates.none { it.id == model.id }) return null
        val file = File(model.path)
        val safeName = if (importId(model.id)) file.name == "${model.id}.${model.runtime}" else Regex("${Regex.escape(model.id)}-[a-f0-9]{12}\\.${Regex.escape(model.runtime)}").matches(file.name)
        val legacyMediaName = model.id == "gemma-e2b-media" && model.runtime == "litertlm" &&
            file.name == "gemma-e2b-${model.sha256.take(12)}.litertlm"
        return if ((safeName || legacyMediaName) && file.canonicalFile.parentFile == directory.canonicalFile && file.canonicalFile.name == file.name) file else null
    }

    private fun importId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    private suspend fun verifiedFile(file: File, expected: String, title: String): Boolean {
        if (!HASH.matches(expected) || !file.isFile || file.length() !in 1..MAX_MODEL_BYTES) return false
        val stamp = Verification(file.length(), file.lastModified(), expected)
        if (verificationCache[file.canonicalPath] == stamp) return true
        return try {
            val valid = sha256(file, title) == expected && stamp.size == file.length() && stamp.modified == file.lastModified()
            if (valid) verificationCache[file.canonicalPath] = stamp else verificationCache.remove(file.canonicalPath)
            valid
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            verificationCache.remove(file.canonicalPath)
            false
        }
    }

    private fun rememberVerified(file: File, expected: String) { verificationCache[file.canonicalPath] = Verification(file.length(), file.lastModified(), expected) }

    private fun saveIndex(values: List<InstalledModel>) {
        val temporary = File(index.path + ".tmp")
        java.io.FileOutputStream(temporary).use { output -> output.write(Json.encodeToString(values).toByteArray(Charsets.UTF_8)); output.fd.sync() }
        check(temporary.renameTo(index)) { "모델 목록을 저장할 수 없습니다." }
        mutableModels.value = values
        val id = selected.getString("id", null)
        mutableSelected.value = values.find { it.id == id && it.verified && HASH.matches(it.sha256) && GemmaMediaCatalog.supportsText(it.id,it.sha256) && it.runtime in setOf("litertlm", "gguf") }
        if (id != null && mutableSelected.value == null) clearSelection()
    }

    private fun clearSelection() { mutableSelected.value = null; selected.edit().remove("id").apply() }

    private fun deleteFixedFile(file: File) {
        require(file.canonicalFile.parentFile == directory.canonicalFile && file.canonicalFile.name == file.name) { "모델 폴더 밖의 파일은 삭제할 수 없습니다." }
        check(!file.exists() || (file.isFile && file.delete())) { "모델 파일을 삭제할 수 없습니다." }
    }

    private fun ensureFree(required: Long) { require(required >= 0 && StatFs(directory.path).availableBytes >= required + STORAGE_RESERVE_BYTES) { "모델 저장에 필요한 저장공간이 부족합니다." } }

    private suspend fun connection(url: String, offset: Long = 0): HttpURLConnection {
        var next = URI(url)
        repeat(8) {
            currentCoroutineContext().ensureActive()
            val host = next.host?.lowercase(Locale.ROOT).orEmpty()
            require(next.scheme == "https" && next.userInfo == null && next.fragment == null && next.port in setOf(-1, 443) &&
                (host == "huggingface.co" || host.endsWith(".huggingface.co") || host.endsWith(".hf.co"))) { "공식 HTTPS Hugging Face/CDN 주소만 허용됩니다." }
            val connection = next.toURL().openConnection() as HttpURLConnection
            var returned = false
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "GalaxyLocalAgent/0.1")
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                when (val code = connection.responseCode) {
                    301, 302, 303, 307, 308 -> next = next.resolve(connection.getHeaderField("Location") ?: error("다운로드 주소 누락"))
                    200, 206 -> { returned = true; return connection }
                    401, 403 -> error("모델 배포 사이트의 사용 승인 또는 로그인이 필요합니다.")
                    else -> error("모델 다운로드 응답 $code")
                }
            } finally { if (!returned) connection.disconnect() }
        }
        error("다운로드 리디렉션 제한")
    }

    private suspend fun readBounded(input: InputStream, maximum: Int): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size().toLong() + count <= maximum) { "모델 메타데이터 크기 제한을 넘었습니다." }
            bytes.write(buffer, 0, count)
        }
        return bytes.toByteArray()
    }

    private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()

    private suspend fun sha256(file: File, title: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val size = file.length()
        require(size in 1..MAX_MODEL_BYTES) { "모델 파일 크기가 유효하지 않습니다." }
        var bytes = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                bytes += count
                require(bytes <= MAX_MODEL_BYTES) { "모델 크기 제한을 넘었습니다." }
                digest.update(buffer, 0, count)
                mutableProgress.value = DownloadProgress(title, bytes, size, "hash")
            }
        }
        require(bytes == size && file.length() == size) { "검증 중 모델 크기가 변경되었습니다." }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val BUFFER_BYTES = 128 * 1024
        private const val MAX_METADATA_BYTES = 2 * 1024 * 1024
        private const val MAX_MODEL_BYTES = 8L * 1024 * 1024 * 1024
        private const val STORAGE_RESERVE_BYTES = 256L * 1024 * 1024
        private val HASH = Regex("[a-f0-9]{64}")
        private val COMMIT = Regex("[a-f0-9]{40}")
        private val RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
    }
}
