package com.user.localagent.core.office

import java.util.Collections
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

@Serializable enum class OfficePublicationKind { @SerialName("announcement") ANNOUNCEMENT, @SerialName("handoff") HANDOFF }
data class OfficeTargetInput(val memberId:String,val threadId:String?=null) {
    init {officeEntityId(memberId);threadId?.let(::officeOpaqueId)}
}
@Serializable data class OfficeMessageTarget(val memberId:String,val principal:OfficePrincipal,val threadId:String?=null) {
    init {officeEntityId(memberId);threadId?.let(::officeOpaqueId)}
    override fun toString()="OfficeMessageTarget(threadBound=${threadId!=null})"
}

/** Fresh host ownership metadata only. This contains no history and does not grant history access. */
class OfficeThreadBindingView(val officeId:String,val authorizationRevision:Long,ownersByThread:Map<String,String>) {
    val ownersByThread:Map<String,String> = Collections.unmodifiableMap(ownersByThread.toMap())
    init {officeEntityId(officeId);require(authorizationRevision>=0);ownersByThread.forEach {(thread,member)->officeOpaqueId(thread);officeEntityId(member)}}
    override fun toString()="OfficeThreadBindingView(revision=$authorizationRevision)"
}
@Serializable private data class PublicationPayload(val version:Int,val id:String,val officeId:String,val sequence:Long,val kind:OfficePublicationKind,
    val authorMemberId:String,val author:OfficePrincipal,val targets:List<OfficeMessageTarget>,val sourceThreadId:String?,
    val threadBindingRevision:Long?,val body:String,val createdAt:Long,val dataOnly:Boolean,val executionAuthority:Boolean)

/** Immutable addressed data. A handoff is not a run, approval, project grant or claim of completion. */
class OfficePublication(val id:String,val officeId:String,val sequence:Long,val kind:OfficePublicationKind,
    val authorMemberId:String,val author:OfficePrincipal,targets:List<OfficeMessageTarget>,val sourceThreadId:String?,
    val threadBindingRevision:Long?,val body:String,val createdAt:Long) {
    val targets=officeFrozen(targets)
    val dataOnly:Boolean get()=true
    val executionAuthority:Boolean get()=false
    init {
        officeEntityId(id);officeEntityId(officeId);officeEntityId(authorMemberId);require(sequence>=1&&createdAt>=0)
        require(targets.size in 1..OfficePublications.MAX_TARGETS&&targets.map {it.memberId}.distinct().size==targets.size)
        require(body.isNotBlank()&&body.codePointCount(0,body.length)<=OfficePublications.MAX_BODY_CODEPOINTS&&body.toByteArray(Charsets.UTF_8).size<=OfficePublications.MAX_BODY_BYTES)
        require(body.none {it=='\u0000'||it.isISOControl()&&it !in setOf('\n','\r','\t')})
        var index=0
        while(index<body.length) {require(!body[index].isLowSurrogate());if(body[index].isHighSurrogate()){require(index+1<body.length&&body[index+1].isLowSurrogate());index++};index++}
        when(kind) {
            OfficePublicationKind.ANNOUNCEMENT->require(sourceThreadId==null&&threadBindingRevision==null&&targets.all {it.threadId==null})
            OfficePublicationKind.HANDOFF->{officeOpaqueId(checkNotNull(sourceThreadId));require(threadBindingRevision!=null&&threadBindingRevision>=0&&targets.all {it.threadId!=null&&it.threadId!=sourceThreadId})}
        }
        require(toJson().toString().toByteArray(Charsets.UTF_8).size<=OfficePublications.MAX_PUBLICATION_BYTES)
    }
    fun toJson():JsonObject=Json.parseToJsonElement(CODEC.encodeToString(PublicationPayload(1,id,officeId,sequence,kind,authorMemberId,author,targets,sourceThreadId,threadBindingRevision,body,createdAt,true,false))).jsonObject
    override fun toString()="OfficePublication(sequence=$sequence,kind=$kind,targets=${targets.size},dataOnly=true)"
    override fun equals(other:Any?):Boolean=other is OfficePublication&&toJson()==other.toJson()
    override fun hashCode():Int=toJson().hashCode()
    companion object {
        private val CODEC=Json {ignoreUnknownKeys=false;encodeDefaults=true}
        fun fromJson(value:JsonObject):OfficePublication {
            require(value.toString().toByteArray(Charsets.UTF_8).size<=OfficePublications.MAX_PUBLICATION_BYTES)
            require(value["dataOnly"]?.let {it is JsonPrimitive&&!it.isString&&it.booleanOrNull==true}==true)
            require(value["executionAuthority"]?.let {it is JsonPrimitive&&!it.isString&&it.booleanOrNull==false}==true)
            val p=CODEC.decodeFromString<PublicationPayload>(value.toString())
            require(p.version==1&&p.dataOnly&&!p.executionAuthority)
            return OfficePublication(p.id,p.officeId,p.sequence,p.kind,p.authorMemberId,p.author,p.targets,p.sourceThreadId,p.threadBindingRevision,p.body,p.createdAt)
        }
    }
}

class OfficePublicationJournal(val officeId:String,val latestSequence:Long=0,publications:List<OfficePublication> = emptyList()) {
    val publications=officeFrozen(publications)
    val firstRetainedSequence:Long get()=publications.firstOrNull()?.sequence?:Math.addExact(latestSequence,1)
    init {
        officeEntityId(officeId);require(latestSequence in 0 until Long.MAX_VALUE&&publications.size<=OfficePublications.MAX_PUBLICATIONS)
        require(publications.all {it.officeId==officeId}&&publications.map {it.id}.distinct().size==publications.size)
        if(publications.isEmpty())require(latestSequence==0L)
        else require(publications.last().sequence==latestSequence&&publications.zipWithNext().all {(a,b)->a.sequence+1==b.sequence&&a.createdAt<=b.createdAt})
        require(toJson().toString().toByteArray(Charsets.UTF_8).size<=OfficePublications.MAX_JOURNAL_BYTES)
    }
    fun toJson():JsonObject=buildJsonObject {
        put("version",1);put("officeId",officeId);put("latestSequence",latestSequence)
        put("publications",buildJsonArray {publications.forEach {add(it.toJson())}})
    }
    override fun toString()="OfficePublicationJournal(latestSequence=$latestSequence,retained=${publications.size})"
    companion object {
        fun fromJson(text:String):OfficePublicationJournal {
            require(text.toByteArray(Charsets.UTF_8).size<=OfficePublications.MAX_JOURNAL_BYTES)
            val p=Json.parseToJsonElement(text).jsonObject
            require(p.keys==setOf("version","officeId","latestSequence","publications"))
            fun number(key:String)=p.getValue(key).jsonPrimitive.let {require(!it.isString);it.long}
            require(number("version")==1L)
            return OfficePublicationJournal(p.getValue("officeId").jsonPrimitive.let {require(it.isString);it.content},number("latestSequence"),p.getValue("publications").jsonArray.map {OfficePublication.fromJson(it.jsonObject)})
        }
    }
}
data class OfficePublicationAppend(val journal:OfficePublicationJournal,val publication:OfficePublication)
data class OfficeInboxPage(val publications:List<OfficePublication>,val firstRetainedSequence:Long,val latestSequence:Long,
    val nextAfterSequence:Long,val resetRequired:Boolean,val hasMore:Boolean)

object OfficePublications {
    const val MAX_BODY_CODEPOINTS=2000
    const val MAX_BODY_BYTES=8000
    const val MAX_PUBLICATION_BYTES=65_536
    const val MAX_TARGETS=16
    const val MAX_PUBLICATIONS=128
    const val MAX_JOURNAL_BYTES=262_144
    /** Caller authenticates actor, refreshes thread bindings and CAS-writes both expected versions. */
    fun publish(state:OfficeState,journal:OfficePublicationJournal,actorPrincipalId:String,targets:List<OfficeTargetInput>,body:String,
        kind:OfficePublicationKind,expectedOfficeRevision:Long,expectedLatestSequence:Long,now:Long,sourceThreadId:String?=null,
        threadBindings:OfficeThreadBindingView?=null,newId:()->String={UUID.randomUUID().toString()}):OfficePublicationAppend {
        state.validate();check(state.id==journal.officeId&&state.revision==expectedOfficeRevision&&journal.latestSequence==expectedLatestSequence)
        val actor=checkNotNull(state.memberForPrincipal(actorPrincipalId));check(actor.role!=OfficeRole.OBSERVER)
        require(now>=state.updatedAt&&(journal.publications.lastOrNull()?.createdAt?:0)<=now)
        require(targets.size in 1..MAX_TARGETS&&targets.map {it.memberId}.distinct().size==targets.size)
        val captured=targets.map {target->val current=state.members.single {it.id==target.memberId};OfficeMessageTarget(current.id,current.principal,target.threadId)}
        if(kind==OfficePublicationKind.HANDOFF) {
            val bindings=checkNotNull(threadBindings);check(bindings.officeId==state.id)
            val source=checkNotNull(sourceThreadId)
            check(bindings.ownersByThread[source]==actor.id&&captured.all {it.threadId!=null&&bindings.ownersByThread[checkNotNull(it.threadId)]==it.memberId})
        }else require(threadBindings==null)
        val id=officeEntityId(newId());require(id!=state.id&&id!=state.founderMemberId&&state.members.none {it.id==id}&&state.groups.none {it.id==id}&&state.invitations.none {it.id==id||it.acceptedMemberId==id}&&journal.publications.none {it.id==id})
        val publication=OfficePublication(id,state.id,Math.addExact(journal.latestSequence,1),kind,actor.id,actor.principal,captured,sourceThreadId,threadBindings?.authorizationRevision,body,now)
        val retained=(journal.publications+publication).toMutableList()
        while(retained.size>MAX_PUBLICATIONS||buildJsonObject {put("version",1);put("officeId",state.id);put("latestSequence",publication.sequence);put("publications",buildJsonArray {retained.forEach {add(it.toJson())}})}.toString().toByteArray(Charsets.UTF_8).size>MAX_JOURNAL_BYTES) {
            check(retained.size>1);retained.removeAt(0)
        }
        return OfficePublicationAppend(OfficePublicationJournal(state.id,publication.sequence,retained),publication)
    }
    /** Even an owner/admin sees only explicitly addressed messages or its own authored messages. */
    fun inbox(state:OfficeState,journal:OfficePublicationJournal,principalId:String,afterSequence:Long,limit:Int=50):OfficeInboxPage {
        state.validate();check(state.id==journal.officeId);val member=checkNotNull(state.memberForPrincipal(principalId))
        require(afterSequence in 0..journal.latestSequence&&limit in 1..50)
        if(afterSequence<journal.firstRetainedSequence-1)return OfficeInboxPage(emptyList(),journal.firstRetainedSequence,journal.latestSequence,journal.firstRetainedSequence-1,true,false)
        val visible=journal.publications.filter {it.sequence>afterSequence&&(it.authorMemberId==member.id&&it.author==member.principal||it.targets.any {target->target.memberId==member.id&&target.principal==member.principal})}
        val page=officeFrozen(visible.take(limit));val more=visible.size>page.size
        return OfficeInboxPage(page,journal.firstRetainedSequence,journal.latestSequence,if(more)page.last().sequence else journal.latestSequence,false,more)
    }
}
