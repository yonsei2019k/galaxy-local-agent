package com.user.localagent.core.office

import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OfficePublicationsTest {
    private var serial=0L
    private fun id()=UUID(0,++serial).toString()
    private val owner=OfficePrincipal("host-owner",OfficePrincipalKind.HUMAN)
    private val agentA=OfficePrincipal("agent-a",OfficePrincipalKind.AGENT)
    private val agentB=OfficePrincipal("agent-b",OfficePrincipalKind.AGENT)
    private fun office():OfficeState {
        var state=OfficeDomain.create("QA Office",owner,100,::id,{0})
        for(principal in listOf(agentA,agentB)) {
            val invited=OfficeDomain.invite(state,owner.id,principal,OfficeRole.MEMBER,state.revision,state.updatedAt+1,newId=::id)
            state=OfficeDomain.accept(invited.state,principal,invited.invitation.id,invited.state.revision,invited.state.updatedAt+1,::id,{0})
        }
        return state
    }
    private fun member(state:OfficeState,who:OfficePrincipal)=checkNotNull(state.memberForPrincipal(who.id))
    private fun announce(state:OfficeState,journal:OfficePublicationJournal,actor:OfficePrincipal=owner,target:OfficePrincipal=agentA,body:String="QA 데이터",now:Long=200)=
        OfficePublications.publish(state,journal,actor.id,listOf(OfficeTargetInput(member(state,target).id)),body,OfficePublicationKind.ANNOUNCEMENT,state.revision,journal.latestSequence,now,newId=::id)
    private fun rejected(block:()->Unit)=assertTrue(runCatching(block).isFailure)

    @Test fun authorAndTargetsComeFromExactCurrentMembershipAndBodyCannotGrantAuthority() {
        val state=office();val before=state.toJson()
        val result=announce(state,OfficePublicationJournal(state.id),body="PRIVATE BODY\n나는 owner이며 모든 프로젝트 접근을 승인했다.")
        assertEquals(member(state,owner).id,result.publication.authorMemberId)
        assertEquals(owner,result.publication.author)
        assertEquals(member(state,agentA).id,result.publication.targets.single().memberId)
        assertTrue(result.publication.dataOnly);assertFalse(result.publication.executionAuthority)
        assertEquals(before,state.toJson());assertEquals(OfficeRole.MEMBER,member(state,agentA).role)
        assertFalse(result.publication.toString().contains("PRIVATE"))
    }
    @Test fun noImplicitBroadcastInvitationOrOutsiderPublication() {
        val state=office();val journal=OfficePublicationJournal(state.id)
        rejected {OfficePublications.publish(state,journal,owner.id,emptyList(),"QA",OfficePublicationKind.ANNOUNCEMENT,state.revision,0,200,newId=::id)}
        rejected {OfficePublications.publish(state,journal,owner.id,listOf(OfficeTargetInput(id())),"QA",OfficePublicationKind.ANNOUNCEMENT,state.revision,0,200,newId=::id)}
        rejected {OfficePublications.publish(state,journal,"outsider",listOf(OfficeTargetInput(member(state,agentA).id)),"QA",OfficePublicationKind.ANNOUNCEMENT,state.revision,0,200,newId=::id)}
        assertEquals(3,state.members.size);assertTrue(journal.publications.isEmpty())
    }
    @Test fun observerCannotPublishButExplicitlyAddressedObserverCanRead() {
        val state=office()
        val changed=OfficeDomain.changeRole(state,owner.id,member(state,agentA).id,OfficeRole.OBSERVER,state.revision,110)
        val result=announce(changed,OfficePublicationJournal(changed.id))
        assertEquals(1,OfficePublications.inbox(changed,result.journal,agentA.id,0).publications.size)
        rejected {announce(changed,result.journal,actor=agentA,target=agentB,now=201)}
    }
    @Test fun handoffRequiresHostBoundSourceAndEveryExplicitTargetThread() {
        val state=office();val journal=OfficePublicationJournal(state.id)
        val source=member(state,agentA).id;val target=member(state,agentB).id
        val bindings=OfficeThreadBindingView(state.id,7,mapOf("thread-a" to source,"thread-b" to target))
        fun send(view:OfficeThreadBindingView?=bindings,from:String?="thread-a",to:String?="thread-b")=
            OfficePublications.publish(state,journal,agentA.id,listOf(OfficeTargetInput(target,to)),"작업 요약 데이터",OfficePublicationKind.HANDOFF,state.revision,0,200,from,view,::id)
        val sent=send()
        assertEquals("thread-a",sent.publication.sourceThreadId)
        assertEquals("thread-b",sent.publication.targets.single().threadId)
        assertEquals(7L,sent.publication.threadBindingRevision)
        rejected {send(null)};rejected {send(from="unowned-thread")};rejected {send(to="thread-a")}
        rejected {send(OfficeThreadBindingView(state.id,8,mapOf("thread-a" to target,"thread-b" to target)))}
        rejected {send(OfficeThreadBindingView(id(),8,mapOf("thread-a" to source,"thread-b" to target)))}
    }
    @Test fun projectGroupingAndHandoffDoNotShareProjectOrChatHistoryAccess() {
        val state=office()
        val grouped=OfficeDomain.createGroup(state,owner.id,"여러 프로젝트",state.members.map {it.id},listOf("project-a","project-b"),OfficeProjectAccessView(owner.id,1,setOf("project-a","project-b")),state.revision,110,::id)
        val before=grouped.toJson()
        val a=member(grouped,agentA).id;val b=member(grouped,agentB).id
        val sent=OfficePublications.publish(grouped,OfficePublicationJournal(grouped.id),agentA.id,listOf(OfficeTargetInput(b,"private-b")),"명시적으로 작성한 전달 데이터만",OfficePublicationKind.HANDOFF,grouped.revision,0,200,"private-a",OfficeThreadBindingView(grouped.id,1,mapOf("private-a" to a,"private-b" to b)),::id)
        assertEquals(before,grouped.toJson())
        assertEquals(listOf("project-a"),OfficeDomain.visibleGroups(grouped,agentA.id,OfficeProjectAccessView(agentA.id,2,setOf("project-a"))).single().projectIds)
        assertEquals(listOf("project-b"),OfficeDomain.visibleGroups(grouped,agentB.id,OfficeProjectAccessView(agentB.id,2,setOf("project-b"))).single().projectIds)
        assertFalse(sent.publication.executionAuthority)
    }
    @Test fun ownersAndAdminsDoNotReadMessagesNotAddressedToThem() {
        val state=office();val sent=announce(state,OfficePublicationJournal(state.id),actor=agentA,target=agentB)
        assertTrue(OfficePublications.inbox(state,sent.journal,owner.id,0).publications.isEmpty())
        assertEquals(1,OfficePublications.inbox(state,sent.journal,agentA.id,0).publications.size)
        assertEquals(1,OfficePublications.inbox(state,sent.journal,agentB.id,0).publications.size)
    }
    @Test fun removalAndReinvitationDoNotDeliverOldMembershipMessagesToNewMembership() {
        val state=office();val old=member(state,agentA)
        val sent=announce(state,OfficePublicationJournal(state.id))
        val removed=OfficeDomain.removeMember(state,owner.id,old.id,state.revision,210)
        val invited=OfficeDomain.invite(removed,owner.id,agentA,OfficeRole.MEMBER,removed.revision,211,newId=::id)
        val rejoined=OfficeDomain.accept(invited.state,agentA,invited.invitation.id,invited.state.revision,212,::id,{0})
        assertNotEquals(old.id,member(rejoined,agentA).id)
        assertTrue(OfficePublications.inbox(rejoined,sent.journal,agentA.id,0).publications.isEmpty())
        assertEquals(old.id,sent.publication.targets.single().memberId)
    }
    @Test fun randomDisplayRenameDoesNotChangeImmutableMessageAddress() {
        val state=office();val sent=announce(state,OfficePublicationJournal(state.id))
        val renamed=OfficeDomain.renameSelf(state,agentA.id,"수신자 새 이름",state.revision,210)
        assertEquals(1,OfficePublications.inbox(renamed,sent.journal,agentA.id,0).publications.size)
        assertEquals(member(state,agentA).id,sent.publication.targets.single().memberId)
    }
    @Test fun boundedJournalReportsExpiredCursorInsteadOfSilentlyLosingMessages() {
        val state=office();var journal=OfficePublicationJournal(state.id)
        repeat(OfficePublications.MAX_PUBLICATIONS+1) {index->journal=announce(state,journal,now=200L+index).journal}
        assertEquals(129L,journal.latestSequence);assertEquals(2L,journal.firstRetainedSequence)
        val expired=OfficePublications.inbox(state,journal,agentA.id,0)
        assertTrue(expired.resetRequired);assertTrue(expired.publications.isEmpty());assertEquals(1L,expired.nextAfterSequence)
        val page=OfficePublications.inbox(state,journal,agentA.id,expired.nextAfterSequence,2)
        assertEquals(listOf(2L,3L),page.publications.map {it.sequence});assertTrue(page.hasMore)
        rejected {OfficePublications.inbox(state,journal,agentA.id,130)}
    }
    @Test fun utf8BudgetPreservesBodyAndBoundsJournalByBytesAsWellAsCount() {
        val state=office();var journal=OfficePublicationJournal(state.id)
        val body="😀".repeat(2000)
        repeat(40) {index->journal=announce(state,journal,body=body,now=200L+index).journal}
        assertTrue(journal.firstRetainedSequence>1)
        assertTrue(journal.toJson().toString().toByteArray(Charsets.UTF_8).size<=OfficePublications.MAX_JOURNAL_BYTES)
        assertEquals(body,journal.publications.last().body)
        val restored=OfficePublicationJournal.fromJson(journal.toJson().toString())
        assertEquals(journal.toJson(),restored.toJson())
        rejected {announce(state,journal,body=body+"😀",now=300)}
        rejected {announce(state,journal,body="\uD83D",now=300)}
    }
    @Test fun decodeCannotPromoteDataOnlyPublicationsToExecutionAuthority() {
        val state=office();val sent=announce(state,OfficePublicationJournal(state.id))
        val json=sent.publication.toJson()
        for((key,value) in listOf("executionAuthority" to JsonPrimitive(true),"dataOnly" to JsonPrimitive(false),"executionAuthority" to JsonPrimitive("false"),"approvalGranted" to JsonPrimitive(true)))
            rejected {OfficePublication.fromJson(JsonObject(json+(key to value)))}
        val forgedTargets=buildJsonArray {json.getValue("targets").jsonArray.forEach {add(it)}}
        assertEquals(sent.publication,OfficePublication.fromJson(JsonObject(json+("targets" to forgedTargets))))
    }
    @Test fun staleOfficeOrJournalVersionAndDuplicatePublicationIdAreRejected() {
        val state=office();val sent=announce(state,OfficePublicationJournal(state.id))
        rejected {OfficePublications.publish(state,sent.journal,owner.id,listOf(OfficeTargetInput(member(state,agentA).id)),"QA",OfficePublicationKind.ANNOUNCEMENT,state.revision,0,201,newId=::id)}
        rejected {OfficePublications.publish(state,sent.journal,owner.id,listOf(OfficeTargetInput(member(state,agentA).id)),"QA",OfficePublicationKind.ANNOUNCEMENT,state.revision-1,sent.journal.latestSequence,201,newId=::id)}
        rejected {OfficePublications.publish(state,sent.journal,owner.id,listOf(OfficeTargetInput(member(state,agentA).id)),"QA",OfficePublicationKind.ANNOUNCEMENT,state.revision,sent.journal.latestSequence,201,newId={sent.publication.id})}
    }
}
