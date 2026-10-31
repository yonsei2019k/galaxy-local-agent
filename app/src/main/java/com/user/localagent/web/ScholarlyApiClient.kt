package com.user.localagent.web

import com.user.localagent.core.EvidenceToolCatalog
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.util.Locale

/** Direct REST readers. No cookies, account data, arbitrary URLs or executable MCP dependency. */
class ScholarlyApiClient(
    private val transport: PublicWebTransport = PinnedPublicHttpsTransport(),
    private val now: () -> Instant = { Instant.now() },
    private val gate: ScholarlyRateGate = ScholarlyRateGate.shared,
) {
    suspend fun execute(call: ToolCall): ToolResult {
        try {
            EvidenceToolCatalog.validate(call)
            val content = when (call.name) {
                "search_publications" -> searchPublications(call.string("query")!!,
                    call.arguments["limit"]?.jsonPrimitive?.int ?: 5, call.string("originalQuery") ?: call.string("query")!!)
                "verify_reference" -> verifyReference(call.string("doi"), call.string("citation"),
                    call.arguments["limit"]?.jsonPrimitive?.int ?: 3)
                else -> error("지원하지 않는 공개 서지 도구입니다.")
            }
            val success = content["success"]?.jsonPrimitive?.booleanOrNull == true
            return ToolResult(call.id, success, content.toString(),
                if (success) VerificationState.VERIFIED else VerificationState.FAILED,
                if (success) "공개 서지 응답 확인 · 논문 결론·인용 일치·연구 품질의 보증 아님" else "공개 서지 응답을 확인하지 못함")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return ToolResult(call.id, false, failure("input", "invalid_input", 0).toString(),
                VerificationState.FAILED, "공개 서지 요청 인자 검증 실패")
        }
    }

    suspend fun searchPublications(query: String, limit: Int = 5, originalQuery: String = query): JsonObject {
        require(limit in 1..5)
        val clean = PublicWebClient.validateQuery(query, maxChars = 500)
        val original = PublicWebClient.validateQuery(originalQuery, maxChars = 500)
        val url = "https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=${encode(clean)}&format=json&resultType=core&pageSize=$limit"
        val fetched = fetch("EuropePMC", url, "www.ebi.ac.uk")
        return parseOrFailure("EuropePMC", fetched) { body ->
            val hitCount = body["hitCount"]?.jsonPrimitive?.longOrNull ?: error("Missing hitCount")
            require(hitCount >= 0)
            val results = (body["resultList"] as? JsonObject)?.get("result") as? JsonArray ?: error("Missing result list")
            val sources = results.take(limit).map { raw -> publication(raw as? JsonObject ?: error("Invalid publication")) }
            require(hitCount == 0L || sources.isNotEmpty())
            report("EuropePMC", sources, fetched.requestCount) {
                put("query", original); put("transmittedQuery", clean); put("hitCount", hitCount)
                put("resultType", "core"); put("scope", "biomedical_and_life_science_publications")
                put("abstractsAreExcerpts", true)
                put("licenseNotice", "Check each item license; open-access status alone does not grant unrestricted reuse.")
            }
        }.let { report -> JsonObject(report + mapOf("query" to JsonPrimitive(original), "transmittedQuery" to JsonPrimitive(clean))) }
    }

    suspend fun verifyReference(doi: String? = null, citation: String? = null, limit: Int = 3): JsonObject {
        require((doi == null) xor (citation == null)) { "doi 또는 citation 중 하나가 필요합니다." }
        require(limit in 1..3)
        val identifier = if (doi != null) normalizeDoi(doi) else runCatching { normalizeDoi(citation!!) }.getOrNull()
        val query = if (identifier == null) PublicWebClient.validateQuery(citation!!, maxChars = 500) else null
        val url = if (identifier != null) "https://api.crossref.org/works/${encode(identifier)}"
            else "https://api.crossref.org/works?query.bibliographic=${encode(query!!)}&rows=$limit"
        val fetched = fetch("Crossref", url, "api.crossref.org")
        if (identifier != null && fetched.response?.statusCode == 404) {
            return withLookup(report("Crossref", emptyList(), fetched.requestCount, "not_found") {
                put("matchType", "not_found"); put("doi", identifier)
                put("meaning", "No record was found in Crossref at this time. Other registration agencies, delayed deposits or input errors remain possible; this does not prove fabrication.")
            }, fetched.response.finalUrl)
        }
        return parseOrFailure("Crossref", fetched) { body ->
            require(body.string("status") == "ok")
            val message = body["message"] as? JsonObject ?: error("Missing Crossref message")
            val records = if (identifier != null) listOf(message) else
                (message["items"] as? JsonArray ?: error("Missing Crossref items")).take(limit).map {
                    it as? JsonObject ?: error("Invalid Crossref item")
                }
            if (identifier != null) require(normalizeDoi(message.string("DOI") ?: error("Missing DOI")) == identifier) {
                "DOI metadata mismatch"
            }
            val sources = records.map(::reference)
            report("Crossref", sources, fetched.requestCount, if (sources.isEmpty()) "not_found" else "ok") {
                put("matchType", if (sources.isEmpty()) "not_found" else if (identifier != null) "exact_doi" else "candidate_match")
                if (identifier != null) put("doi", identifier) else put("transmittedQuery", query!!)
                put("exactCitationMatch", false); put("requiresAssessment", true)
                put("meaning", if (identifier != null) "Identifier lookup found deposited metadata; compare title, authors, date and claim separately."
                    else "Bibliographic search candidates are not automatically confirmed citations; assess the metadata against the stated reference.")
                put("updatesCoverageComplete", false)
                put("licenseNotice", "Crossref bibliographic metadata is generally reusable; abstract text can carry separate rights. This tool does not return Crossref abstracts.")
            }
        }
    }

    private suspend fun fetch(provider: String, url: String, expectedHost: String): ScholarlyFetch =
        gate.fetch(provider) {
            val response = transport.get(url, MAX_BYTES)
            val final = PublicWebSafety.requireHttps(response.finalUrl)
            require(final.host.equals(expectedHost, true)) { "Unexpected scholarly endpoint" }
            require(response.body.size <= MAX_BYTES)
            response
        }

    private fun parseOrFailure(provider: String, fetched: ScholarlyFetch, parse: (JsonObject) -> JsonObject): JsonObject {
        if (fetched.cooldownSeconds > 0) return failure(provider, "rate_limited", fetched.requestCount, fetched.cooldownSeconds)
        val response = fetched.response ?: return failure(provider, "unavailable", fetched.requestCount)
        if (response.statusCode == 429) return failure(provider, "rate_limited", fetched.requestCount, 30)
        if (response.statusCode != 200) return failure(provider, "unavailable", fetched.requestCount,
            if (response.statusCode == 503) 30 else 0)
        return try {
            val type = response.contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
            require(type == "application/json" || type.endsWith("+json"))
            val body = Json.parseToJsonElement(response.body.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
                ?: error("Invalid JSON object")
            withLookup(parse(body), response.finalUrl)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failure(provider, "unavailable", fetched.requestCount)
        }
    }

    private fun withLookup(report: JsonObject, lookupUrl: String): JsonObject {
        val observed = (report["observedUrls"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        }
        return JsonObject(report + mapOf("lookupUrl" to JsonPrimitive(lookupUrl),
            "observedUrls" to JsonArray((observed + lookupUrl).distinct().map(::JsonPrimitive))))
    }

    private fun publication(raw: JsonObject): JsonObject {
        val id = raw.string("id")?.takeIf { it.isNotBlank() && it.length <= 150 } ?: error("Missing publication identifier")
        val source = raw.string("source")?.takeIf { it.matches(Regex("[A-Z]{2,8}")) } ?: error("Invalid publication source")
        val title = plain(raw.string("title").orEmpty(), 400)
        require(title.isNotEmpty())
        val fullAbstract = plain(raw.string("abstractText").orEmpty(), MAX_BYTES)
        val abstract = plain(fullAbstract, 1200)
        val journalInfo = raw["journalInfo"] as? JsonObject
        val journal = (journalInfo?.get("journal") as? JsonObject)?.string("title")
        val doi = raw.string("doi")?.let { runCatching { normalizeDoi(it) }.getOrNull() }
        val fullTextLinks = ((raw["fullTextUrlList"] as? JsonObject)?.get("fullTextUrl") as? JsonArray)
            .orEmpty().take(4).mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val url = obj.string("url")?.let(::safeUrl) ?: return@mapNotNull null
                buildJsonObject {
                    put("url", url); put("availability", plain(obj.string("availability").orEmpty(), 80))
                    put("site", plain(obj.string("site").orEmpty(), 100))
                }
            }
        return buildJsonObject {
            put("url", "https://europepmc.org/article/${encode(source)}/${encode(id)}")
            put("title", title); put("id", id); put("source", source)
            raw.string("pmid")?.takeIf { it.matches(Regex("[0-9]{1,15}")) }?.let { put("pmid", it) }
            raw.string("pmcid")?.takeIf { it.matches(Regex("PMC[0-9]{1,15}")) }?.let { put("pmcid", it) }
            doi?.let { put("doi", it); put("doiUrl", doiUrl(it)) }
            put("authors", plain(raw.string("authorString").orEmpty(), 250))
            put("date", plain(raw.string("firstPublicationDate") ?: raw.string("pubYear").orEmpty(), 40))
            put("journal", plain(journal ?: raw.string("journalTitle").orEmpty(), 180))
            put("abstractExcerpt", abstract); put("abstractTruncated", fullAbstract.length > abstract.length)
            put("publicationTypes", buildJsonArray {
                ((raw["pubTypeList"] as? JsonObject)?.get("pubType") as? JsonArray).orEmpty().take(8).forEach {
                    (it as? JsonPrimitive)?.contentOrNull?.let { type -> add(plain(type, 100)) }
                }
            })
            put("openAccess", raw.string("isOpenAccess")?.takeIf { it in setOf("Y", "N") } ?: "unknown")
            put("license", plain(raw.string("license").orEmpty(), 160))
            put("fullTextLinks", JsonArray(fullTextLinks))
            put("fullTextFetched", false); put("peerReviewAssessed", false)
        }
    }

    private fun reference(raw: JsonObject): JsonObject {
        val doi = normalizeDoi(raw.string("DOI") ?: error("Missing DOI"))
        val title = ((raw["title"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull.orEmpty()
        require(title.isNotBlank())
        return buildJsonObject {
            put("url", doiUrl(doi)); put("doi", doi); put("title", plain(title, 400))
            put("authors", buildJsonArray { (raw["author"] as? JsonArray).orEmpty().take(10).forEach { item ->
                (item as? JsonObject)?.let { author ->
                    add(plain(listOfNotNull(author.string("given"), author.string("family"), author.string("name")).joinToString(" "), 100))
                }
            } })
            put("date", referenceDate(raw)); put("type", plain(raw.string("type").orEmpty(), 80))
            put("publisher", plain(raw.string("publisher").orEmpty(), 180))
            put("containerTitle", plain(((raw["container-title"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull.orEmpty(), 180))
            (raw["score"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }?.let { put("providerScore", it) }
            put("updates", buildJsonArray {
                listOf("update-to", "updated-by").forEach { field ->
                    (raw[field] as? JsonArray).orEmpty().take(4).forEach { item ->
                        (item as? JsonObject)?.let { update -> add(buildJsonObject {
                            put("relation", field); put("type", plain(update.string("type").orEmpty(), 80))
                            put("label", plain(update.string("label").orEmpty(), 150))
                            update.string("DOI")?.let { runCatching { normalizeDoi(it) }.getOrNull() }?.let { put("doi", it); put("url", doiUrl(it)) }
                        }) }
                    }
                }
            })
            put("licenses", buildJsonArray { (raw["license"] as? JsonArray).orEmpty().take(3).forEach { item ->
                (item as? JsonObject)?.string("URL")?.let(::safeUrl)?.let { add(it) }
            } })
        }
    }

    private fun referenceDate(raw: JsonObject): String {
        for (key in listOf("published", "published-print", "published-online", "issued")) {
            val parts = (((raw[key] as? JsonObject)?.get("date-parts") as? JsonArray)?.firstOrNull() as? JsonArray)
                ?.take(3)?.map { (it as? JsonPrimitive)?.intOrNull ?: return@map -1 } ?: continue
            if (parts.isNotEmpty() && parts[0] in 1..9999 && (parts.size < 2 || parts[1] in 1..12) &&
                (parts.size < 3 || parts[2] in 1..31)) return parts.mapIndexed { index, value ->
                value.toString().padStart(if (index == 0) 4 else 2, '0')
            }.joinToString("-")
        }
        return ""
    }

    private fun report(provider: String, sources: List<JsonObject>, requestCount: Int, status: String = "ok",
        extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("success", true); put("status", status); put("provider", provider); put("fetchedAt", now().toString())
        put("requestCount", requestCount); put("untrustedSourceData", true)
        put("sources", JsonArray(sources)); put("sourceCount", sources.size)
        put("observedUrls", buildJsonArray { sources.mapNotNull { it.string("url") }.distinct().forEach { add(it) } })
        extra()
    }

    private fun failure(provider: String, status: String, requestCount: Int, cooldown: Long = 0): JsonObject = buildJsonObject {
        put("success", false); put("status", status); put("provider", provider); put("fetchedAt", now().toString())
        put("requestCount", requestCount); put("sources", JsonArray(emptyList())); put("observedUrls", JsonArray(emptyList()))
        put("untrustedSourceData", true)
        if (cooldown > 0) {
            put("retryAfterSeconds", cooldown)
            put("retryAfterSource", "local_conservative_cooldown_not_response_header")
        }
        put("automaticRetry", false)
        put("meaning", "No verified scholarly result is available from this attempt. Do not infer that a reference or claim is false.")
    }

    companion object {
        const val MAX_BYTES = 512 * 1024
        fun normalizeDoi(raw: String): String {
            require(raw.length <= 500)
            var value = raw.trim()
            if (value.startsWith("doi:", true)) value = value.substring(4).trim()
            else if (value.startsWith("https://", true) || value.startsWith("http://", true)) {
                val uri = URI(value)
                require(uri.host?.lowercase(Locale.ROOT) in setOf("doi.org", "dx.doi.org") &&
                    uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null)
                value = uri.path.removePrefix("/")
            }
            require(value.length in 7..250 && value.matches(Regex("10\\.[0-9]{4,9}/[^\\s]+")) &&
                value.none { it.code < 32 || it.code == 127 || it == '\\' }) { "DOI 형식을 확인해주세요." }
            return value.lowercase(Locale.ROOT)
        }
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        private fun doiUrl(doi: String) = "https://doi.org/" + encode(doi).replace("%2F", "/")
        private fun safeUrl(raw: String): String? = runCatching { PublicWebSafety.requireHttps(raw).toString() }
            .getOrNull()?.takeIf { it.length <= 1500 }
        private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
        private fun ToolCall.string(key: String) = (arguments[key] as? JsonPrimitive)?.contentOrNull
        private fun plain(raw: String, limit: Int): String {
            val text = raw.replace(Regex("(?s)<[^>]{0,8192}>"), " ")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&nbsp;", " ")
                .replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim()
            var end = minOf(limit, text.length)
            if (end < text.length && end > 0 && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            return text.substring(0, end)
        }
    }
}

data class ScholarlyFetch(val response: PublicWebResponse?, val requestCount: Int, val cooldownSeconds: Long = 0)

/** Conservative per-provider serial 1/s. 429/503 starts a 30s cooldown with no automatic retry. */
class ScholarlyRateGate internal constructor(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private val lastStarted = mutableMapOf<String, Long>()
    private val blockedUntil = mutableMapOf<String, Long>()

    suspend fun fetch(provider: String, request: suspend () -> PublicWebResponse): ScholarlyFetch = mutex.withLock {
        currentCoroutineContext().ensureActive()
        val clock = clockMillis()
        val blocked = blockedUntil[provider]?.let { it - clock } ?: 0L
        if (blocked > 0) return@withLock ScholarlyFetch(null, 0, (blocked + 999) / 1000)
        lastStarted[provider]?.let { previous ->
            val wait = 1000 - (clockMillis() - previous)
            if (wait > 0) pause(wait.coerceAtMost(1000))
        }
        currentCoroutineContext().ensureActive()
        lastStarted[provider] = clockMillis()
        try {
            val response = withTimeout(35_000) { request() }
            if (response.statusCode in setOf(429, 503)) blockedUntil[provider] = clockMillis() + 30_000
            ScholarlyFetch(response, 1)
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            ScholarlyFetch(null, 1)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            ScholarlyFetch(null, 1)
        }
    }

    companion object { val shared = ScholarlyRateGate() }
}
