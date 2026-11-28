package com.user.localagent.core

/** Builds chat copy from host observations without quoting provider errors or tool inputs. */
object WorkFailureReport {
    fun describe(code: String, outcomes: List<ObservedToolOutcome>, unknownEffect: Boolean = false): String {
        val last = outcomes.lastOrNull()
        val verified = outcomes.count { it.result.success && it.result.verification == VerificationState.VERIFIED }
        val connectionTimedOut = code.startsWith("PC_BRIDGE_TIMEOUT_")
        val problem = when {
            connectionTimedOut && code.endsWith("GET_PROVIDER") ->
                "PC에서 작업 결과를 보내주길 기다렸는데, 정해진 시간 안에 답이 오지 않았어요."
            connectionTimedOut -> "PC에 요청을 보냈지만 제때 응답을 받지 못했어요."
            code in setOf("planning_pc_busy", "PcBusy", "PcAgentProviderBusy") ->
                "PC가 다른 일을 처리하고 있어서 이번 요청을 진행하지 못했어요."
            code in setOf("planning_pc_required", "PcRequired") -> "이번 작업을 이어가려면 PC 연결이 필요해요."
            code in setOf("planning_pc_unavailable", "PcUnavailable") -> "PC에 연결하지 못해서 작업이 멈췄어요."
            code.startsWith("planning_") && code.endsWith("_timeout") ->
                "어떻게 진행할지 정하는 데 시간이 오래 걸려, 실행 전에 멈췄어요."
            code == "SecurityException" -> "이 단계에 필요한 접근이 허용되지 않아 멈췄어요."
            unknownEffect -> "마지막 동작이 실제로 반영됐는지 확인하지 못했어요."
            code == "FINAL_MISSING_PLAN" -> "어떤 순서로 작업할지 정하지 못해서 다음 단계로 넘어가지 못했어요."
            code == "FINAL_EVIDENCE_INCOMPLETE" ->
                "작업이 끝났다는 응답은 왔지만, 요청하신 결과가 나왔는지 확인할 자료가 부족해요."
            last != null && !last.result.success && ToolInterruptionPolicy.hasNoMutation(last.call.name) ->
                "필요한 정보를 읽으려 했지만 결과를 가져오지 못했어요."
            last != null && last.result.effectNotApplied && !last.result.success ->
                "마지막에 하려던 동작이 적용되지 않은 건 확인했어요. 왜 안 됐는지는 아직 알기 어려워요."
            code == "FINAL_PROVIDER_INCOMPLETE" -> "작업이 끝나기 전에 PC에서 응답을 마쳤어요."
            else -> "작업을 끝내지 못했어요. 지금 확인한 정보만으로는 어디서 문제가 생겼는지 알기 어려워요."
        }
        val progress = if (verified > 0) "앞서 확인한 결과는 작업 기록에 남겨뒀어요."
            else "이번 실행에서 새로 확인한 결과는 없어요."
        val next = when {
            unknownEffect -> "같은 동작을 다시 하기 전에 현재 상태를 먼저 확인해야 해요."
            code in setOf("planning_pc_busy", "PcBusy", "PcAgentProviderBusy") -> "PC의 다른 작업이 끝난 뒤 이어가면 돼요."
            connectionTimedOut || code.startsWith("planning_pc_") || code in setOf("PcRequired", "PcUnavailable") ->
                "PC가 켜져 있고 앱과 연결되어 있는지 확인해 주세요."
            code == "SecurityException" -> "앱이나 사이트에서 이 작업에 필요한 접근 권한을 확인해 주세요."
            else -> "이어가려면 마지막 상태부터 확인해야 해요."
        }
        return "$problem $progress $next"
    }
}
