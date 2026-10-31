package com.user.localagent.imports

import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Captured before save, so cancellation after SQLite commit cannot hide an owned output. */
data class IncompleteConversationNote(val noteId:String,val bodySha256:String) {
    override fun toString()="IncompleteConversationNote(<private>)"
}

data class ConversationCleanupResult(val deleted:Int=0,val missing:Int=0,
    val retainedChanged:Int=0,val retainedUnverified:Int=0) {
    val verified:Boolean get()=retainedChanged==0&&retainedUnverified==0
    operator fun plus(other:ConversationCleanupResult)=ConversationCleanupResult(
        deleted+other.deleted,missing+other.missing,retainedChanged+other.retainedChanged,retainedUnverified+other.retainedUnverified)
    override fun toString()="ConversationCleanupResult(deleted=$deleted,missing=$missing,retainedChanged=$retainedChanged,retainedUnverified=$retainedUnverified)"
}

/** Host-owned rollback protocol; does not grant note access or bypass a repository's source scope. */
object ConversationImportCleanup {
    private val uuid=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private val sha=Regex("[a-f0-9]{64}")
    fun validateExpected(run:String,expected:List<IncompleteConversationNote>) {
        require(uuid.matches(run)&&expected.size<=2001&&expected.map {it.noteId}.distinct().size==expected.size){"미완성 대화 정리 범위가 올바르지 않습니다."}
        expected.forEach {require(uuid.matches(it.noteId)&&sha.matches(it.bodySha256)){"미완성 대화 자료의 식별자가 올바르지 않습니다."}}
    }
    fun sha256(body:String):String=MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
        .joinToString(""){"%02x".format(it.toInt()and 255)}

    fun owns(collection:String,body:String,run:String,expected:IncompleteConversationNote):Boolean {
        if(collection!="imported-chats"||!uuid.matches(run)||!uuid.matches(expected.noteId)||
            !sha.matches(expected.bodySha256)||sha256(body)!=expected.bodySha256)return false
        val lineEnd=body.indexOf('\n')
        val header=if(lineEnd<0)body else body.substring(0,lineEnd)
        if(header.length>4000)return false
        val meta=runCatching {Json.parseToJsonElement(header) as? JsonObject}.getOrNull()?:return false
        fun string(key:String)=(meta[key] as? JsonPrimitive)?.takeIf {it.isString}?.content
        fun integer(key:String)=(meta[key] as? JsonPrimitive)?.takeIf {!it.isString}?.intOrNull
        if(string("type")!="conversation-import-v1"||string("run")!=run||!sha.matches(string("sha256").orEmpty())||
            string("scope")!="user_supplied_single_file_only"||string("encoding") !in setOf("UTF-8","EUC-KR")||
            string("format").isNullOrBlank()||(integer("recognized_messages")?:-1)<0)return false
        val count=integer("chunks")?:return false
        if(count !in 1..2000)return false
        return when(string("kind")) {
            "chunk"->lineEnd>=0&&lineEnd<body.lastIndex&&(integer("chunk")?:0)in 1..count
            "manifest"->lineEnd<0&&!meta.containsKey("chunk")
            else->false
        }
    }
}
