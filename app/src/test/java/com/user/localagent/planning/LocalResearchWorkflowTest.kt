package com.user.localagent.planning

import com.user.localagent.core.SemanticAction
import com.user.localagent.core.SemanticRequestPlan
import com.user.localagent.core.SemanticSourceKind
import com.user.localagent.inference.InferenceOptions
import com.user.localagent.models.ChosenInference
import com.user.localagent.models.InstalledModel
import com.user.localagent.web.PublicWebClient
import com.user.localagent.web.PublicWebResponse
import com.user.localagent.web.PublicWebTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.time.Instant

/** No Android engine, live requests, personal histories, accounts or API keys enter these fixtures. */
class LocalResearchWorkflowTest {
    private val timestamp = Instant.parse("2030-01-01T00:00:00Z")
    private val first = "https://docs.first.example/connect"
    private val second = "https://help.second.example/limits"
    private val chosen = ChosenInference(
        InstalledModel("synthetic", "Synthetic local model", "/not-loaded/synthetic.gguf", "synthetic", true, "gguf"),
        InferenceOptions(), "injected decision test"
    )
    private fun plan() = SemanticRequestPlan(SemanticAction.PUBLIC_RESEARCH,
        "카카오톡 직접 연결 방법과 지원 범위를 근거로 비교한다", "카카오톡", "카카오톡 연결 방법",
        SemanticSourceKind.NONE, setOf("search_web"), listOf("내보내기 안내 제외"), false, "")
    private fun reply(url: String, body: String, status: Int = 200) =
        PublicWebResponse(status, url, "text/html", body.toByteArray())
    private fun isSearch(url: String) = URI(url).host in setOf("lite.duckduckgo.com", "search.yahoo.com", "www.bing.com")
    private fun searchHtml(vararg urls: String) = urls.mapIndexed { index, url ->
        """<a class="result__a" href="$url">카카오톡 연결 방법 $index</a><a class="result__snippet">카카오톡 공식 연결 방법 API와 연동 범위 제약 및 다른 대안을 확인할 수 있는 공개 자료</a>"""
    }.joinToString("\n")
    private fun client(calls: MutableList<String>, response: (String) -> PublicWebResponse) =
        PublicWebClient(PublicWebTransport { url, _ -> calls += url; response(url) }) { timestamp }
    private fun decision(readUrls: List<String> = emptyList(), query: String = "") = buildJsonObject {
        put("readUrls", JsonArray(readUrls.map(::JsonPrimitive))); put("nextQuery", query)
    }.toString()
    private fun workflow(client: PublicWebClient, decide: suspend (String, Boolean) -> String) =
        LocalResearchWorkflow(client, ResearchActionDecider { input, _, _, repair -> decide(input, repair) }) { timestamp }

    @Test fun emptySearchThenPrematureStopCanRecoverWithADifferentSearch() = runBlocking {
        val calls = mutableListOf<String>()
        val web = client(calls) { url ->
            if (calls.size <= 3) reply(url, "", 503) else reply(url, searchHtml(second))
        }
        var decisions = 0
        val packet = workflow(web) { input, _ ->
            val context = Json.parseToJsonElement(input).jsonObject
            assertTrue(context.getValue("observedLimitations").jsonArray.isNotEmpty())
            if (++decisions == 1) decision() else {
                assertTrue(context.containsKey("hostFeedback"))
                decision(query = "카카오톡 공식 연동 범위")
            }
        }.gather(plan(), chosen)
        assertEquals(2, decisions)
        assertEquals(listOf(false, true), packet.steps.map { it.verified })
        assertTrue(second in packet.observedUrls)
    }

    @Test fun sourceEvidenceChangesTheNextModelSelectedSearchAndRead() = runBlocking {
        val calls = mutableListOf<String>()
        var searches = 0
        val web = client(calls) { url ->
            when {
                isSearch(url) -> reply(url, searchHtml(if (searches++ == 0) first else second))
                url == first -> reply(url, "<title>카카오톡 연결 방법</title><p>알림 연동의 지원 범위는 이 원문에서 확인됩니다.</p>")
                else -> reply(url, "<title>카카오톡 API 제약</title><p>과거 대화 이력은 이 원문에서 따로 확인해야 합니다.</p>")
            }
        }
        var choices = 0
        val packet = workflow(web) { input, repair ->
            assertFalse(repair)
            val value = Json.parseToJsonElement(input).jsonObject
            assertEquals("카카오톡", value.getValue("target").jsonPrimitive.content)
            assertTrue(value.getValue("constraints").jsonArray.any { it.jsonPrimitive.content == "내보내기 안내 제외" })
            if (choices++ == 0) decision(listOf(first), "카카오톡 연동 범위 제약")
            else {
                assertTrue(value.getValue("sources").jsonArray.any { source ->
                    source.jsonObject["originalPageFetched"]?.jsonPrimitive?.boolean == true
                })
                assertTrue(input.contains("알림 연동의 지원 범위"))
                decision(listOf(second))
            }
        }.gather(plan(), chosen)
        assertEquals(4, calls.size)
        assertEquals(listOf("search_web", "read_webpage", "search_web", "read_webpage"), packet.steps.map { it.tool })
        assertTrue(packet.steps.all { it.verified })
        assertEquals(setOf(first, second), packet.observedUrls)
        assertEquals(2, packet.sourceCount)
        assertEquals(2, packet.independentDomains)
        assertEquals(2, choices)
        val evidence = Json.parseToJsonElement(packet.json).jsonObject
        assertEquals(4, evidence.getValue("requestCount").jsonPrimitive.int)
        assertTrue(evidence.getValue("sources").jsonArray.all { it.jsonObject.getValue("originalPageFetched").jsonPrimitive.boolean })
    }

    @Test fun guessedHttpCredentialAndDuplicateUrlsNeverReachTheTransport() = runBlocking {
        for (urls in listOf(
            listOf("https://invented.example/not-observed"),
            listOf(first.replace("https:", "http:")),
            listOf("https://user:secret@docs.first.example/connect"),
            listOf(first, "$first#duplicate")
        )) {
            val calls = mutableListOf<String>()
            var choices = 0
            val packet = workflow(client(calls) { url -> reply(url, searchHtml(first)) }) { _, _ ->
                choices++; decision(urls)
            }.gather(plan(), chosen)
            assertEquals(1, calls.size)
            assertTrue(calls.all(::isSearch))
            assertEquals(2, choices)
            assertTrue(packet.partial)
            assertEquals(setOf(first), packet.observedUrls)
            assertTrue(packet.steps.none { it.tool == "read_webpage" })
        }
    }

    @Test fun alreadyReadSourcesCannotBeRepeatedInTheNextRound() = runBlocking {
        val calls = mutableListOf<String>()
        var choices = 0
        val web = client(calls) { url -> reply(url, if (isSearch(url)) searchHtml(first) else "<p>카카오톡 연결 원문</p>") }
        val packet = workflow(web) { _, _ -> choices++; decision(listOf(first)) }.gather(plan(), chosen)
        assertEquals(2, calls.size)
        assertEquals(1, calls.count { it == first })
        assertEquals(3, choices)
        assertTrue(packet.partial)
    }

    @Test fun providerFallbackAttemptsAndReadsShareOneSixRequestBudget() = runBlocking {
        val calls = mutableListOf<String>()
        val rss = """<rss><channel><item><title>카카오톡 연결 방법</title><link>$first</link><description>카카오톡 API 연결 범위</description></item><item><title>카카오톡 연결 제약</title><link>$second</link><description>카카오톡 지원 연결 범위</description></item></channel></rss>"""
        val web = client(calls) { url ->
            when (URI(url).host) {
                "lite.duckduckgo.com", "search.yahoo.com" -> reply(url, "", 202)
                "www.bing.com" -> reply(url, rss)
                else -> reply(url, "<p>카카오톡 연결 원문</p>")
            }
        }
        var choices = 0
        val packet = workflow(web) { _, _ -> choices++; decision(listOf(first, second), "카카오톡 다른 연결 대안") }.gather(plan(), chosen)
        assertEquals(6, calls.size)
        assertEquals(4, calls.count(::isSearch))
        assertEquals(2, calls.count { !isSearch(it) })
        assertEquals(1, choices)
        assertTrue(packet.partial)
        assertEquals(6, Json.parseToJsonElement(packet.json).jsonObject.getValue("requestCount").jsonPrimitive.int)
    }

    @Test fun invalidModelOutputGetsOneRepairAndNoHeuristicReplacement() = runBlocking {
        val calls = mutableListOf<String>()
        val repairFlags = mutableListOf<Boolean>()
        val web = client(calls) { url -> reply(url, if (isSearch(url)) searchHtml(first) else "<p>카카오톡 연결 근거</p>") }
        val packet = workflow(web) { _, repair ->
            repairFlags += repair
            when (repairFlags.size) { 1 -> "read the first page"; 2 -> decision(listOf(first)); else -> decision() }
        }.gather(plan(), chosen)
        assertEquals(listOf(false, true, false), repairFlags)
        assertEquals(2, calls.size)
        assertFalse(packet.partial)
        assertEquals(1, Json.parseToJsonElement(packet.json).jsonObject.getValue("decisionRepairs").jsonPrimitive.int)
    }

    @Test fun malformedDecisionShapeAndDuplicateFieldsStopAfterOneRepair() = runBlocking {
        for (raw in listOf(
            """{"readUrls":[],"nextQuery":"","execute":true}""",
            """{"readUrls":"$first","nextQuery":""}""",
            """{"readUrls":[],"nextQuery":"","nextQuery":"카카오톡 다른 방법"}"""
        )) {
            val calls = mutableListOf<String>()
            var choices = 0
            val packet = workflow(client(calls) { url -> reply(url, searchHtml(first)) }) { _, _ -> choices++; raw }.gather(plan(), chosen)
            assertEquals(1, calls.size)
            assertEquals(2, choices)
            assertTrue(packet.partial)
        }
    }

    @Test fun evidenceRetainsFetchProvenanceAndStaysWithinTheContextBudget() = runBlocking {
        val calls = mutableListOf<String>()
        var choices = 0
        val web = client(calls) { url -> reply(url, if (isSearch(url)) searchHtml(first, second) else "<title>카카오톡 연결</title><p>${"합성 공개 근거 ".repeat(5000)}</p>") }
        val packet = workflow(web) { _, _ -> if (choices++ == 0) decision(listOf(first, second)) else decision() }.gather(plan(), chosen)
        assertTrue(packet.json.length <= 10_000)
        val sources = Json.parseToJsonElement(packet.json).jsonObject.getValue("sources").jsonArray
        assertEquals(2, sources.size)
        sources.forEach { element ->
            val source = element.jsonObject
            assertTrue(source.getValue("originalPageFetched").jsonPrimitive.boolean)
            assertEquals(timestamp.toString(), source.getValue("pageFetchedAt").jsonPrimitive.content)
            assertTrue(source.getValue("excerpt").jsonPrimitive.content.length <= 1200)
            assertTrue(source.getValue("untrusted").jsonPrimitive.boolean)
        }
    }

    @Test fun failedPageReadRemainsASnippetAndDoesNotBecomeVerifiedOriginalEvidence() = runBlocking {
        val calls = mutableListOf<String>()
        var choices = 0
        val web = client(calls) { url -> if (isSearch(url)) reply(url, searchHtml(first)) else reply(url, "blocked", 403) }
        val packet = workflow(web) { _, _ -> if (choices++ == 0) decision(listOf(first)) else decision() }.gather(plan(), chosen)
        assertTrue(packet.partial)
        assertFalse(packet.steps.single { it.tool == "read_webpage" }.verified)
        val source = Json.parseToJsonElement(packet.json).jsonObject.getValue("sources").jsonArray.single().jsonObject
        assertFalse(source.getValue("originalPageFetched").jsonPrimitive.boolean)
        assertEquals("search_snippet", source.getValue("kind").jsonPrimitive.content)
        assertTrue(source.getValue("pageFetchAttempted").jsonPrimitive.boolean)
    }

    @Test fun aNonpublicOrPrivatePlanIsRejectedBeforeNetworkOrModelUse() = runBlocking {
        val calls = mutableListOf<String>()
        var choices = 0
        val research = workflow(client(calls) { url -> reply(url, "") }) { _, _ -> choices++; decision() }
        for (request in listOf(
            plan().copy(usesPrivateData = true),
            plan().copy(action = SemanticAction.ANSWER, publicQuery = "", requiredTools = emptySet())
        )) {
            try { research.gather(request, chosen); fail("Private/nonpublic plans cannot start research") }
            catch (_: IllegalArgumentException) { }
        }
        assertTrue(calls.isEmpty())
        assertEquals(0, choices)
    }

    @Test fun cancellationPropagatesWithoutARepairOrFurtherRequests() = runBlocking {
        val calls = mutableListOf<String>()
        var choices = 0
        val research = workflow(client(calls) { url -> reply(url, searchHtml(first)) }) { _, _ -> choices++; throw CancellationException("synthetic cancellation") }
        try { research.gather(plan(), chosen); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertEquals(1, choices)
        assertEquals(1, calls.size)
    }

    @Test fun disabledPageReadsCannotBeReenabledByTheResearchModel() = runBlocking {
        val calls = mutableListOf<String>()
        var choices = 0
        val research = workflow(client(calls) { url -> reply(url, searchHtml(first)) }) { input, _ ->
            choices++
            val value = Json.parseToJsonElement(input).jsonObject
            assertEquals(listOf("search_web"), value.getValue("availableTools").jsonArray.map { it.jsonPrimitive.content })
            assertTrue(value.getValue("readableObservedUrls").jsonArray.isEmpty())
            // An adversarial or mistaken model still knows the URL from the search evidence.
            decision(listOf(first))
        }
        val packet = research.gather(plan(), chosen, availableTools = setOf("search_web"))
        assertEquals(1, calls.size)
        assertTrue(calls.all(::isSearch))
        assertEquals(2, choices)
        assertTrue(packet.partial)
        assertEquals(setOf(first), packet.observedUrls)
        assertTrue(packet.steps.none { it.tool == "read_webpage" })
        try { research.gather(plan(), chosen, availableTools = emptySet()); fail("A disabled search cannot start") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, calls.size)
        assertEquals(2, choices)
    }
}
