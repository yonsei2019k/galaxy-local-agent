package com.user.localagent.core

/**
 * PREPARED means the host handed an Intent to another app and cannot see the outcome. Two kinds differ:
 * - Pending effect (SMS/email/calendar compose, alarm, messenger reply): the owner must finish or confirm it there,
 *   so the run stops and the agent may not repeat it.
 * - Screen opening (map, web page, consumer-app search/product/home): nothing was sent or saved. Opening the screen
 *   is the tool's whole effect, so the run may continue, e.g. read the opened screen with screen control.
 */
object PreparedHandoff {
    val screenOpeners:Set<String> = com.user.localagent.core.connections.ConsumerAppCatalog.names+setOf("open_map","open_url")
    fun opensScreenOnly(tool:String):Boolean=tool in screenOpeners
    /** A PREPARED step that must stop the run until the owner confirms in the other app. */
    fun pendingEffect(step:HarnessStep):Boolean=step.evidence.state==VerificationState.PREPARED&&!opensScreenOnly(step.tool)
    fun pendingEffect(tool:String,state:VerificationState):Boolean=state==VerificationState.PREPARED&&!opensScreenOnly(tool)
}
