package com.user.localagent.core.office

import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OfficeDomainTest {
    private var serial=0L
    private fun id()=UUID(0,++serial).toString()
    private val owner=OfficePrincipal("host-owner",OfficePrincipalKind.HUMAN)
    private fun office()=OfficeDomain.create("합성 Office",owner,100,::id,{0})
    private fun principal(value:String)=OfficePrincipal(value,OfficePrincipalKind.AGENT)
    private fun join(state:OfficeState,who:OfficePrincipal=principal("agent-a"),role:OfficeRole=OfficeRole.MEMBER):OfficeState {
        val invited=OfficeDomain.invite(state,owner.id,who,role,state.revision,state.updatedAt+1,newId=::id)
        return OfficeDomain.accept(invited.state,who,invited.invitation.id,invited.state.revision,invited.state.updatedAt+1,::id,{0})
    }
    private fun rejected(block:()->Unit)=assertTrue(runCatching(block).isFailure)

    @Test fun namesAreRandomLabelsWhileStableIdsAndRolesSurvivePersistence() {
        val state=join(office())
        assertEquals(2,state.members.map {it.id}.distinct().size)
        assertEquals(2,state.members.map {it.displayName}.distinct().size)
        val renamed=OfficeDomain.renameSelf(state,"agent-a","새 표시 이름",state.revision,105)
        assertEquals(state.members.last().id,renamed.members.last().id)
        assertEquals(state.members.last().principal,renamed.members.last().principal)
        assertEquals(OfficeRole.MEMBER,renamed.members.last().role)
        assertEquals(renamed,OfficeState.fromJson(renamed.toJson().toString()))
    }
    @Test fun invitationDoesNotJoinUntilExactRecipientAccepts() {
        val state=office();val who=principal("agent-a")
        val invited=OfficeDomain.invite(state,owner.id,who,OfficeRole.MEMBER,state.revision,101,newId=::id)
        assertEquals(1,invited.state.members.size)
        rejected {OfficeDomain.accept(invited.state,principal("other"),invited.invitation.id,invited.state.revision,102,::id,{0})}
        rejected {OfficeDomain.accept(invited.state,OfficePrincipal(who.id,OfficePrincipalKind.HUMAN),invited.invitation.id,invited.state.revision,102,::id,{0})}
        val joined=OfficeDomain.accept(invited.state,who,invited.invitation.id,invited.state.revision,102,::id,{0})
        assertEquals(invited.invitation.id,joined.members.last().joinedViaInvitationId)
    }
    @Test fun duplicatePendingInviteIsIdempotentAndCannotSilentlyUpgradeRole() {
        val state=office();val who=principal("agent-a")
        val first=OfficeDomain.invite(state,owner.id,who,OfficeRole.MEMBER,state.revision,101,newId=::id)
        val duplicate=OfficeDomain.invite(first.state,owner.id,who,OfficeRole.MEMBER,first.state.revision,102,newId={error("must not allocate")})
        assertSame(first.state,duplicate.state);assertEquals(first.invitation,duplicate.invitation)
        rejected {OfficeDomain.invite(first.state,owner.id,who,OfficeRole.ADMIN,first.state.revision,102,newId=::id)}
    }
    @Test fun expiredDeclinedAndRevokedInvitationsNeverBecomeMembership() {
        val state=office();val who=principal("agent-a")
        val invited=OfficeDomain.invite(state,owner.id,who,OfficeRole.MEMBER,state.revision,101,ttlMs=10,newId=::id)
        rejected {OfficeDomain.accept(invited.state,who,invited.invitation.id,invited.state.revision,111,::id,{0})}
        val revoked=OfficeDomain.revoke(invited.state,owner.id,invited.invitation.id,invited.state.revision,102)
        rejected {OfficeDomain.accept(revoked,who,invited.invitation.id,revoked.revision,103,::id,{0})}
        val declined=OfficeDomain.decline(invited.state,who,invited.invitation.id,invited.state.revision,102)
        rejected {OfficeDomain.accept(declined,who,invited.invitation.id,declined.revision,103,::id,{0})}
    }
    @Test fun acceptanceRechecksInvitersCurrentRoleInsteadOfUsingPastAuthority() {
        val state=join(office(),principal("admin"),OfficeRole.ADMIN)
        val invited=OfficeDomain.invite(state,"admin",principal("new-agent"),OfficeRole.MEMBER,state.revision,105,newId=::id)
        val demoted=OfficeDomain.changeRole(invited.state,owner.id,state.members.last().id,OfficeRole.MEMBER,invited.state.revision,106)
        rejected {OfficeDomain.accept(demoted,principal("new-agent"),invited.invitation.id,demoted.revision,107,::id,{0})}
    }
    @Test fun acceptingAgainCannotRestoreOldRoleOrAssignAnotherName() {
        val state=join(office())
        val member=state.members.last()
        val promoted=OfficeDomain.changeRole(state,owner.id,member.id,OfficeRole.ADMIN,state.revision,105)
        val result=OfficeDomain.accept(promoted,member.principal,checkNotNull(member.joinedViaInvitationId),promoted.revision,106,{error("no new ID")},{error("no new name")})
        assertSame(promoted,result);assertEquals(OfficeRole.ADMIN,result.members.last().role)
    }
    @Test fun displayNameCannotSpoofAuthorizationOrResolveAnotherPrincipal() {
        val state=join(office())
        val renamed=OfficeDomain.renameSelf(state,"agent-a","owner",state.revision,105)
        rejected {OfficeDomain.invite(renamed,"agent-a",principal("other"),OfficeRole.MEMBER,renamed.revision,106,newId=::id)}
        rejected {OfficeDomain.invite(renamed,"owner",principal("other"),OfficeRole.MEMBER,renamed.revision,106,newId=::id)}
        assertEquals(OfficeRole.MEMBER,renamed.members.last().role)
    }
    @Test fun adminCannotCreateAnotherAdminOrOwnerAndCannotRemoveOwner() {
        val state=join(office(),principal("admin"),OfficeRole.ADMIN)
        for(role in listOf(OfficeRole.ADMIN,OfficeRole.OWNER))rejected {OfficeDomain.invite(state,"admin",principal("other"),role,state.revision,105,newId=::id)}
        rejected {OfficeDomain.removeMember(state,"admin",state.members.first().id,state.revision,105)}
        rejected {OfficeDomain.changeRole(state,"admin",state.members.last().id,OfficeRole.OWNER,state.revision,105)}
    }
    @Test fun explicitOwnershipTransferKeepsExactlyOneOwnerAndDoesNotChangeMembershipIds() {
        val state=join(office())
        rejected {OfficeDomain.removeMember(state,owner.id,state.members.first().id,state.revision,105)}
        val transferred=OfficeDomain.transferOwnership(state,owner.id,state.members.last().id,state.revision,105)
        assertEquals(1,transferred.members.count {it.role==OfficeRole.OWNER})
        assertEquals(state.members.map {it.id},transferred.members.map {it.id})
        val founderLeft=OfficeDomain.removeMember(transferred,"agent-a",state.founderMemberId,transferred.revision,106)
        assertEquals(founderLeft,OfficeState.fromJson(founderLeft.toJson().toString()))
    }
    @Test fun removedMemberNeedsANewInvitationAndOldInvitationCannotRejoin() {
        val state=join(office());val old=state.members.last()
        val removed=OfficeDomain.removeMember(state,owner.id,old.id,state.revision,105)
        rejected {OfficeDomain.accept(removed,old.principal,checkNotNull(old.joinedViaInvitationId),removed.revision,106,::id,{0})}
        val invited=OfficeDomain.invite(removed,owner.id,old.principal,OfficeRole.MEMBER,removed.revision,106,newId=::id)
        rejected {OfficeDomain.accept(invited.state,old.principal,invited.invitation.id,invited.state.revision,107,{old.id},{0})}
        val fresh=OfficeDomain.accept(invited.state,old.principal,invited.invitation.id,invited.state.revision,107,::id,{0})
        assertNotEquals(old.id,fresh.members.last().id)
    }
    @Test fun crossProjectGroupingOnlyExposesIntersectionWithActualHostAccess() {
        val state=join(office())
        val grouped=OfficeDomain.createGroup(state,owner.id,"두 프로젝트",state.members.map {it.id},listOf("project-a","project-b"),OfficeProjectAccessView(owner.id,7,setOf("project-a","project-b")),state.revision,105,::id)
        val before=grouped.toJson()
        assertEquals(listOf("project-a"),OfficeDomain.visibleGroups(grouped,owner.id,OfficeProjectAccessView(owner.id,8,setOf("project-a"))).single().projectIds)
        assertEquals(listOf("project-b"),OfficeDomain.visibleGroups(grouped,"agent-a",OfficeProjectAccessView("agent-a",9,setOf("project-b"))).single().projectIds)
        assertTrue(OfficeDomain.visibleGroups(grouped,"agent-a",OfficeProjectAccessView("agent-a",10,emptySet())).single().projectIds.isEmpty())
        assertEquals(before,grouped.toJson())
        rejected {OfficeDomain.visibleGroups(grouped,"agent-a",OfficeProjectAccessView(owner.id,7,setOf("project-a","project-b")))}
    }
    @Test fun groupsDoNotInviteUnknownPeopleOrCreateProjectAccess() {
        val state=office();val access=OfficeProjectAccessView(owner.id,1,setOf("project-a"))
        rejected {OfficeDomain.createGroup(state,owner.id,"QA",listOf(id()),listOf("project-a"),access,state.revision,101,::id)}
        rejected {OfficeDomain.createGroup(state,owner.id,"QA",listOf(state.founderMemberId),listOf("project-b"),access,state.revision,101,::id)}
        assertEquals(1,state.members.size);assertTrue(state.groups.isEmpty())
    }
    @Test fun snapshotsDefensivelyCopyCollectionsAndStrictDecodeRejectsUnknownFields() {
        val state=office();val ids=mutableListOf(state.founderMemberId);val projects=mutableListOf("project-a")
        val group=OfficeGroup(id(),"QA",ids,projects)
        val snapshot=OfficeState(state.id,state.founderMemberId,state.label,state.revision,state.createdAt,state.updatedAt,state.members,groups=listOf(group))
        ids.clear();projects+= "secret-project"
        assertEquals(listOf(state.founderMemberId),snapshot.groups.single().memberIds)
        assertEquals(listOf("project-a"),snapshot.groups.single().projectIds)
        rejected {(snapshot.members as MutableList).clear()}
        val unknown=JsonObject(snapshot.toJson()+("projectAccessGranted" to JsonPrimitive(true)))
        rejected {OfficeState.fromJson(unknown.toString())}
    }
    @Test fun boundedRandomNamesDoNotLoopOrSplitUnicodeAndNoOpCommandsKeepRevision() {
        val names=mutableListOf<String>()
        repeat(OfficeDomain.MAX_MEMBERS) {names+=OfficeDisplayNames.assign(names){0}}
        assertEquals(OfficeDomain.MAX_MEMBERS,names.distinct().size)
        rejected {OfficeDisplayNames.assign(emptyList()){-1}}
        val state=office()
        assertSame(state,OfficeDomain.renameSelf(state,owner.id,state.members.single().displayName,state.revision,101))
        rejected {OfficeDomain.renameSelf(state,owner.id,"\uD83D",state.revision,101)}
        assertEquals("😀".repeat(40),OfficeDomain.renameSelf(state,owner.id,"😀".repeat(40),state.revision,101).members.single().displayName)
    }
    @Test fun staleRevisionClockRollbackAndIdReuseAreRejected() {
        val state=office()
        rejected {OfficeDomain.renameSelf(state,owner.id,"이름",0,101)}
        rejected {OfficeDomain.renameSelf(state,owner.id,"이름",state.revision,99)}
        rejected {OfficeDomain.invite(state,owner.id,principal("x"),OfficeRole.MEMBER,state.revision,101,newId={state.id})}
    }
    @Test fun pendingInvitationsAreBoundedAndDebugTextContainsNoPrivateLabelsOrIds() {
        var state=office()
        repeat(OfficeDomain.MAX_PENDING_INVITATIONS) {index->state=OfficeDomain.invite(state,owner.id,principal("private-agent-$index"),OfficeRole.MEMBER,state.revision,state.updatedAt+1,newId=::id).state}
        rejected {OfficeDomain.invite(state,owner.id,principal("overflow"),OfficeRole.MEMBER,state.revision,state.updatedAt+1,newId=::id)}
        assertFalse(state.toString().contains(state.id));assertFalse(state.toString().contains(state.label))
        assertFalse(state.members.first().toString().contains(owner.id));assertFalse(state.invitations.first().toString().contains("private-agent"))
    }
}
