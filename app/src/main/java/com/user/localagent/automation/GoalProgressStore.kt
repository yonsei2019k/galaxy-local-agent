package com.user.localagent.automation

import com.user.localagent.connections.ConnectionStore
import com.user.localagent.core.GoalProgressLedger
import com.user.localagent.core.GoalProgressLimits
import com.user.localagent.core.GoalProgressToolCatalog
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolResult
import com.user.localagent.core.VerificationState
import com.user.localagent.core.apply
import com.user.localagent.core.validate
import com.user.localagent.core.summary
import kotlinx.serialization.json.*

internal class GoalProgressStore(private val store: ConnectionStore) {
    fun hasMediaItem(key:String,itemId:String):Boolean = synchronized(lock) {
        store.read("goal.progress.v1.$key")?.let {
            GoalProgressLimits.json.decodeFromString<GoalProgressLedger>(it).validate().items.any { item ->
                item.id==itemId && item.kind==com.user.localagent.core.LedgerKind.LECTURE &&
                    item.status!=com.user.localagent.core.LedgerStatus.DONE
            }
        } ?: false
    }

    fun summary(key: String): String? = synchronized(lock) {
        store.read("goal.progress.v1.$key")?.let {
            GoalProgressLimits.json.decodeFromString<GoalProgressLedger>(it).validate().summary().take(320)
        }
    }

    fun execute(key: String, goalId: String, goalRevision: Long, call: ToolCall, evidence: Map<String,String>): ToolResult = synchronized(lock) {
        val slot = "goal.progress.v1.$key"
        val json = GoalProgressLimits.json
        val current = store.read(slot)?.let { json.decodeFromString<GoalProgressLedger>(it).validate() }
            ?: GoalProgressLedger(goalId)
        check(current.goalId == goalId)
        val ledger = if (call.name == GoalProgressToolCatalog.UPDATE) {
            val revision = call.arguments.getValue("expectedUpdatedAt").jsonPrimitive.content
            require(revision.all(Char::isDigit))
            require(revision.toLongOrNull() == current.updatedAt) { "작업 장부가 바뀌었어요. 다시 읽어 주세요." }
            current.apply(GoalProgressToolCatalog.parseOps(call), evidence.keys,
                maxOf(System.currentTimeMillis(), current.updatedAt + 1)).also {
                val updated = json.encodeToString(GoalProgressLedger.serializer(), it)
                store.write(slot, updated)
                check(store.read(slot) == updated)
            }
        } else current
        val body = buildJsonObject {
            put("goalId", goalId)
            put("goalRevision", goalRevision.toString())
            put("expectedUpdatedAt", ledger.updatedAt.toString())
            put("ledger", json.encodeToJsonElement(GoalProgressLedger.serializer(), ledger))
            put("observedActionAudits", buildJsonObject { evidence.forEach { (id, tool) -> put(id, tool) } })
        }
        ToolResult(call.id, true, body.toString(), VerificationState.VERIFIED, "작업 장부 확인 · 전체 목표 완료 증거 아님")
    }

    private companion object { val lock = Any() }
}
