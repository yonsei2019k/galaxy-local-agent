package com.user.localagent.web

import com.user.localagent.core.ToolCall
import com.user.localagent.core.VerificationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Synthetic responses only. Tests do not send private data, contact APIs or claim phone execution. */
class ScholarlyApiClientTest {
    private val timestamp = Instant.parse("2030-01-01T00:00:00Z")
    private fun response(url: String, body: String, code: Int = 200, type: String = "application/json") =
        PublicWebResponse(code, url, type, body.toByteArray())
    private fun client(transport: PublicWebTransport): ScholarlyApiClient {
        var clock = 1000L
        return ScholarlyApiClient(transport, { timestamp }, ScholarlyRateGate({ clock }, { clock += it }))
    }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun call(name: String, vararg arguments: Pair<String, JsonElement>) =
        ToolCall("synthetic-call", name, JsonObject(mapOf(*arguments)))
    private fun record(abstract: String = "<h4>Background</h4>A public synthetic abstract.") = buildJsonObject {
        put("id", "12345678"); put("source", "MED"); put("pmid", "12345678"); put("pmcid", "PMC1234567")
        put("doi", "10.1234/example"); put("title", "A synthetic publication"); put("authorString", "Kim A, Lee B")
        put("firstPublicationDate", "2026-01-12"); put("abstractText", abstract)
        put("license", "cc by-nc-nd"); put("isOpenAccess", "Y")
        put("pubTypeList", buildJsonObject { put("pubType", buildJsonArray { add("Preprint") }) })
        put("fullTextUrlList", buildJsonObject { put("fullTextUrl", buildJsonArray {
            add(buildJsonObject { put("url", "https://example.com/article"); put("availability", "Open access") })
            add(buildJsonObject { put("url", "https://127.0.0.1/private"); put("availability", "Open access") })
        }) })
    }
    private fun publications(vararg records: JsonObject, hitCount: Int = records.size) = buildJsonObject {
        put("hitCount", hitCount); put("resultList", buildJsonObject { put("result", JsonArray(records.toList())) })
    }.toString()
    private fun reference(doi: String = "10.1234/example") = buildJsonObject {
        put("DOI", doi); put("title", buildJsonArray { add("Synthetic reference") })
        put("type", "journal-article"); put("publisher", "Synthetic publisher")
        put("author", buildJsonArray { add(buildJsonObject { put("given", "A"); put("family", "Kim") }) })
        put("published", buildJsonObject { put("date-parts", buildJsonArray { add(buildJsonArray { add(2026); add(2); add(3) }) }) })
        put("update-to", buildJsonArray { add(buildJsonObject { put("DOI", "10.1234/earlier"); put("type", "retraction"); put("label", "Retraction") }) })
    }
    private fun crossref(item: JsonElement) = buildJsonObject { put("status", "ok"); put("message", item) }.toString()

    @Test fun searchReturnsProviderIdentifiersAbstractLicenseAndBothPublicQueries() = runBlocking {
        var requested = ""
        val client = client { url, max ->
            requested = url; assertEquals(512 * 1024, max)
            response(url, publications(record("가".repeat(1199) + "🛰️".repeat(1000))))
        }
        val report = client.searchPublications("shift work sleep", 3, "교대근무 수면")
        assertTrue(requested.contains("query=shift%20work%20sleep"))
        assertTrue(requested.contains("resultType=core&pageSize=3"))
        assertEquals("교대근무 수면", report.text("query"))
        assertEquals("shift work sleep", report.text("transmittedQuery"))
        assertEquals(timestamp.toString(), report.text("fetchedAt"))
        val source = report.getValue("sources").jsonArray.single().jsonObject
        assertEquals("cc by-nc-nd", source.text("license")); assertEquals("Y", source.text("openAccess"))
        assertEquals("Preprint", source.getValue("publicationTypes").jsonArray.single().jsonPrimitive.content)
        assertTrue(source.text("abstractExcerpt").length <= 1200)
        assertFalse(source.text("abstractExcerpt").last().isHighSurrogate())
        assertTrue(source.getValue("abstractTruncated").jsonPrimitive.boolean)
        assertFalse(source.getValue("fullTextFetched").jsonPrimitive.boolean)
        assertEquals(1, source.getValue("fullTextLinks").jsonArray.size)
        assertTrue(report.getValue("observedUrls").jsonArray.any { it.jsonPrimitive.content == requested })
        assertTrue(report.getValue("observedUrls").jsonArray.any { it.jsonPrimitive.content == "https://europepmc.org/article/MED/12345678" })
    }

    @Test fun zeroResultsIsVerifiedQueryWithLookupProvenanceAndNoSources() = runBlocking {
        val result = client { url, _ -> response(url, publications()) }.execute(
            call("search_publications", "query" to JsonPrimitive("public synthetic topic")))
        assertTrue(result.success); assertEquals(VerificationState.VERIFIED, result.verification)
        val report = Json.parseToJsonElement(result.content).jsonObject
        assertEquals(0, report.getValue("sourceCount").jsonPrimitive.int)
        assertEquals(1, report.getValue("observedUrls").jsonArray.size)
        assertEquals("ok", report.text("status"))
    }

    @Test fun removingAbstractMarkupDoesNotClaimTheAbstractWasTruncated() = runBlocking {
        val report = client { url, _ -> response(url, publications(record())) }.searchPublications("public topic")
        val source = report.getValue("sources").jsonArray.single().jsonObject
        assertEquals("Background A public synthetic abstract.", source.text("abstractExcerpt"))
        assertFalse(source.getValue("abstractTruncated").jsonPrimitive.boolean)
    }

    @Test fun authorizedPublicQueryUpTo500CharactersIsPreserved() = runBlocking {
        val query = "public synthetic topic ".repeat(20).trim()
        assertTrue(query.length in 301..500)
        val report = client { url, _ -> response(url, publications()) }.execute(
            call("search_publications", "query" to JsonPrimitive(query)))
        assertTrue(report.success)
        assertEquals(query, Json.parseToJsonElement(report.content).jsonObject.text("transmittedQuery"))
    }

    @Test fun unknownToolAndAmbiguousReferenceArgumentsFailBeforeNetwork() = runBlocking {
        var requests = 0
        val client = client { _, _ -> requests++; error("Must not execute") }
        assertFalse(client.execute(call("not_a_tool")).success)
        assertFalse(client.execute(call("verify_reference", "doi" to JsonPrimitive("10.1234/example"),
            "citation" to JsonPrimitive("Synthetic citation"))).success)
        assertEquals(0, requests)
    }

    @Test fun malformedProviderDataDoesNotBecomeAZeroResultClaim() = runBlocking {
        val result = client { url, _ -> response(url, """{"hitCount":20,"resultList":{}}""") }.execute(
            call("search_publications", "query" to JsonPrimitive("public topic")))
        assertFalse(result.success); assertEquals(VerificationState.FAILED, result.verification)
        assertEquals("unavailable", Json.parseToJsonElement(result.content).jsonObject.text("status"))
    }

    @Test fun rateLimitHasNoAutomaticRetryAndCooldownDoesNotContactProvider() = runBlocking {
        var requests = 0
        val client = client { url, _ -> requests++; response(url, "{}", 429) }
        val first = client.searchPublications("public topic")
        val second = client.searchPublications("other public topic")
        assertEquals(1, requests)
        assertEquals("rate_limited", first.text("status")); assertEquals("rate_limited", second.text("status"))
        assertEquals(1, first.getValue("requestCount").jsonPrimitive.int)
        assertEquals(0, second.getValue("requestCount").jsonPrimitive.int)
        assertEquals(30, first.getValue("retryAfterSeconds").jsonPrimitive.int)
        assertFalse(first.getValue("automaticRetry").jsonPrimitive.boolean)
        assertTrue(first.getValue("observedUrls").jsonArray.isEmpty())
    }

    @Test fun serviceUnavailableIsNotNotFoundAndStartsConservativeCooldown() = runBlocking {
        val report = client { url, _ -> response(url, "{}", 503) }.verifyReference(doi = "10.1234/example")
        assertEquals("unavailable", report.text("status"))
        assertFalse(report.getValue("success").jsonPrimitive.boolean)
        assertEquals(30, report.getValue("retryAfterSeconds").jsonPrimitive.int)
    }

    @Test fun missingCrossrefDoiIsVerifiedLookupRatherThanFabricationVerdict() = runBlocking {
        val result = client { url, _ -> response(url, "Resource not found", 404, "text/plain") }.execute(
            call("verify_reference", "doi" to JsonPrimitive("10.1234/missing")))
        assertTrue(result.success); assertEquals(VerificationState.VERIFIED, result.verification)
        val report = Json.parseToJsonElement(result.content).jsonObject
        assertEquals("not_found", report.text("status")); assertEquals("not_found", report.text("matchType"))
        assertTrue(report.text("meaning").contains("does not prove fabrication"))
        assertTrue(report.getValue("sources").jsonArray.isEmpty())
        assertEquals(1, report.getValue("observedUrls").jsonArray.size)
    }

    @Test fun aDoiUrlInCitationUsesIdentifierLookupAndReturnsDepositedUpdateLinks() = runBlocking {
        var requested = ""
        val report = client { url, _ -> requested = url; response(url, crossref(reference())) }
            .verifyReference(citation = "https://doi.org/10.1234/EXAMPLE")
        assertEquals("https://api.crossref.org/works/10.1234%2Fexample", requested)
        assertEquals("exact_doi", report.text("matchType"))
        val source = report.getValue("sources").jsonArray.single().jsonObject
        assertEquals("2026-02-03", source.text("date"))
        assertEquals("retraction", source.getValue("updates").jsonArray.single().jsonObject.text("type"))
        assertFalse(report.getValue("exactCitationMatch").jsonPrimitive.boolean)
        assertFalse(report.getValue("updatesCoverageComplete").jsonPrimitive.boolean)
    }

    @Test fun bibliographicCandidatesAreNotClaimedAsConfirmedCitations() = runBlocking {
        val report = client { url, _ ->
            assertTrue(url.contains("query.bibliographic="))
            response(url, crossref(buildJsonObject { put("items", buildJsonArray { add(reference()) }) }))
        }.verifyReference(citation = "Kim 2026 synthetic reference")
        assertEquals("candidate_match", report.text("matchType"))
        assertTrue(report.getValue("requiresAssessment").jsonPrimitive.boolean)
        assertFalse(report.getValue("exactCitationMatch").jsonPrimitive.boolean)
    }

    @Test fun wrongIdentifierInProviderResponseFailsInsteadOfVerifyingDifferentWork() = runBlocking {
        val report = client { url, _ -> response(url, crossref(reference("10.1234/other"))) }
            .verifyReference(doi = "10.1234/example")
        assertEquals("unavailable", report.text("status"))
        assertFalse(report.getValue("success").jsonPrimitive.boolean)
        assertTrue(report.getValue("observedUrls").jsonArray.isEmpty())
    }

    @Test fun publicQueryBoundaryRejectsObviousPrivateInputsBeforeNetwork() = runBlocking {
        var requests = 0
        val result = client { _, _ -> requests++; error("Must not execute") }.execute(
            call("search_publications", "query" to JsonPrimitive("patient test@example.com work schedule")))
        assertEquals(0, requests); assertFalse(result.success)
        assertEquals("invalid_input", Json.parseToJsonElement(result.content).jsonObject.text("status"))
    }

    @Test fun anEndpointRedirectToAnotherHostIsUnavailable() = runBlocking {
        val report = client { _, _ -> response("https://example.com/pretend", publications(record())) }
            .searchPublications("public topic")
        assertEquals("unavailable", report.text("status"))
    }

    @Test fun cancellationPropagatesInsteadOfBecomingAnApiFailureOrRetry() = runBlocking {
        try {
            client { _, _ -> throw CancellationException("Synthetic cancel") }.searchPublications("public topic")
            fail("Cancellation must propagate")
        } catch (cancel: CancellationException) {
            assertEquals("Synthetic cancel", cancel.message)
        }
    }

    @Test fun rateGateSerializesSameProviderStartsWithoutExtraNetworkAttempts() = runBlocking {
        var clock = 1000L; val starts = mutableListOf<Long>(); val waits = mutableListOf<Long>()
        val gate = ScholarlyRateGate({ clock }, { waits += it; clock += it })
        repeat(3) { gate.fetch("Crossref") { starts += clock; response("https://api.crossref.org/works", "{}") } }
        assertEquals(listOf(1000L, 2000L, 3000L), starts)
        assertEquals(listOf(1000L, 1000L), waits)
    }

    @Test fun doiParsingValidatesIdentifierFormatWithoutNaturalLanguageIntentRules() {
        assertEquals("10.1234/example", ScholarlyApiClient.normalizeDoi("doi:10.1234/EXAMPLE"))
        assertEquals("10.1234/a?b", ScholarlyApiClient.normalizeDoi("https://doi.org/10.1234/a%3Fb"))
        for (invalid in listOf("10.12/foo", "https://doi.org.evil.example/10.1234/foo", "https://doi.org/10.1234/foo?private=1", "10.1234/foo bar")) {
            try { ScholarlyApiClient.normalizeDoi(invalid); fail("Invalid DOI must be rejected") }
            catch (_: IllegalArgumentException) { /* expected */ }
        }
    }
}
