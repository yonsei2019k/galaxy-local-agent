package com.user.localagent.core

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Exports the real conversation policy for the PC evaluation loop (pc-worker/eval/agent_eval.py), so
 * the loop always tests the prompts the app actually sends. {{NOW}} is filled in by the loop.
 */
class EvalPromptExportTest {
    @Test fun exportsCurrentPolicyForLocalVerificationTools(){
        val tools=ToolUsePolicy.verificationTools
        val out=buildJsonObject {
            put("tools",JsonArray(tools.sorted().map(::JsonPrimitive)))
            put("policy",AssistantPolicy.build(tools,"{{NOW}}"))
            put("catalog",ToolUsePolicy.compactCatalog(tools))
            put("afterToolSuffix","검증된 결과로 답하거나 다음 필요한 도구를 요청하라. PREPARED는 작성 화면·전송 요청 단계이며 외부 작업 완료가 아니다. FAILED·UNVERIFIED를 성공이라고 말하지 마라. 같은 인자로 쓰기 작업을 반복하지 마라.")
            put("gateSystem",ToolUsePolicy.gateSystem(tools))
            put("turnReminder",ToolUsePolicy.TURN_REMINDER)
            put("gateSchema",AutonomousReviewPolicy.schema(tools))
        }
        val file=File("build/eval/prompts.json").apply{parentFile.mkdirs();writeText(out.toString())}
        assertTrue(file.length()>500)
        assertTrue(out.getValue("policy").jsonPrimitive.content.contains("{{NOW}}"))
    }
}
