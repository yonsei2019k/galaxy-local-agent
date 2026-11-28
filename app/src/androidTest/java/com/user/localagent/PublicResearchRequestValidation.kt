package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.benchmark.RuntimeGate
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real chat entry point, public synthetic queries only; never sends a messenger reply. */
@RunWith(AndroidJUnit4::class)
class PublicResearchRequestValidation {
    @Test fun connectionMethodAndCorrectionUsePublicResearch() = runBlocking<Unit>(Dispatchers.Default) {
        assumeTrue("publicResearchRequestApproved=true required",
            InstrumentationRegistry.getArguments().getString("publicResearchRequestApproved") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as LocalAgentApplication).container
        val runs = app.runs
        val chat = app.repository.chat
        withTimeout(20_000) { runs.selectedThreadId.first { it.isNotBlank() } }
        check(!runs.busy.value && runs.queued.value.isEmpty() && !RuntimeGate.inference.get()) {
            "Wait for the existing owner task before running public research validation"
        }
        val ownsBenchmark = RuntimeGate.benchmark.compareAndSet(false, true)
        check(ownsBenchmark) { "Another device validation is running" }
        val previous = runs.selectedThreadId.value
        val title = "QA public connection method ${UUID.randomUUID()}"
        var ownedId: String? = null
        var passed = 0
        var cleaned = false
        var failure: String? = null
        val reviews = mutableListOf<JsonObject>()
        var activeQuestion = 0
        var activeQuery = ""
        var activeBeforeMessages = emptySet<String>()
        var activeRunId: String? = null
        var activeRunStatus = "not_completed"
        fun writeReport() {
            val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "benchmarks").apply { mkdirs() }
            File(directory, "public-research-request.json").writeText(buildJsonObject {
                put("synthetic_public_queries_only", true)
                put("response_scope", "this_test_owned_synthetic_thread_only")
                put("chat_requests_verified", passed)
                put("synthetic_thread_cleanup_verified", cleaned)
                put("failure_class", failure)
                put("questions", JsonArray(reviews.toList()))
            }.toString())
        }
        suspend fun captureSyntheticResponse(): JsonObject? {
            val id = ownedId ?: return null
            val owned = chat.thread(id) ?: return null
            if (owned.title != title || activeQuestion <= 0) return null
            val message = chat.messages(id, owned.activeBranchId).first()
                .lastOrNull { it.role == "assistant" && it.id !in activeBeforeMessages }
            val response = message?.let { chat.fullMessageText(it.id) }.orEmpty()
            val observed = runs.lastResearchSourceUrls.value.toSet()
            val citations = markdownHttpUrls(response)
            val observedKeys = observed.map(::citationKey).toSet()
            val unobserved = citations.filter { citationKey(it) !in observedKeys }
            val steps = withContext(Dispatchers.Main) { activeRunId?.let { runId ->
                runs.completedSteps[runId].orEmpty().map { step -> buildJsonObject {
                    put("tool", step.tool); put("verification", step.evidence.state.name)
                } }
            }.orEmpty() }
            val value = buildJsonObject {
                put("question_number", activeQuestion); put("synthetic_request", activeQuery)
                put("run_status", activeRunStatus); put("response", response)
                put("semantic_model_id", runs.lastSemanticModelId.value)
                put("semantic_action", runs.lastSemanticAction.value)
                put("observed_urls", JsonArray(observed.sorted().map(::JsonPrimitive)))
                put("markdown_citation_urls", JsonArray(citations.map(::JsonPrimitive)))
                put("unobserved_markdown_urls", JsonArray(unobserved.map(::JsonPrimitive)))
                put("steps", JsonArray(steps))
            }
            val existing = reviews.indexOfFirst { it.getValue("question_number").jsonPrimitive.int == activeQuestion }
            if (existing >= 0) reviews[existing] = value else reviews += value
            return value
        }
        try {
            val thread = chat.createThread(title = title)
            ownedId = thread.id
            withContext(Dispatchers.Main) { runs.selectThread(thread.id) }
            withTimeout(20_000) { runs.selectedThreadId.first { it == thread.id } }
            for ((index, query) in listOf("카카오톡에 직접 연결할 수 있는 방법을 찾아봐", "파일 가져오기 말고 다른 연결 방법 조사해").withIndex()) {
                activeQuestion = index + 1; activeQuery = query; activeRunId = null; activeRunStatus = "not_completed"
                activeBeforeMessages = chat.messages(thread.id, thread.activeBranchId).first().map { it.id }.toSet()
                val before = chat.runs.first().filter { it.threadId == thread.id }.map { it.id }.toSet()
                assertTrue(withContext(Dispatchers.Main) { runs.send(query) })
                val completed = withTimeout(600_000) {
                    chat.runs.first { list -> list.any {
                        it.threadId == thread.id && it.id !in before && it.status in setOf("completed", "failed", "cancelled")
                    } }.first { it.threadId == thread.id && it.id !in before }
                }
                activeRunId = completed.id; activeRunStatus = completed.status
                withTimeout(10_000) { runs.busy.first { !it } }
                val review = checkNotNull(captureSyntheticResponse())
                writeReport()
                assertEquals("The complete chat request must finish", "completed", completed.status)
                val response = review.getValue("response").jsonPrimitive.content
                assertTrue("The actual chat must explain its research result", response.isNotBlank())
                assertFalse("Connection-method research must not end as inbox status", response.contains("지금 받은 알림"))
                assertFalse("Method research must not display shopping fallback copy", response.contains("가격: 판매처에서 확인"))
                assertEquals("PUBLIC_RESEARCH", runs.lastSemanticAction.value)
                assertFalse("A real local model must interpret the context", runs.lastSemanticModelId.value.isNullOrBlank())
                val observed = runs.lastResearchSourceUrls.value
                assertTrue("The live investigation must return observed sources", observed.isNotEmpty())
                assertTrue("The final answer must cite an observed source", observed.any { response.contains(it) })
                assertTrue("Markdown citations must point only to actually observed URLs",
                    review.getValue("unobserved_markdown_urls").jsonArray.isEmpty())
                passed++
                activeQuestion = 0
            }
        } catch (error: Throwable) {
            failure = error.javaClass.simpleName
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                if (ownedId != null && runs.activeThreadId == ownedId) {
                    withContext(Dispatchers.Main) { runs.cancelThread(ownedId!!) }
                    withTimeoutOrNull(10_000) { while (runs.activeThreadId == ownedId) delay(100) }
                }
                captureSyntheticResponse()
                if (chat.thread(previous) != null) {
                    withContext(Dispatchers.Main) { runs.selectThread(previous) }
                    withTimeout(10_000) { runs.selectedThreadId.first { it == previous } }
                }
                ownedId?.let { id ->
                    val owned = chat.thread(id)
                    if (owned != null && owned.title == title && runs.activeThreadId != id) chat.deleteThread(id, deleteDerivedMemory = true)
                    cleaned = chat.thread(id) == null
                }
                } finally {
                    try { writeReport() } finally { RuntimeGate.benchmark.set(false) }
                }
            }
        }
        assertTrue("Only this test's synthetic thread must be removed", cleaned)
    }

    /** Only explicit Markdown HTTP(S) links; quoted prose and non-web navigation are not citations. */
    private fun markdownHttpUrls(text: String): List<String> {
        val urls = mutableListOf<String>()
        Regex("""(?<!!)\[[^\]\r\n]*]\(""").findAll(text).forEach { match ->
            var index = match.range.last + 1
            while (index < text.length && text[index].isWhitespace()) index++
            val value = StringBuilder()
            if (index < text.length && text[index] == '<') {
                index++
                while (index < text.length && text[index] != '>') value.append(text[index++])
                if (index == text.length) return@forEach
            } else {
                var depth = 0
                while (index < text.length) {
                    val ch = text[index]
                    if (ch == '\\' && index + 1 < text.length) { value.append(text[index + 1]); index += 2; continue }
                    if (ch == ')' && depth == 0 || ch.isWhitespace() && depth == 0) break
                    if (ch == '(') depth++ else if (ch == ')') depth--
                    value.append(ch); index++
                }
                if (index == text.length) return@forEach
            }
            val url = value.toString()
            if (url.startsWith("https://", true) || url.startsWith("http://", true)) urls += url
        }
        return urls.distinct()
    }

    private fun citationKey(raw: String): String = runCatching {
        val uri = URI(raw).normalize()
        uri.scheme.lowercase(Locale.ROOT) + "://" + uri.rawAuthority.lowercase(Locale.ROOT) +
            uri.rawPath.orEmpty() + (uri.rawQuery?.let { "?$it" } ?: "")
    }.getOrDefault(raw)
}
