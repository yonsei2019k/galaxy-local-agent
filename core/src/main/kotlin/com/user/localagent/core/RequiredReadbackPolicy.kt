package com.user.localagent.core

/** Host observations, never model JSON. This does not execute or grant any tool. */
object RequiredReadbackPolicy {
    data class Binding(val runId:String,val scopeKey:String,val workspaceBinding:String?)
    data class Resource(val kind:String,val stableId:String)
    data class Snapshot(val resource:Resource,val revision:String,val stateSha256:String) {
        init {require(resource.kind.isNotBlank()&&resource.stableId.isNotBlank()&&revision.isNotBlank())
            require(stateSha256.matches(Regex("[a-f0-9]{64}")))}
    }
    /** Capture after mutation/readback while still holding the actual target's host mutation boundary. */
    data class Mutation(val binding:Binding,val stepIndex:Int,val tool:String,val after:Snapshot)
    /** Exact entries returned by this read invocation, before clipping; never a later replayed lookup. */
    data class Read(val binding:Binding,val stepIndex:Int,val tool:String,val returned:List<Snapshot>,val completeForTarget:Boolean=true)
    enum class Reason { NO_LATER_READ, MISSING_READ_PROOF, INCOMPLETE_READ, TARGET_NOT_RETURNED, VERSION_MISMATCH }
    enum class Blocker { REQUEST_TOOL_UNAVAILABLE, INVALID_HOST_PROOF, MISSING_MUTATION_PROOF, BUDGET_EXHAUSTED }
    data class Pending(val mutationStepIndex:Int,val expected:Snapshot,val readTool:String,val reason:Reason)
    data class Assessment(val applicable:Boolean,val pending:List<Pending>,val blockers:Set<Blocker>) {
        /** Only the read-after-write criterion; never whole-task success or permission to run a tool. */
        val readbackSatisfied:Boolean get()=applicable&&pending.isEmpty()&&blockers.isEmpty()
    }
    private data class Relation(val mutation:String,val read:String,val resourceKind:String)
    private val relations=listOf(
        Relation("save_note","search_notes","note"),
        Relation("update_note","search_notes","note"),
        Relation("create_reminder","list_reminders","reminder"),
        Relation("update_reminder","list_reminders","reminder"),
        Relation("write_project_file","read_project_file","project_file"))

    fun assess(binding:Binding,requiredTools:Set<String>,capturedAllowed:Set<String>,steps:List<HarnessStep>,
        mutations:List<Mutation>,reads:List<Read>,remainingSteps:Int,remainingRounds:Int):Assessment {
        require(remainingSteps>=0&&remainingRounds>=0)
        val active=relations.filter {it.mutation in requiredTools&&it.read in requiredTools}
        if(active.isEmpty())return Assessment(false,emptyList(),emptySet())
        val blockers=linkedSetOf<Blocker>()
        if(active.any {it.mutation !in capturedAllowed||it.read !in capturedAllowed})blockers+=Blocker.REQUEST_TOOL_UNAVAILABLE
        fun verified(index:Int,tool:String)=steps.getOrNull(index)?.let {
            it.tool==tool&&it.evidence.state==VerificationState.VERIFIED
        }==true
        fun validMutation(m:Mutation)=m.binding==binding&&verified(m.stepIndex,m.tool)&&
            active.any {it.mutation==m.tool&&it.resourceKind==m.after.resource.kind}&&
            (m.after.resource.kind!="project_file"||binding.workspaceBinding!=null)
        fun validRead(r:Read)=r.binding==binding&&verified(r.stepIndex,r.tool)&&
            active.any {it.read==r.tool}&&r.returned.all {snapshot->
                active.any {it.read==r.tool&&it.resourceKind==snapshot.resource.kind}
            }
        // Duplicate, foreign, stale-run or mis-indexed proof must never be silently treated as current.
        if(mutations.any{!validMutation(it)}||reads.any{!validRead(it)}||
            mutations.groupBy{it.stepIndex}.any{it.value.size!=1}||reads.groupBy{it.stepIndex}.any{it.value.size!=1})
            blockers+=Blocker.INVALID_HOST_PROOF
        val expectedSteps=steps.withIndex().filter {(_,s)->
            s.evidence.state==VerificationState.VERIFIED&&active.any{it.mutation==s.tool}
        }
        if(expectedSteps.any {(index,_)->mutations.none {it.stepIndex==index&&validMutation(it)}})
            blockers+=Blocker.MISSING_MUTATION_PROOF
        val pending=mutableListOf<Pending>()
        // For the same resource the latest observed version is authoritative; do not re-read old versions.
        val latest=mutations.filter(::validMutation).groupBy{it.after.resource}.values.map{group->group.maxBy{it.stepIndex}}
        for(mutation in latest){
            val relation=active.single{it.mutation==mutation.tool}
            val afterReads=reads.filter{validRead(it)&&it.tool==relation.read&&it.stepIndex>mutation.stepIndex}
            // An unrelated later lookup cannot erase a successful target readback. A later observation
            // of this actual target can supersede it and reveal a changed version.
            val after=afterReads.filter{r->r.returned.any{it.resource==mutation.after.resource}}
                .maxByOrNull{it.stepIndex}?:afterReads.maxByOrNull{it.stepIndex}
            val target=after?.returned?.filter {it.resource==mutation.after.resource}.orEmpty()
            if(after?.completeForTarget==true&&target.size==1&&target.single()==mutation.after)continue
            if(target.size>1)blockers+=Blocker.INVALID_HOST_PROOF
            val laterVerifiedReadExists=steps.withIndex().any{(index,s)->index>mutation.stepIndex&&s.tool==relation.read&&s.evidence.state==VerificationState.VERIFIED}
            val reason=when {
                after==null&&laterVerifiedReadExists->Reason.MISSING_READ_PROOF
                after==null->Reason.NO_LATER_READ
                !after.completeForTarget->Reason.INCOMPLETE_READ
                target.isEmpty()->Reason.TARGET_NOT_RETURNED
                else->Reason.VERSION_MISMATCH
            }
            pending+=Pending(mutation.stepIndex,mutation.after,relation.read,reason)
        }
        // Pending verification consumes the existing request budget, never a new segment/run allowance.
        if(pending.isNotEmpty()&&(remainingSteps==0||remainingRounds==0))blockers+=Blocker.BUDGET_EXHAUSTED
        return Assessment(true,pending.toList(),blockers.toSet())
    }
}
