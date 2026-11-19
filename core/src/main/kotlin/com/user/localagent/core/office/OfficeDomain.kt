package com.user.localagent.core.office

import java.security.SecureRandom
import java.util.Collections
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

internal fun officeEntityId(value:String):String = value.also {
    require(runCatching { UUID.fromString(it).toString()==it }.getOrDefault(false)) { "Office 식별자가 올바르지 않습니다." }
}
internal fun officeOpaqueId(value:String):String = officeText(value,160)
internal fun officeText(value:String,max:Int):String = value.also {
    require(it.isNotBlank()&&it==it.trim()&&it.codePointCount(0,it.length)<=max&&it.none(Char::isISOControl)) { "Office 표시 값이 올바르지 않습니다." }
    var index=0
    while(index<it.length) {
        val char=it[index]
        require(!char.isLowSurrogate()) { "Office 문자열이 올바르지 않습니다." }
        if(char.isHighSurrogate()) {require(index+1<it.length&&it[index+1].isLowSurrogate());index++}
        index++
    }
}
internal fun <T> officeFrozen(values:Collection<T>):List<T> = Collections.unmodifiableList(values.toList())

@Serializable enum class OfficePrincipalKind { @SerialName("human") HUMAN, @SerialName("agent") AGENT }
@Serializable enum class OfficeRole { @SerialName("owner") OWNER, @SerialName("admin") ADMIN, @SerialName("member") MEMBER, @SerialName("observer") OBSERVER }
@Serializable enum class OfficeInvitationStatus { @SerialName("pending") PENDING, @SerialName("accepted") ACCEPTED, @SerialName("declined") DECLINED, @SerialName("revoked") REVOKED, @SerialName("expired") EXPIRED }

/** Principal IDs come from the authenticated host, never a display name, model name or message body. */
@Serializable data class OfficePrincipal(val id:String,val kind:OfficePrincipalKind) {
    init {officeOpaqueId(id)}
    override fun toString()="OfficePrincipal(kind=$kind)"
}
@Serializable data class OfficeMember(val id:String,val principal:OfficePrincipal,val displayName:String,val role:OfficeRole,
    val joinedAt:Long,val joinedViaInvitationId:String?=null) {
    init {officeEntityId(id);officeText(displayName,40);require(joinedAt>=0);joinedViaInvitationId?.let(::officeEntityId)}
    override fun toString()="OfficeMember(kind=${principal.kind},role=$role)"
}
@Serializable data class OfficeInvitation(val id:String,val officeId:String,val inviterMemberId:String,val recipient:OfficePrincipal,
    val offeredRole:OfficeRole,val createdAt:Long,val expiresAt:Long,val status:OfficeInvitationStatus=OfficeInvitationStatus.PENDING,
    val resolvedAt:Long?=null,val acceptedMemberId:String?=null) {
    init {
        officeEntityId(id);officeEntityId(officeId);officeEntityId(inviterMemberId)
        require(offeredRole!=OfficeRole.OWNER&&createdAt>=0&&expiresAt>createdAt&&expiresAt-createdAt<=OfficeDomain.MAX_INVITATION_TTL_MS)
        require((status==OfficeInvitationStatus.PENDING)==(resolvedAt==null))
        resolvedAt?.let {require(it>=createdAt)}
        require((status==OfficeInvitationStatus.ACCEPTED)==(acceptedMemberId!=null));acceptedMemberId?.let(::officeEntityId)
        if(status==OfficeInvitationStatus.ACCEPTED)require(checkNotNull(resolvedAt)<expiresAt)
        if(status==OfficeInvitationStatus.EXPIRED)require(checkNotNull(resolvedAt)>=expiresAt)
    }
    override fun toString()="OfficeInvitation(status=$status,role=$offeredRole)"
}
@Serializable data class OfficeGroup(val id:String,val label:String,val memberIds:List<String>,val projectIds:List<String>) {
    init {
        officeEntityId(id);officeText(label,80)
        require(memberIds.size<=OfficeDomain.MAX_MEMBERS&&memberIds.distinct().size==memberIds.size);memberIds.forEach(::officeEntityId)
        require(projectIds.size<=OfficeDomain.MAX_PROJECTS_PER_GROUP&&projectIds.distinct().size==projectIds.size);projectIds.forEach(::officeOpaqueId)
    }
    internal fun frozen()=copy(memberIds=officeFrozen(memberIds),projectIds=officeFrozen(projectIds))
    override fun toString()="OfficeGroup(members=${memberIds.size},projects=${projectIds.size})"
}

@Serializable private data class OfficePayload(val version:Int,val id:String,val founderMemberId:String,val label:String,val revision:Long,
    val createdAt:Long,val updatedAt:Long,val members:List<OfficeMember>,val invitations:List<OfficeInvitation>,val groups:List<OfficeGroup>)

/** Metadata only. No project contents, ACLs, account sessions or chat histories are stored here. */
class OfficeState(val id:String,val founderMemberId:String,val label:String,val revision:Long,val createdAt:Long,val updatedAt:Long,
    members:List<OfficeMember>,invitations:List<OfficeInvitation> = emptyList(),groups:List<OfficeGroup> = emptyList()) {
    val members=officeFrozen(members)
    val invitations=officeFrozen(invitations)
    val groups=officeFrozen(groups.map {it.frozen()})
    init {validate()}
    fun validate():OfficeState {
        officeEntityId(id);officeEntityId(founderMemberId);officeText(label,80)
        require(revision>=1&&createdAt>=0&&updatedAt>=createdAt)
        require(members.size in 1..OfficeDomain.MAX_MEMBERS&&members.count {it.role==OfficeRole.OWNER}==1)
        require(members.map {it.principal.id}.distinct().size==members.size&&members.map {it.displayName}.distinct().size==members.size)
        require(invitations.size<=OfficeDomain.MAX_INVITATIONS&&groups.size<=OfficeDomain.MAX_GROUPS)
        val ids=listOf(id)+members.map {it.id}+invitations.map {it.id}+groups.map {it.id}
        require(ids.distinct().size==ids.size)
        require(invitations.all {it.officeId==id})
        val historicalMemberIds=invitations.mapNotNull {it.acceptedMemberId}
        require(historicalMemberIds.distinct().size==historicalMemberIds.size&&historicalMemberIds.none {it==id||it==founderMemberId||invitations.any {v->v.id==it}||groups.any {g->g.id==it}})
        for(member in members) {
            require(member.joinedAt<=updatedAt)
            if(member.joinedViaInvitationId==null)require(member.id==founderMemberId)
            else require(invitations.any {it.id==member.joinedViaInvitationId&&it.status==OfficeInvitationStatus.ACCEPTED&&it.acceptedMemberId==member.id&&it.recipient==member.principal})
        }
        require(invitations.all {it.createdAt<=updatedAt&&(it.resolvedAt==null||it.resolvedAt<=updatedAt)})
        require(groups.all {group->group.memberIds.all {target->members.any {it.id==target}}})
        require(toJson().toString().toByteArray(Charsets.UTF_8).size<=OfficeDomain.MAX_STATE_BYTES){"Office 메타데이터 한도에 도달했습니다."}
        return this
    }
    fun memberForPrincipal(principalId:String):OfficeMember?=members.singleOrNull {it.principal.id==principalId}
    fun toJson():JsonObject=Json.parseToJsonElement(CODEC.encodeToString(OfficePayload(1,id,founderMemberId,label,revision,createdAt,updatedAt,members,invitations,groups))).jsonObject
    override fun toString()="OfficeState(revision=$revision,members=${members.size},invitations=${invitations.size},groups=${groups.size})"
    override fun equals(other:Any?):Boolean=other is OfficeState&&toJson()==other.toJson()
    override fun hashCode():Int=toJson().hashCode()
    companion object {
        private val CODEC=Json {ignoreUnknownKeys=false;encodeDefaults=true}
        fun fromJson(text:String):OfficeState {
            require(text.toByteArray(Charsets.UTF_8).size<=OfficeDomain.MAX_STATE_BYTES)
            val p=CODEC.decodeFromString<OfficePayload>(text);require(p.version==1)
            return OfficeState(p.id,p.founderMemberId,p.label,p.revision,p.createdAt,p.updatedAt,p.members,p.invitations,p.groups)
        }
    }
}

/** A fresh, read-only projection of actual host permissions. It is not persisted in OfficeState. */
class OfficeProjectAccessView(val subjectPrincipalId:String,val authorizationRevision:Long,readableProjectIds:Set<String>) {
    val readableProjectIds:Set<String> = Collections.unmodifiableSet(readableProjectIds.toSet())
    init {officeOpaqueId(subjectPrincipalId);require(authorizationRevision>=0);readableProjectIds.forEach(::officeOpaqueId)}
    override fun toString()="OfficeProjectAccessView(revision=$authorizationRevision)"
}
data class OfficeGroupView(val id:String,val label:String,val memberIds:List<String>,val projectIds:List<String>)
data class OfficeInvitationResult(val state:OfficeState,val invitation:OfficeInvitation)

/** Random labels are assigned once on joining. Labels never participate in authorization or targeting. */
object OfficeDisplayNames {
    private val random=SecureRandom()
    private val adjectives=listOf("차분한","든든한","명랑한","꾸준한","산뜻한","다정한","또렷한","슬기로운")
    private val nouns=listOf("수달","참새","여우","고래","토끼","다람쥐","펭귄","부엉이")
    fun assign(existing:Collection<String>,nextInt:(Int)->Int={random.nextInt(it)}):String {
        require(existing.size<=OfficeDomain.MAX_MEMBERS)
        val size=adjectives.size*nouns.size
        val start=nextInt(size).also {require(it in 0 until size)}
        fun name(index:Int)=adjectives[index/nouns.size]+" "+nouns[index%nouns.size]
        for(offset in 0 until size) {val candidate=name((start+offset)%size);if(candidate !in existing)return candidate}
        val base=name(start)
        for(suffix in 2..OfficeDomain.MAX_MEMBERS+2) {val candidate="$base $suffix";if(candidate !in existing)return candidate}
        error("Office 표시 이름을 배정하지 못했습니다.")
    }
}

/** Pure host commands. The caller must authenticate principal IDs and persist the returned revision by CAS. */
object OfficeDomain {
    const val MAX_MEMBERS=128
    const val MAX_STATE_BYTES=262_144
    const val MAX_INVITATIONS=256
    const val MAX_PENDING_INVITATIONS=64
    const val MAX_GROUPS=64
    const val MAX_PROJECTS_PER_GROUP=64
    const val MAX_INVITATION_TTL_MS=7*24*60*60*1000L
    private fun newUuid()=UUID.randomUUID().toString()
    private fun member(state:OfficeState,principalId:String)=checkNotNull(state.memberForPrincipal(principalId)){"Office 참여자가 아닙니다."}
    private fun begin(state:OfficeState,expectedRevision:Long,now:Long) {
        state.validate();check(state.revision==expectedRevision){"Office 저장 버전이 변경되었습니다."};require(now>=state.updatedAt)
    }
    private fun manager(member:OfficeMember)=check(member.role in setOf(OfficeRole.OWNER,OfficeRole.ADMIN)){"Office 관리 역할이 필요합니다."}
    private fun mayOffer(actor:OfficeMember,role:OfficeRole)=role!=OfficeRole.OWNER&&when(actor.role) {
        OfficeRole.OWNER->true;OfficeRole.ADMIN->role in setOf(OfficeRole.MEMBER,OfficeRole.OBSERVER);else->false
    }
    private fun nextId(state:OfficeState,newId:()->String):String=officeEntityId(newId()).also {candidate->
        require(candidate!=state.id&&candidate!=state.founderMemberId&&state.members.none {it.id==candidate}&&state.invitations.none {it.id==candidate||it.acceptedMemberId==candidate}&&state.groups.none {it.id==candidate})
    }
    private fun commit(state:OfficeState,now:Long,members:List<OfficeMember> = state.members,
        invitations:List<OfficeInvitation> = state.invitations,groups:List<OfficeGroup> = state.groups)=
        OfficeState(state.id,state.founderMemberId,state.label,Math.addExact(state.revision,1),state.createdAt,now,members,invitations,groups)
    fun create(label:String,owner:OfficePrincipal,now:Long,newId:()->String=::newUuid,nextInt:(Int)->Int={SecureRandom().nextInt(it)}):OfficeState {
        require(now>=0);officeText(label,80)
        val officeId=officeEntityId(newId());val memberId=officeEntityId(newId());require(officeId!=memberId)
        return OfficeState(officeId,memberId,label,1,now,now,listOf(OfficeMember(memberId,owner,OfficeDisplayNames.assign(emptyList(),nextInt),OfficeRole.OWNER,now)))
    }
    fun invite(state:OfficeState,actorPrincipalId:String,recipient:OfficePrincipal,role:OfficeRole,expectedRevision:Long,now:Long,
        ttlMs:Long=24*60*60*1000L,newId:()->String=::newUuid):OfficeInvitationResult {
        begin(state,expectedRevision,now);val actor=member(state,actorPrincipalId);check(mayOffer(actor,role))
        check(state.memberForPrincipal(recipient.id)==null){"이미 Office 참여자입니다."};require(ttlMs in 1..MAX_INVITATION_TTL_MS)
        val existing=state.invitations.singleOrNull {it.recipient.id==recipient.id&&it.status==OfficeInvitationStatus.PENDING&&it.expiresAt>now}
        if(existing!=null) {check(existing.recipient==recipient&&existing.offeredRole==role);return OfficeInvitationResult(state,existing)}
        val invites=state.invitations.map {if(it.status==OfficeInvitationStatus.PENDING&&it.expiresAt<=now)it.copy(status=OfficeInvitationStatus.EXPIRED,resolvedAt=now)else it}.toMutableList()
        check(invites.count {it.status==OfficeInvitationStatus.PENDING}<MAX_PENDING_INVITATIONS)
        while(invites.size>=MAX_INVITATIONS) {
            val index=invites.indexOfFirst {it.status!=OfficeInvitationStatus.PENDING&&state.members.none {m->m.joinedViaInvitationId==it.id}}
            check(index>=0){"Office 초대 기록 한도에 도달했습니다."};invites.removeAt(index)
        }
        val invitation=OfficeInvitation(nextId(state,newId),state.id,actor.id,recipient,role,now,Math.addExact(now,ttlMs))
        return OfficeInvitationResult(commit(state,now,invitations=invites+invitation),invitation)
    }
    fun accept(state:OfficeState,recipient:OfficePrincipal,invitationId:String,expectedRevision:Long,now:Long,
        newId:()->String=::newUuid,nextInt:(Int)->Int={SecureRandom().nextInt(it)}):OfficeState {
        begin(state,expectedRevision,now);val invitation=state.invitations.single {it.id==invitationId};check(invitation.recipient==recipient)
        if(invitation.status==OfficeInvitationStatus.ACCEPTED) {
            check(state.members.any {it.id==invitation.acceptedMemberId&&it.principal==recipient&&it.joinedViaInvitationId==invitation.id})
            return state // Replaying acceptance cannot restore an old role or display name.
        }
        check(invitation.status==OfficeInvitationStatus.PENDING&&now<invitation.expiresAt)
        check(mayOffer(checkNotNull(state.members.singleOrNull {it.id==invitation.inviterMemberId}),invitation.offeredRole))
        check(state.memberForPrincipal(recipient.id)==null&&state.members.size<MAX_MEMBERS)
        val joined=OfficeMember(nextId(state,newId),recipient,OfficeDisplayNames.assign(state.members.map {it.displayName},nextInt),invitation.offeredRole,now,invitation.id)
        val accepted=invitation.copy(status=OfficeInvitationStatus.ACCEPTED,resolvedAt=now,acceptedMemberId=joined.id)
        return commit(state,now,members=state.members+joined,invitations=state.invitations.map {if(it.id==invitation.id)accepted else it})
    }
    fun decline(state:OfficeState,recipient:OfficePrincipal,invitationId:String,expectedRevision:Long,now:Long):OfficeState {
        begin(state,expectedRevision,now);val invitation=state.invitations.single {it.id==invitationId};check(invitation.recipient==recipient)
        if(invitation.status==OfficeInvitationStatus.DECLINED)return state
        check(invitation.status==OfficeInvitationStatus.PENDING)
        return commit(state,now,invitations=state.invitations.map {if(it.id==invitationId)it.copy(status=OfficeInvitationStatus.DECLINED,resolvedAt=now)else it})
    }
    fun revoke(state:OfficeState,actorPrincipalId:String,invitationId:String,expectedRevision:Long,now:Long):OfficeState {
        begin(state,expectedRevision,now);val invitation=state.invitations.single {it.id==invitationId};check(mayOffer(member(state,actorPrincipalId),invitation.offeredRole))
        if(invitation.status==OfficeInvitationStatus.REVOKED)return state
        check(invitation.status==OfficeInvitationStatus.PENDING)
        return commit(state,now,invitations=state.invitations.map {if(it.id==invitationId)it.copy(status=OfficeInvitationStatus.REVOKED,resolvedAt=now)else it})
    }
    fun renameSelf(state:OfficeState,principalId:String,displayName:String,expectedRevision:Long,now:Long):OfficeState {
        begin(state,expectedRevision,now);officeText(displayName,40);val current=member(state,principalId)
        if(current.displayName==displayName)return state
        require(state.members.none {it.displayName==displayName})
        return commit(state,now,members=state.members.map {if(it.id==current.id)it.copy(displayName=displayName)else it})
    }
    fun changeRole(state:OfficeState,actorPrincipalId:String,targetMemberId:String,role:OfficeRole,expectedRevision:Long,now:Long):OfficeState {
        begin(state,expectedRevision,now);val actor=member(state,actorPrincipalId);val target=state.members.single {it.id==targetMemberId}
        check(role!=OfficeRole.OWNER&&target.role!=OfficeRole.OWNER)
        check(actor.role==OfficeRole.OWNER||actor.role==OfficeRole.ADMIN&&target.role in setOf(OfficeRole.MEMBER,OfficeRole.OBSERVER)&&role in setOf(OfficeRole.MEMBER,OfficeRole.OBSERVER))
        if(target.role==role)return state
        return commit(state,now,members=state.members.map {if(it.id==targetMemberId)it.copy(role=role)else it})
    }
    fun transferOwnership(state:OfficeState,actorPrincipalId:String,targetMemberId:String,expectedRevision:Long,now:Long):OfficeState {
        begin(state,expectedRevision,now);val actor=member(state,actorPrincipalId);check(actor.role==OfficeRole.OWNER)
        require(state.members.any {it.id==targetMemberId});if(actor.id==targetMemberId)return state
        return commit(state,now,members=state.members.map {when(it.id){actor.id->it.copy(role=OfficeRole.ADMIN);targetMemberId->it.copy(role=OfficeRole.OWNER);else->it}})
    }
    fun removeMember(state:OfficeState,actorPrincipalId:String,targetMemberId:String,expectedRevision:Long,now:Long):OfficeState {
        begin(state,expectedRevision,now);val actor=member(state,actorPrincipalId);val target=state.members.single {it.id==targetMemberId}
        check(target.role!=OfficeRole.OWNER)
        check(actor.id==target.id||actor.role==OfficeRole.OWNER||actor.role==OfficeRole.ADMIN&&target.role in setOf(OfficeRole.MEMBER,OfficeRole.OBSERVER))
        return commit(state,now,members=state.members.filterNot {it.id==target.id},invitations=state.invitations.map {
            if(it.status==OfficeInvitationStatus.PENDING&&(it.inviterMemberId==target.id||it.recipient.id==target.principal.id))it.copy(status=OfficeInvitationStatus.REVOKED,resolvedAt=now)else it
        },groups=state.groups.map {it.copy(memberIds=it.memberIds-target.id)})
    }
    fun createGroup(state:OfficeState,actorPrincipalId:String,label:String,memberIds:List<String>,projectIds:List<String>,access:OfficeProjectAccessView,
        expectedRevision:Long,now:Long,newId:()->String=::newUuid):OfficeState {
        begin(state,expectedRevision,now);manager(member(state,actorPrincipalId));check(access.subjectPrincipalId==actorPrincipalId)
        check(state.groups.size<MAX_GROUPS&&memberIds.all {id->state.members.any {it.id==id}}&&projectIds.all {it in access.readableProjectIds})
        val group=OfficeGroup(nextId(state,newId),label,memberIds,projectIds)
        return commit(state,now,groups=state.groups+group)
    }
    fun updateGroup(state:OfficeState,actorPrincipalId:String,groupId:String,label:String,memberIds:List<String>,projectIds:List<String>,access:OfficeProjectAccessView,
        expectedRevision:Long,now:Long):OfficeState {
        begin(state,expectedRevision,now);manager(member(state,actorPrincipalId));check(access.subjectPrincipalId==actorPrincipalId)
        val previous=state.groups.single {it.id==groupId}
        check(memberIds.all {id->state.members.any {it.id==id}}&&projectIds.filterNot {it in previous.projectIds}.all {it in access.readableProjectIds})
        val changed=OfficeGroup(groupId,label,memberIds,projectIds)
        if(changed==previous)return state
        return commit(state,now,groups=state.groups.map {if(it.id==groupId)changed else it})
    }
    fun visibleGroups(state:OfficeState,principalId:String,access:OfficeProjectAccessView):List<OfficeGroupView> {
        state.validate();val viewer=member(state,principalId);check(access.subjectPrincipalId==principalId)
        return officeFrozen(state.groups.filter {viewer.role in setOf(OfficeRole.OWNER,OfficeRole.ADMIN)||viewer.id in it.memberIds}.map {
            OfficeGroupView(it.id,it.label,officeFrozen(it.memberIds),officeFrozen(it.projectIds.filter {project->project in access.readableProjectIds}))
        })
    }
}
