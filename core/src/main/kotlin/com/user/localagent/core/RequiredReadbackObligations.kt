package com.user.localagent.core

import java.security.MessageDigest
import kotlinx.serialization.json.JsonPrimitive

/** Encrypted host checkpoint metadata. Criteria only: this object never grants or executes tools. */
object RequiredReadbackObligations {
    const val MAX_STEPS = 18
    enum class Pair(val mutation:String,val read:String,val kind:String) {
        NOTE_SAVE("save_note","search_notes","note"),
        NOTE_UPDATE("update_note","search_notes","note"),
        NOTE_PATCH("patch_note","read_note","note_body_v1"),
        REMINDER_CREATE("create_reminder","list_reminders","reminder"),
        REMINDER_UPDATE("update_reminder","list_reminders","reminder"),
        PROJECT_WRITE("write_project_file","read_project_file","project_file")
    }
    data class Context(val runId:String,val threadId:String,val projectId:String?,val scopeKey:String,val workspaceBinding:String?) {
        init {
            require(listOf(runId,threadId,scopeKey).all { bounded(it,256) })
            require(projectId==null||bounded(projectId,256))
            require(workspaceBinding==null||bounded(workspaceBinding,256))
        }
        fun proofBinding()=RequiredReadbackPolicy.Binding(runId,scopeKey,workspaceBinding)
    }
    /** Index into the persisted previousSteps/current steps, plus exact canonical-call digest. */
    data class StepRef(val index:Int,val tool:String,val callSha256:String,val sourceRunId:String) {
        init { require(index in 0 until MAX_STEPS&&tool in Pair.entries.flatMap { listOf(it.mutation,it.read) })
            require(sha(callSha256)&&bounded(sourceRunId,256)) }
    }
    /** One bounded observation for this target; null snapshot explicitly means no target proof. */
    data class Observation(val source:StepRef,val returned:RequiredReadbackPolicy.Snapshot?,val complete:Boolean,val associated:Boolean,val querySha256:String?=null) {
        init {require(querySha256==null||sha(querySha256))}
    }
    data class QueryAssociation(val source:StepRef,val querySha256:String?) {
        init {require(querySha256==null||sha(querySha256))}
        fun key()=querySha256?:"call:"+source.callSha256
    }
    data class Applied(val source:StepRef,val after:RequiredReadbackPolicy.Snapshot,val latestRead:Observation?=null,val coverage:List<QueryAssociation> = emptyList()) {
        init { require(validSnapshot(after));require(latestRead?.returned?.let { validSnapshot(it)&&it.resource==after.resource }!=false)
            require(coverage.size<=MAX_STEPS&&coverage.map {it.key()}.distinct().size==coverage.size) }
    }
    data class State(val context:Context,val originRunId:String,val selected:Set<Pair>,val applied:List<Applied>,val invalidHostProof:Boolean=false) {
        init { require(bounded(originRunId,256)&&selected.isNotEmpty()&&selected.size<=Pair.entries.size)
            require(applied.size<=MAX_STEPS&&applied.map { it.source.index }.distinct().size==applied.size) }
        /** This is collector input only, never executable requiredTools or capturedAllowed. */
        fun selectedTools():Set<String> = selected.flatMap { listOf(it.mutation,it.read) }.toSet()
    }

    /** Capture before effects. Replanning can add a criterion but cannot erase an earlier pair. */
    fun select(state:State?,context:Context,required:Set<String>):State? {
        val selected=Pair.entries.filter { it.mutation in required&&it.read in required }.toSet()
        if(state==null)return selected.takeIf { it.isNotEmpty() }?.let { State(context,context.runId,it,emptyList()) }
        if(state.context!=context)return state.copy(invalidHostProof=true)
        return state.copy(selected=state.selected+selected)
    }

    fun reference(context:Context,index:Int,steps:List<HarnessStep>):StepRef {
        val step=steps[index]
        require(step.evidence.state==VerificationState.VERIFIED&&!step.evidence.effectNotApplied)
        return StepRef(index,step.tool,digest(step.fingerprint),context.runId)
    }

    /** Called in the same NonCancellable checkpoint as previousSteps, using unabridged outcomes. */
    fun capture(state:State?,context:Context,steps:List<HarnessStep>,mutations:List<RequiredReadbackPolicy.Mutation>,
        reads:List<RequiredReadbackPolicy.Read>,aligned:Boolean,querySignatures:Map<Int,String> = emptyMap()):State? {
        if(state==null)return null
        if(state.context!=context||!aligned||steps.size>MAX_STEPS||!validReferences(state,steps))return state.copy(invalidHostProof=true)
        var invalid=state.invalidHostProof
        if(querySignatures.any { (index,value)->index !in steps.indices||!sha(value) })invalid=true
        val applied=state.applied.toMutableList()
        if(mutations.groupBy { it.stepIndex }.any { it.value.size!=1 }||reads.groupBy { it.stepIndex }.any { it.value.size!=1 })invalid=true
        for(m in mutations) {
            val pair=state.selected.singleOrNull { it.mutation==m.tool }
            if(m.binding!=context.proofBinding()||pair?.kind!=m.after.resource.kind||!validSnapshot(m.after)||
                (pair==Pair.PROJECT_WRITE&&context.workspaceBinding==null)||!verified(m.stepIndex,m.tool,steps)) { invalid=true;continue }
            val captured=Applied(reference(context,m.stepIndex,steps),m.after)
            val old=applied.indexOfFirst { it.source.index==m.stepIndex }
            if(old>=0) {
                // Re-collecting the current outcomes is idempotent, but conflicting receipts are not.
                if(applied[old].source.callSha256!=captured.source.callSha256||applied[old].after!=captured.after)invalid=true
            } else if(applied.size<MAX_STEPS)applied+=captured else invalid=true
        }
        val validReads=reads.filter { r->
            val kinds=state.selected.filter { it.read==r.tool }.map { it.kind }.toSet()
            val resources=r.returned.map { it.resource }+r.incompleteTargets+r.queriedTargets
            val valid=r.binding==context.proofBinding()&&kinds.isNotEmpty()&&verified(r.stepIndex,r.tool,steps)&&
                r.returned.all(::validSnapshot)&&resources.all { it.kind in kinds&&bounded(it.stableId,1024) }
            if(!valid)invalid=true
            valid
        }.sortedBy { it.stepIndex }
        val updated=applied.map { mutation->
            val pair=state.selected.single { it.mutation==mutation.source.tool }
            val lastFailedNoteRead=if(pair==Pair.NOTE_PATCH)steps.withIndex().lastOrNull{(index,s)->
                index>mutation.source.index&&s.tool==pair.read&&(s.evidence.state!=VerificationState.VERIFIED||s.evidence.effectNotApplied)&&
                    (s.arguments["id"] as? JsonPrimitive)?.takeIf{it.isString}?.content==mutation.after.resource.stableId
            }?.index else null
            var latest=mutation.latestRead?.takeUnless{lastFailedNoteRead!=null&&it.source.index<=lastFailedNoteRead}
            val coverage=mutation.coverage.toMutableList()
            val sameTargetCoverage=applied.filter {it.after.resource==mutation.after.resource}.flatMap {it.coverage}
            for(r in validReads.filter { it.tool==pair.read&&it.stepIndex>mutation.source.index&&(lastFailedNoteRead==null||it.stepIndex>lastFailedNoteRead) }) {
                if(latest!=null&&r.stepIndex<latest.source.index)continue
                val targets=r.returned.filter { it.resource==mutation.after.resource }
                if(targets.size>1)invalid=true
                val ref=reference(context,r.stepIndex,steps)
                val query=querySignatures[r.stepIndex]?.takeIf(::sha)
                // An exact repeated query previously associated with this target also invalidates
                // old proof when its new result is empty, including across a process restart.
                val associated=targets.isNotEmpty()||mutation.after.resource in r.incompleteTargets||
                    mutation.after.resource in r.queriedTargets||
                    (coverage+sameTargetCoverage).any {it.source.callSha256==ref.callSha256||query!=null&&it.querySha256==query}
                if(associated){
                    val association=QueryAssociation(ref,query)
                    val old=coverage.indexOfFirst {it.key()==association.key()}
                    if(old>=0)coverage[old]=association
                    else if(coverage.size<MAX_STEPS)coverage+=association else invalid=true
                }
                if(associated||latest?.associated!=true)latest=Observation(ref,targets.singleOrNull(),
                    r.completeForTarget&&mutation.after.resource !in r.incompleteTargets,associated,query)
            }
            mutation.copy(latestRead=latest,coverage=coverage.toList())
        }
        return state.copy(applied=updated,invalidHostProof=invalid)
    }

    /** Read authority is checked now. A historical mutation receipt does not grant write authority. */
    fun assess(state:State?,context:Context,steps:List<HarnessStep>,inheritedStepCount:Int,readAllowed:Set<String>,
        remainingSteps:Int,remainingRounds:Int,requestedFinish:Boolean=false):RequiredReadbackPolicy.Assessment {
        require(inheritedStepCount in 0..steps.size&&remainingSteps>=0&&remainingRounds>=0)
        val inheritedMutations=steps.take(inheritedStepCount).withIndex().filter {
            it.value.evidence.state==VerificationState.VERIFIED&&Pair.entries.any { pair->pair.mutation==it.value.tool }
        }
        if(state==null)return RequiredReadbackPolicy.Assessment(inheritedMutations.isNotEmpty(),emptyList(),
            if(inheritedMutations.isEmpty())emptySet()else setOf(RequiredReadbackPolicy.Blocker.MISSING_MUTATION_PROOF))
        val blockers=linkedSetOf<RequiredReadbackPolicy.Blocker>()
        if(state.context!=context||state.invalidHostProof||!validReferences(state,steps))blockers+=RequiredReadbackPolicy.Blocker.INVALID_HOST_PROOF
        val expected=steps.withIndex().filter { (index,s)->s.evidence.state==VerificationState.VERIFIED&&
            (index<inheritedStepCount&&Pair.entries.any { it.mutation==s.tool }||state.selected.any { it.mutation==s.tool }) }
        if(expected.any { (index,_)->state.applied.none { it.source.index==index&&matches(it.source,steps) } })
            blockers+=RequiredReadbackPolicy.Blocker.MISSING_MUTATION_PROOF
        // Before the first effect this remains an unmet completion criterion, with no invented target
        // and no permission to restore a mutation that a later plan is no longer allowed to execute.
        if(requestedFinish&&state.selected.any {pair->state.applied.none {it.source.tool==pair.mutation}})
            blockers+=RequiredReadbackPolicy.Blocker.MISSING_MUTATION_PROOF
        val pending=state.applied.groupBy { it.after.resource }.values.mapNotNull { group->
            val mutation=group.maxBy { it.source.index }
            val read=mutation.latestRead
            val pair=state.selected.singleOrNull { it.mutation==mutation.source.tool&&it.kind==mutation.after.resource.kind }
            if(pair==null) { blockers+=RequiredReadbackPolicy.Blocker.INVALID_HOST_PROOF;return@mapNotNull null }
            if(read!=null&&read.source.index>mutation.source.index&&read.complete&&read.returned==mutation.after&&matches(read.source,steps))return@mapNotNull null
            val reason=when {
                read==null&&steps.withIndex().any { it.index>mutation.source.index&&it.value.tool==pair.read&&it.value.evidence.state==VerificationState.VERIFIED }->RequiredReadbackPolicy.Reason.MISSING_READ_PROOF
                read==null||read.source.index<=mutation.source.index->RequiredReadbackPolicy.Reason.NO_LATER_READ
                !read.complete->RequiredReadbackPolicy.Reason.INCOMPLETE_READ
                read.returned==null->RequiredReadbackPolicy.Reason.TARGET_NOT_RETURNED
                else->RequiredReadbackPolicy.Reason.VERSION_MISMATCH
            }
            RequiredReadbackPolicy.Pending(mutation.source.index,mutation.after,pair.read,reason)
        }
        // Previously complete proof needs no new execution. Any still-needed read must be currently allowed.
        if(pending.any { it.readTool !in readAllowed })blockers+=RequiredReadbackPolicy.Blocker.REQUEST_TOOL_UNAVAILABLE
        if(pending.isNotEmpty()&&(remainingSteps==0||remainingRounds==0))blockers+=RequiredReadbackPolicy.Blocker.BUDGET_EXHAUSTED
        return RequiredReadbackPolicy.Assessment(true,pending,blockers)
    }

    /** Only guarded host resume/steer/sameGoalWork callers may invoke this explicit transfer. */
    fun carry(state:State,from:Context,to:Context,oldSteps:List<HarnessStep>,newSteps:List<HarnessStep>,indexMap:Map<Int,Int>):State {
        require(state.context==from&&sameScope(from,to)&&validReferences(state,oldSteps))
        require(indexMap.keys.all {it in oldSteps.indices}&&indexMap.values.all {it in newSteps.indices}&&indexMap.values.distinct().size==indexMap.size)
        val referenced=state.applied.flatMap {listOf(it.source)+listOfNotNull(it.latestRead?.source)+it.coverage.map {c->c.source}}.map {it.index}.distinct()
        // Two actually distinct call positions cannot be collapsed even when arguments are identical.
        require(referenced.map {checkNotNull(indexMap[it])}.distinct().size==referenced.size)
        fun moved(ref:StepRef):StepRef=ref.copy(index=checkNotNull(indexMap[ref.index])).also { require(matches(it,newSteps)) }
        return state.copy(context=to,applied=state.applied.map { it.copy(source=moved(it.source),latestRead=it.latestRead?.let { r->r.copy(source=moved(r.source)) },
            coverage=it.coverage.map {c->c.copy(source=moved(c.source))}) }).also {require(validReferences(it,newSteps))}
    }
    fun merge(states:List<State>,context:Context):State? {
        if(states.isEmpty())return null
        require(states.all { it.context==context })
        val applied=states.flatMap { it.applied }.groupBy { it.source.index }.values.map { group->
            val first=group.first()
            require(group.all { it.source==first.source&&it.after==first.after })
            val reads=group.mapNotNull { it.latestRead }
            val latest=reads.maxByOrNull { it.source.index }
            require(latest==null||reads.filter {it.source.index==latest.source.index}.all {it==latest})
            val coverage=group.flatMap {it.coverage}.groupBy {it.key()}.values.map {entries->entries.maxBy {it.source.index}}
            first.copy(latestRead=latest,coverage=coverage)
        }
        // A goal's call deduplication can collapse two separate effects. Never guess which target won.
        require(applied.size<=MAX_STEPS&&applied.map { it.source.index }.distinct().size==applied.size)
        return State(context,states.first().originRunId,states.flatMap { it.selected }.toSet(),applied,states.any { it.invalidHostProof })
    }
    fun sameScope(a:Context,b:Context)=a.threadId==b.threadId&&a.projectId==b.projectId&&a.scopeKey==b.scopeKey&&a.workspaceBinding==b.workspaceBinding
    private fun validReferences(state:State,steps:List<HarnessStep>)=state.applied.all { a->
        val pair=state.selected.singleOrNull { it.mutation==a.source.tool&&it.kind==a.after.resource.kind }
        pair!=null&&matches(a.source,steps)&&(pair!=Pair.PROJECT_WRITE||state.context.workspaceBinding!=null)&&
            a.coverage.all {it.source.tool==pair.read&&it.source.index>a.source.index&&matches(it.source,steps)}&&
            (a.latestRead?.let { it.source.tool==pair.read&&it.source.index>a.source.index&&matches(it.source,steps)&&
                (it.returned==null||it.associated)&&(!it.associated||a.coverage.any {c->c.source==it.source&&c.querySha256==it.querySha256}) }!=false)
    }
    private fun verified(index:Int,tool:String,steps:List<HarnessStep>)=steps.getOrNull(index)?.let {
        it.tool==tool&&it.evidence.state==VerificationState.VERIFIED&&!it.evidence.effectNotApplied
    }==true
    private fun matches(ref:StepRef,steps:List<HarnessStep>)=verified(ref.index,ref.tool,steps)&&digest(steps[ref.index].fingerprint)==ref.callSha256
    private fun digest(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun sha(value:String)=value.matches(Regex("[a-f0-9]{64}"))
    private fun bounded(value:String,max:Int)=value.isNotBlank()&&value.length<=max&&value.none { it.code<32 }
    private fun validSnapshot(s:RequiredReadbackPolicy.Snapshot)=s.resource.kind in Pair.entries.map { it.kind }&&bounded(s.resource.stableId,1024)&&bounded(s.revision,256)&&sha(s.stateSha256)
}
