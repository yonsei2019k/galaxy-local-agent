package com.user.localagent.core.connections

import com.user.localagent.core.ToolCall
import kotlinx.serialization.json.*
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Base64

/** Exact, private approval content. It is never a model permission grant or a public audit payload. */
data class GmailSendPreview(
    val toolName:String,val callId:String,val operationId:String,val accountPrincipal:String,val from:String,val to:String,
    val subject:String,val body:String,val threadId:String?,val originalMessageId:String?,val rfcMessageId:String,
    val inReplyTo:String?,val references:List<String>,val mimeSha256:String,val requestFingerprint:String,
    val requestedAtMillis:Long,
) {
    fun approvalText():String=buildString {
        append("실제 이메일 발송 · 초안 저장이 아닙니다\n발신 계정: ").append(from)
        append("\n받는 사람: ").append(to).append("\n제목: ").append(subject)
        append("\n작성 시각: ").append(java.time.Instant.ofEpochMilli(requestedAtMillis).atZone(java.time.ZoneId.of("Asia/Seoul")))
        threadId?.let {append("\n답장 대화 ID: ").append(it)}
        originalMessageId?.let {append("\n답장할 메일 ID: ").append(it)}
        append("\n\n본문 전체\n").append(body).append("\n\nMIME SHA-256: ").append(mimeSha256)
    }
    override fun toString()="GmailSendPreview(toolName=$toolName,privateMailOmitted=true)"
}

internal data class PreparedGmailSend(val preview:GmailSendPreview,val request:ApiRequest)

/** Builds only plain-text, one-recipient mail. Cc/Bcc/From/header injection is never accepted from model arguments. */
object GmailSendContract {
    val names=setOf("send_gmail_message","reply_gmail_message")
    private const val BASE="https://gmail.googleapis.com/gmail/v1/users/me"
    internal fun sha(value:ByteArray)=MessageDigest.getInstance("SHA-256").digest(value).joinToString(""){"%02x".format(it.toInt()and 255)}
    internal fun mailbox(raw:String):String {
        require(raw.length in 3..320&&raw.none {it.code<33||it.code>126}&&
            raw.matches(Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,63}"))){"단일 이메일 주소를 확인해주세요."}
        require(!raw.startsWith('.')&&!raw.substringBefore('@').endsWith('.')&&!raw.substringBefore('@').contains(".."))
        require(raw.substringBeforeLast('@').length<=64&&raw.substringAfterLast('@').length<=253&&
            raw.substringAfterLast('@').split('.').all {it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"))})
        return raw.substringBeforeLast('@')+"@"+raw.substringAfterLast('@').lowercase()
    }
    private fun header(value:String,max:Int):String {
        require(value.isNotBlank()&&value.length<=max&&value.none {it.code<32||it.code==127}){"메일 헤더를 확인해주세요."}
        com.user.localagent.core.PrivateAnalysisEvidence.strictUtf8(value)
        return value
    }
    internal fun plan(call:ToolCall):ApiPlan {
        require(call.name in names);mailbox(call.arguments.getValue("to").jsonPrimitive.content)
        val body=call.arguments.getValue("body").jsonPrimitive.content
        require(body.isNotBlank()&&body.length<=12000&&'\u0000' !in body)
        com.user.localagent.core.PrivateAnalysisEvidence.strictUtf8(body)
        if(call.name=="send_gmail_message")header(call.arguments.getValue("subject").jsonPrimitive.content,300)
        else ConnectionRequests.key(call.arguments.getValue("messageId").jsonPrimitive.content)
        // This harmless preflight is replaced by the specialized approved submission path in ConnectionRuntime.
        return ApiPlan(ConnectedService.GMAIL,true,ApiRequest("$BASE/profile"))
    }
    internal fun profileRequest()=ApiRequest("$BASE/profile")
    internal fun originalRequest(id:String)=ApiRequest("$BASE/messages/${ConnectionRequests.key(id)}?format=metadata")
    internal fun readbackRequest(id:String)=ApiRequest("$BASE/messages/${ConnectionRequests.key(id)}?format=raw")

    internal fun prepare(call:ToolCall,principal:String,operation:String,profileBody:String,originalBody:String?,requestedAtMillis:Long):PreparedGmailSend {
        require(requestedAtMillis>=0)
        val date=java.time.Instant.ofEpochMilli(requestedAtMillis).atOffset(java.time.ZoneOffset.UTC)
        require(date.year in 1900..9999)
        val rfcDate=java.time.format.DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z",java.util.Locale.US).format(date)
        val from=mailbox(Json.parseToJsonElement(profileBody).jsonObject.getValue("emailAddress").jsonPrimitive.content)
        val to=mailbox(call.arguments.getValue("to").jsonPrimitive.content)
        val body=call.arguments.getValue("body").jsonPrimitive.content
        var thread:String?=null;var originalId:String?=null;var replyTo:String?=null;var referenceIds=emptyList<String>()
        val subject=if(call.name=="reply_gmail_message") {
            val original=Json.parseToJsonElement(checkNotNull(originalBody)).jsonObject
            originalId=call.arguments.getValue("messageId").jsonPrimitive.content
            require(original["id"]?.jsonPrimitive?.content==originalId){"답장 원본 ID가 다릅니다."}
            thread=original.getValue("threadId").jsonPrimitive.content.also {ConnectionRequests.key(it)}
            val h=metadataHeaders(original)
            val target=addressHeader(h["reply-to"]?:h.getValue("from"))
            require(to.equals(target,ignoreCase=true)){"받는 주소가 실제 원본의 Reply-To/From과 다릅니다. 새 메일로 별도 검토해주세요."}
            val originalRfcId=messageId(h.getValue("message-id"));replyTo=originalRfcId
            referenceIds=(references(h["references"].orEmpty())+originalRfcId).distinct()
            require(referenceIds.size<=50)
            header(decodeSubject(h.getValue("subject")),300)
        }else header(call.arguments.getValue("subject").jsonPrimitive.content,300)
        val rfcId="<ziphap.$operation@local.ziphap.invalid>"
        val subjectEncoded=encodedSubject(subject)
        val mime=buildString {
            append("From: ").append(from).append("\r\nTo: ").append(to)
            append("\r\nDate: ").append(rfcDate)
            append("\r\nSubject: ").append(subjectEncoded)
            append("\r\nMessage-ID: ").append(rfcId)
            replyTo?.let {append("\r\nIn-Reply-To: ").append(it);append("\r\nReferences: ").append(referenceIds.joinToString("\r\n "))}
            append("\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n")
            append(Base64.getMimeEncoder().encodeToString(body.toByteArray(Charsets.UTF_8)))
        }.toByteArray(Charsets.UTF_8)
        val mimeHash=sha(mime)
        val requestHash=sha((call.name+"\n"+JsonObject(call.arguments.toSortedMap()).toString()+"\n"+mimeHash+"\n"+thread.orEmpty()).toByteArray())
        val preview=GmailSendPreview(call.name,call.id,operation,principal,from,to,subject,body,thread,originalId,rfcId,replyTo,
            java.util.Collections.unmodifiableList(referenceIds.toList()),mimeHash,requestHash,requestedAtMillis)
        val post=ApiRequest("$BASE/messages/send","POST",buildJsonObject {
            put("raw",Base64.getUrlEncoder().withoutPadding().encodeToString(mime));thread?.let {put("threadId",it)}
        }.toString())
        return PreparedGmailSend(preview,post)
    }

    internal fun verify(prepared:PreparedGmailSend,id:String,body:String):Boolean=runCatching {
        val data=Json.parseToJsonElement(body).jsonObject
        require(data["id"]?.jsonPrimitive?.content==id)
        val labels=data.getValue("labelIds").jsonArray.map {it.jsonPrimitive.content}.toSet()
        require("SENT" in labels&&"DRAFT" !in labels)
        val thread=data.getValue("threadId").jsonPrimitive.content.also {ConnectionRequests.key(it)}
        require(prepared.preview.threadId==null||thread==prepared.preview.threadId)
        val bytes=Base64.getUrlDecoder().decode(data.getValue("raw").jsonPrimitive.content)
        val raw=bytes.decodeToString(throwOnInvalidSequence=true).replace("\r\n","\n")
        require('\r' !in raw)
        val split=raw.indexOf("\n\n");require(split>0)
        val h=parseHeaders(raw.substring(0,split).lines())
        val p=prepared.preview
        val observedDate=java.time.ZonedDateTime.parse(h.getValue("date"),java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        require(observedDate==java.time.Instant.ofEpochMilli(p.requestedAtMillis).truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
        require(addressHeader(h.getValue("from"))==p.from&&addressHeader(h.getValue("to"))==p.to)
        require(h["cc"].isNullOrBlank()&&h["bcc"].isNullOrBlank())
        require(decodeSubject(h.getValue("subject"))==p.subject&&messageId(h.getValue("message-id"))==p.rfcMessageId)
        require(h.getValue("content-type").lowercase().replace(" ","") in setOf("text/plain;charset=utf-8","text/plain;charset=\"utf-8\""))
        require(h.getValue("content-transfer-encoding").equals("base64",true))
        require(Base64.getMimeDecoder().decode(raw.substring(split+2)).contentEquals(p.body.toByteArray(Charsets.UTF_8)))
        if(p.inReplyTo!=null)require(messageId(h.getValue("in-reply-to"))==p.inReplyTo&&references(h.getValue("references"))==p.references)
        else require(h["in-reply-to"].isNullOrBlank()&&h["references"].isNullOrBlank())
        true
    }.getOrDefault(false)

    private fun metadataHeaders(original:JsonObject):Map<String,String> {
        val lines=original.getValue("payload").jsonObject.getValue("headers").jsonArray.map {entry->
            val h=entry.jsonObject;val name=h.getValue("name").jsonPrimitive.content
            require(name.matches(Regex("[A-Za-z0-9-]{1,80}")))
            val value=h.getValue("value").jsonPrimitive.content.replace(Regex("\\r?\\n[ \\t]+")," ")
            require(value.none {it=='\r'||it=='\n'||it=='\u0000'})
            "$name: $value"
        }
        return parseHeaders(lines)
    }
    private fun parseHeaders(lines:List<String>):Map<String,String> {
        require(lines.size<=200)
        val unfolded=mutableListOf<String>()
        lines.forEach {line->require(line.length<=8192);if(line.startsWith(' ')||line.startsWith('\t')) {
            require(unfolded.isNotEmpty());unfolded[unfolded.lastIndex]+=" "+line.trim()
        }else unfolded+=line}
        val values=linkedMapOf<String,String>()
        val important=setOf("from","to","cc","bcc","date","subject","message-id","references","in-reply-to","reply-to","content-type","content-transfer-encoding")
        unfolded.forEach {line->val at=line.indexOf(':');require(at>0)
            val key=line.substring(0,at).lowercase();require(key.matches(Regex("[a-z0-9-]{1,80}")))
            if(key in important){require(key !in values);values[key]=line.substring(at+1).trim()}}
        return values
    }
    private fun addressHeader(raw:String):String {
        header(raw,1024)
        val address=if('<' in raw||'>' in raw){
            require(raw.count {it=='<'}==1&&raw.count {it=='>'}==1&&raw.endsWith('>'))
            val name=raw.substringBefore('<').trim()
            if(name.startsWith('"')){
                require(name.endsWith('"')&&name.length>=2)
                var escaped=false
                name.substring(1,name.lastIndex).forEach {c->
                    if(escaped)escaped=false else if(c=='\\')escaped=true else require(c!='"')
                }
                require(!escaped)
            }
            else require(name.none {it in "@,;:\""})
            raw.substringAfter('<').dropLast(1)
        }else raw
        return mailbox(address)
    }
    private fun messageId(raw:String):String {
        require(raw.length in 5..998&&raw.all {it.code in 33..126}&&raw.matches(Regex("<[^<>@\\s]+@[^<>@\\s]+>")))
        return raw
    }
    private fun references(raw:String):List<String> {
        if(raw.isBlank())return emptyList()
        require(raw.length<=8192)
        return raw.trim().split(Regex("[ \\t]+")).also {require(it.size<=50);it.forEach(::messageId)}
    }
    private fun decodeSubject(raw:String):String {
        val pattern=Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=")
        val compact=raw.replace(Regex("(\\?=)[ \\t]+(?==\\?)"),"$1")
        return pattern.replace(compact){m->
            val name=m.groupValues[1].lowercase();require(name.matches(Regex("[a-z0-9_.-]{1,40}"))&&Charset.isSupported(name))
            val bytes=if(m.groupValues[2].equals("B",true))Base64.getDecoder().decode(m.groupValues[3])else {
                val value=m.groupValues[3];val out=java.io.ByteArrayOutputStream();var i=0
                while(i<value.length){val c=value[i++];when(c){'_'->out.write(32);'='->{require(i+2<=value.length);out.write(value.substring(i,i+2).toInt(16));i+=2};else->{require(c.code in 33..126);out.write(c.code)}}};out.toByteArray()
            }
            Charset.forName(name).newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }
    }
    /** RFC 2047 encoded words stay <=75 chars and never split a UTF-8 code point. */
    private fun encodedSubject(value:String):String {
        val pieces=mutableListOf<String>();var current=StringBuilder();var i=0
        while(i<value.length){
            val width=if(value[i].isHighSurrogate())2 else 1
            val next=value.substring(i,i+width)
            if((current.toString()+next).toByteArray(Charsets.UTF_8).size>45){pieces+=current.toString();current=StringBuilder()}
            current.append(next);i+=width
        }
        if(current.isNotEmpty())pieces+=current.toString()
        return pieces.joinToString("\r\n "){"=?UTF-8?B?"+Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))+"?="}
    }
}
