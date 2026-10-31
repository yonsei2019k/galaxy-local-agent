package com.user.localagent.automation

import com.user.localagent.core.GoalDefinition
import com.user.localagent.data.ChatAccessScope

enum class GoalLifecycleAction { START, RESUME, PAUSE, CLEAR, UPDATE }
data class GoalLifecycleRequest(val action:GoalLifecycleAction,val scope:ChatAccessScope,
    val goal:GoalDefinition,val callerRunId:String?) {
    override fun toString()="GoalLifecycleRequest(action=$action,<private>)"
}
data class GoalLifecycleReceipt(val queuedRunId:String?=null,val queuedOneBoundedRun:Boolean=false,
    val quiesced:Boolean=false,val deferredCallerStop:Boolean=false) {
    override fun toString()="GoalLifecycleReceipt(queuedOneBoundedRun=$queuedOneBoundedRun,quiesced=$quiesced,deferredCallerStop=$deferredCallerStop)"
}
fun interface GoalToolLifecycle {suspend fun apply(request:GoalLifecycleRequest):GoalLifecycleReceipt}

interface GoalToolStore {
    suspend fun load(scope:ChatAccessScope):GoalDefinition?
    suspend fun save(scope:ChatAccessScope,goal:GoalDefinition):GoalDefinition
    suspend fun delete(scope:ChatAccessScope,goalId:String,expectedUpdatedAt:Long):Boolean
}

/** Real adapter; expected access and deletion CAS are checked inside ChatGoalStore.MUTATION. */
class GuardedChatGoalStore(private val store:ChatGoalStore):GoalToolStore {
    override suspend fun load(scope:ChatAccessScope)=store.loadGuarded(scope)
    override suspend fun save(scope:ChatAccessScope,goal:GoalDefinition)=store.saveGuarded(scope,goal)
    override suspend fun delete(scope:ChatAccessScope,goalId:String,expectedUpdatedAt:Long)=store.deleteGuarded(scope,goalId,expectedUpdatedAt)
}
