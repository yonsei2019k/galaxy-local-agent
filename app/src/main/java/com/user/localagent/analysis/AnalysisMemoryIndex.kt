package com.user.localagent.analysis

import com.user.localagent.core.PrivateEvidenceKind
import com.user.localagent.data.KnowledgeClaim
import com.user.localagent.data.NoteRecord
import kotlinx.serialization.json.*

/** Project findings stay local and retain the analysis ID needed to retrieve verified source evidence. */
internal object AnalysisMemoryIndex {
    private const val FINDING_KIND = "analysis_finding"
    private val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private val sha = Regex("[a-f0-9]{64}")

    fun marker(stepId: String) = "analysis_memory_v1.$stepId"

    fun scopedSubject(scope: String, analysisId: String): String {
        val subject = "analysis.$analysisId"
        return if (scope == "owner") subject else "$scope|$subject"
    }

    fun parentId(stepBody: String): String? = objectOrNull(stepBody)
        ?.string("analysisId")?.takeIf(uuid::matches)

    fun findings(step: NoteRecord, root: NoteRecord, scope: String): List<KnowledgeClaim> {
        val metadata = objectOrNull(root.body) ?: return emptyList()
        val entry = objectOrNull(step.body) ?: return emptyList()
        val source = metadata["source"] as? JsonObject ?: return emptyList()
        val kind = PrivateEvidenceKind.entries.firstOrNull { it.name == source.string("kind") } ?: return emptyList()
        val index = (entry["index"] as? JsonPrimitive)?.intOrNull ?: return emptyList()
        val count = (metadata["stepCount"] as? JsonPrimitive)?.intOrNull ?: return emptyList()
        if (metadata.string("kind") != "private_analysis_checkpoint_v1" ||
            entry.string("kind") != "private_analysis_step_v1" ||
            metadata.string("id") != root.id || entry.string("analysisId") != root.id ||
            entry.string("scopeId") != scope || metadata.string("scopeId") != scope || source.string("scopeId") != scope ||
            index !in 0 until count || count > 10_000 ||
            (index == count - 1 && metadata.string("lastId") != step.id)) return emptyList()

        val records = (entry["result"] as? JsonObject)?.get("findings") as? JsonArray ?: return emptyList()
        if (records.size > 4) return emptyList()
        return records.mapNotNull { item ->
            val finding = item as? JsonObject ?: return@mapNotNull null
            val findingId = finding.string("id")?.takeIf(sha::matches) ?: return@mapNotNull null
            val text = finding.string("text")?.trim()?.takeIf { it.length in 1..500 && '\u0000' !in it }
                ?: return@mapNotNull null
            KnowledgeClaim(
                id = "af.${step.id}.$findingId",
                subject = "analysis.${root.id}",
                predicate = "$FINDING_KIND.$findingId",
                value = text,
                status = "observed",
                sourceKind = FINDING_KIND,
                sourceId = step.id,
                updatedAt = step.updatedAt,
                tags = listOf(FINDING_KIND, kind.name.lowercase()),
            )
        }
    }

    private fun objectOrNull(text: String): JsonObject? =
        runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
