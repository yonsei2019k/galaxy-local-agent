package com.user.localagent.skills

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class EmailAttachmentMetadata(val name:String?,val mediaType:String,val decodedSizeBytes:Long)
data class EmailDocument(
    val subject:String,
    val from:String,
    val to:String,
    val cc:String,
    val date:String?,
    val messageId:String?,
    val bodyText:String,
    val attachments:List<EmailAttachmentMetadata>,
    val warnings:List<String>,
    val bodyTruncated:Boolean
) {
    /** Mail headers/body/attachment names are quoted data, never instructions or permission. */
    val untrustedData:Boolean get()=true
}

/** Reads only the URI explicitly selected by the user. No mailbox/network/HTML/attachment execution. */
class EmailFileTools(context:Context) {
    private val app=context.applicationContext
    suspend fun readEml(uri:Uri):EmailDocument=withContext(Dispatchers.IO){
        require(uri.scheme=="content"&&!uri.authority.isNullOrBlank()){"사용자가 파일 선택기로 선택한 content URI가 필요합니다."}
        val bytes=app.contentResolver.openInputStream(uri)?.use {input->
            val output=ByteArrayOutputStream();val buffer=ByteArray(16*1024)
            while(true){currentCoroutineContext().ensureActive();val count=input.read(buffer);if(count<0)break
                require(output.size().toLong()+count<=MAX_EML_BYTES){"이메일은 2MiB 이하여야 합니다."};output.write(buffer,0,count)
            };output.toByteArray()
        }?:error("선택한 이메일을 열 수 없습니다.")
        currentCoroutineContext().ensureActive();parse(bytes)
    }
    /** Returns a draft Intent only; the caller requests review and the email app performs final send. */
    fun composeDraft(to:List<String>,subject:String,body:String):Intent=BuiltinSkills(app).composeEmail(to,subject,body)

    companion object {
        const val MAX_EML_BYTES=2*1024*1024
        const val MAX_BODY_CHARS=200000
        private const val MAX_HEADER_BYTES=65536
        private const val MAX_PARTS=64
        private const val MAX_ATTACHMENTS=32
        private const val MAX_DEPTH=8
        private val encodedWord=Regex("=\\?([^?\\s]+)\\?([bBqQ])\\?([^?]*)\\?=")
        private val fieldName=Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,80}")

        /** Bounded parser; no Android calls, remote resources, attachment extraction or tool execution. */
        fun parse(bytes:ByteArray):EmailDocument {
            require(bytes.isNotEmpty()&&bytes.size<=MAX_EML_BYTES){"EML must contain 1 byte–2MiB"}
            val source=bytes.toString(Charsets.ISO_8859_1)
            val root=entity(source)
            require(root.headers.keys.any {it in setOf("from","to","subject","date","message-id","mime-version","content-type")}){"EML headers are missing"}
            val parser=Parser()
            parser.visit(root,0)
            parser.finish()
            fun header(name:String)=root.headers[name]?.let {decodeHeader(it,parser.warnings)}.orEmpty().take(4096)
            val subject=header("subject");val from=header("from");val to=header("to");val cc=header("cc")
            return EmailDocument(subject,from,to,cc,header("date").ifBlank {null},header("message-id").ifBlank {null},parser.text.toString(),parser.attachments.toList(),parser.warnings.distinct(),parser.truncated)
        }

        private data class Entity(val headers:Map<String,String>,val body:String)
        private data class Parameterized(val value:String,val parameters:Map<String,String>)
        private fun entity(source:String):Entity {
            val crlf=source.indexOf("\r\n\r\n");val lf=source.indexOf("\n\n")
            val boundary=if(crlf>=0&&(lf<0||crlf<=lf))crlf else lf
            require(boundary>=0&&boundary<=MAX_HEADER_BYTES){"EML header block is missing or exceeds64KiB"}
            val length=if(source.startsWith("\r\n\r\n",boundary))4 else 2
            val headers=linkedMapOf<String,String>();var current:String?=null
            val lines=source.substring(0,boundary).replace("\r\n","\n").split('\n')
            require(lines.size<=1024){"Too many EML header lines"}
            for(line in lines){
                require(line.length<=8192&&'\u0000' !in line){"Invalid EML header line"}
                if(line.startsWith(' ')||line.startsWith('\t')){
                    val key=current?:error("Header continuation has no preceding field")
                    headers[key]=headers.getValue(key)+" "+line.trim()
                }else{
                    val colon=line.indexOf(':');require(colon>0){"Invalid EML header field"}
                    val name=line.substring(0,colon);require(fieldName.matches(name)){"Invalid EML header name"}
                    val key=name.lowercase(Locale.ROOT)
                    val singleton=key in setOf("from","to","cc","subject","date","message-id","mime-version","content-type","content-disposition","content-transfer-encoding")
                    require(!singleton||key !in headers){"Duplicate EML header field is ambiguous: $key"}
                    current=key;val value=line.substring(colon+1).trim()
                    headers[key]=headers[key]?.let {"$it; $value"}?:value
                }
            }
            return Entity(headers,source.substring(boundary+length))
        }
        private class Parser {
            val warnings=mutableListOf<String>()
            val attachments=mutableListOf<EmailAttachmentMetadata>()
            val text=StringBuilder()
            var truncated=false
            var parts=0
            private var plainSeen=false
            private val htmlFallback=StringBuilder()
            private var htmlTruncated=false
            private var htmlSeen=false
            fun visit(part:Entity,depth:Int){
                require(depth<=MAX_DEPTH&&++parts<=MAX_PARTS){"EML MIME nesting/part limit exceeded"}
                val type=parameters(part.headers["content-type"]?:"text/plain")
                val disposition=parameters(part.headers["content-disposition"].orEmpty())
                val name=parameterName(disposition.parameters,"filename",warnings)?:parameterName(type.parameters,"name",warnings)
                val attachment=disposition.value=="attachment"||name!=null
                if(type.value.startsWith("multipart/")&&!attachment){
                    val boundary=type.parameters["boundary"]?:error("Multipart boundary missing")
                    require(boundary.length in 1..200&&boundary.none {it=='\r'||it=='\n'||it=='\u0000'}){"Invalid multipart boundary"}
                    val children=multipart(part.body,boundary)
                    children.forEach {visit(entity(it),depth+1)}
                    return
                }
                val decoded=transfer(part.body,part.headers["content-transfer-encoding"].orEmpty())
                if(attachment||!type.value.startsWith("text/")){
                    require(attachments.size<MAX_ATTACHMENTS){"Too many EML attachments"}
                    attachments+=EmailAttachmentMetadata(safeName(name),type.value.ifBlank {"application/octet-stream"},decoded.size.toLong())
                    return
                }
                if(type.value !in setOf("text/plain","text/html")){
                    warnings+="${type.value} 본문 형식은 제외했습니다."
                    return
                }
                val charset=type.parameters["charset"]?:"UTF-8"
                val value=runCatching {decodeText(decoded,charset)}.getOrElse {warnings+="지원하지 않거나 손상된 문자셋($charset) 본문은 제외했습니다.";return}
                require('\u0000' !in value){"EML text contains NUL"}
                if(type.value=="text/html"){
                    htmlSeen=true
                    val converted=htmlToText(value)
                    if(htmlFallback.isNotEmpty()&&htmlFallback.length<MAX_BODY_CHARS)htmlFallback.append('\n')
                    val available=(MAX_BODY_CHARS-htmlFallback.length).coerceAtLeast(0)
                    val excerpt=converted.text.take(available).let {if(it.lastOrNull()?.isHighSurrogate()==true)it.dropLast(1) else it}
                    htmlFallback.append(excerpt)
                    htmlTruncated=htmlTruncated||converted.truncated||converted.text.length>excerpt.length
                    if(converted.malformed)warnings+="일부 손상된 HTML 구조를 제외했습니다."
                    return
                }
                plainSeen=true
                val remaining=(MAX_BODY_CHARS-text.length).coerceAtLeast(0)
                if(text.isNotEmpty()&&remaining>0)text.append('\n')
                val allowed=(MAX_BODY_CHARS-text.length).coerceAtLeast(0)
                text.append(value.take(allowed));if(value.length>allowed)truncated=true
            }
            fun finish(){
                if(!plainSeen&&htmlSeen){
                    text.append(htmlFallback);truncated=htmlTruncated
                    warnings+="HTML 본문을 로컬 일반 텍스트로 변환했습니다. 스크립트·스타일·원격 리소스를 실행하거나 불러오지 않았습니다."
                    if(truncated)warnings+="이메일 본문이200000자로 잘렸습니다."
                }else if(htmlSeen)warnings+="HTML 대안 대신 일반 텍스트 본문을 사용했습니다."
            }
        }
        private data class HtmlText(val text:String,val truncated:Boolean,val malformed:Boolean)
        /** Finite linear scan: no HTML renderer, URL loading, executable parsing or backtracking regex. */
        private fun htmlToText(html:String):HtmlText {
            val result=StringBuilder();val ignored=mutableListOf<String>();var index=0;var truncated=false;var malformed=false
            val suppressed=setOf("script","style","iframe","object","noscript","template","svg","math")
            val blocks=setOf("br","p","div","li","ul","ol","table","tr","td","th","h1","h2","h3","h4","h5","h6","pre","section","article","blockquote","hr")
            fun append(value:String){
                for(char in value){
                    if(char.isWhitespace()){
                        if(result.isEmpty()||result.last().isWhitespace())continue
                        if(result.length<MAX_BODY_CHARS)result.append(' ') else truncated=true
                    }else if(char.code>=32&&char.code!=127){if(result.length<MAX_BODY_CHARS)result.append(char) else truncated=true}
                }
            }
            fun newline(){if(result.isNotEmpty()&&result.last()!='\n'){if(result.last()==' ')result.setLength(result.length-1);if(result.length<MAX_BODY_CHARS)result.append('\n')else truncated=true}}
            while(index<html.length){
                if(html.startsWith("<!--",index)){
                    val end=html.indexOf("-->",index+4);if(end<0){malformed=true;break};index=end+3;continue
                }
                if(html[index]=='<'&&index+1<html.length&&(html[index+1].isLetter()||html[index+1] in "/!?")){
                    var end=index+1;var quote:Char?=null
                    while(end<html.length&&end-index<=8192){val char=html[end];if(quote!=null){if(char==quote)quote=null}else if(char=='\''||char=='"')quote=char else if(char=='>')break;end++}
                    if(end>=html.length||end-index>8192){malformed=true;break}
                    val raw=html.substring(index+1,end).trim();val closing=raw.startsWith('/')
                    val name=raw.trimStart('/',' ','\t','\r','\n').takeWhile {it in 'a'..'z'||it in 'A'..'Z'||it in '0'..'9'}.lowercase(Locale.ROOT)
                    if(ignored.isNotEmpty()){
                        if(closing&&name==ignored.last())ignored.removeAt(ignored.lastIndex)
                        else if(!closing&&name in suppressed){if(ignored.size>=32){malformed=true;break};ignored+=name}
                    }else if(!closing&&name in suppressed)ignored+=name
                    else if(name in blocks)newline()
                    index=end+1;continue
                }
                if(ignored.isNotEmpty()){index++;continue}
                if(html[index]=='&'){
                    var end=index+1
                    while(end<html.length&&end<=index+16&&html[end]!=';')end++
                    if(end in (index+2)..minOf(html.length-1,index+16)&&html[end]==';'){
                        val code=html.substring(index+1,end)
                        val decoded=when(code){"amp"->"&";"lt"->"<";"gt"->">";"quot"->"\"";"apos","#39"->"'";"nbsp"->" ";"copy"->"©";"reg"->"®";"ndash"->"–";"mdash"->"—";"hellip"->"…";else->{
                            val number=if(code.startsWith("#x",true))code.substring(2).toIntOrNull(16) else if(code.startsWith('#'))code.substring(1).toIntOrNull() else null
                            number?.takeIf {it in 32..0x10FFFF&&it !in 0xD800..0xDFFF}?.let {String(Character.toChars(it))}
                        }}
                        if(decoded!=null){append(decoded);index=end+1;continue}
                    }
                }
                append(html[index++].toString())
            }
            if(ignored.isNotEmpty())malformed=true
            if(result.lastOrNull()?.isHighSurrogate()==true){result.setLength(result.length-1);truncated=true}
            return HtmlText(result.toString().trim(),truncated,malformed)
        }
        private fun multipart(body:String,boundary:String):List<String> {
            val marker="--$boundary";val close="$marker--"
            val children=mutableListOf<String>();var current:StringBuilder?=null;var closed=false
            for(line in body.replace("\r\n","\n").split('\n')){
                val delimiter=line.trimEnd(' ','\t')
                if(delimiter==marker||delimiter==close){
                    current?.let {children+=it.toString().removeSuffix("\r\n")}
                    require(children.size<=MAX_PARTS){"Too many MIME parts"}
                    if(delimiter==close){closed=true;current=null;break}
                    current=StringBuilder()
                }else current?.append(line)?.append("\r\n")
            }
            require(closed&&children.isNotEmpty()){"Multipart body is incomplete"}
            return children
        }
        private fun transfer(body:String,encoding:String):ByteArray=when(encoding.trim().lowercase(Locale.ROOT)){
            "base64"->Base64.getDecoder().decode(body.filterNot {it==' '||it=='\t'||it=='\r'||it=='\n'})
            "quoted-printable"->quotedPrintable(body,false)
            "","7bit","8bit","binary"->body.toByteArray(Charsets.ISO_8859_1)
            else->error("Unsupported MIME transfer encoding")
        }
        private fun quotedPrintable(value:String,header:Boolean):ByteArray {
            val output=ByteArrayOutputStream();var index=0
            while(index<value.length){
                val char=value[index++]
                if(char=='='&&index<value.length&&value[index]=='\n'){index++;continue}
                if(char=='='&&index+1<value.length&&value[index]=='\r'&&value[index+1]=='\n'){index+=2;continue}
                if(char=='='){
                    require(index+1<value.length){"Truncated quoted-printable escape"}
                    val high=value[index++].digitToIntOrNull(16);val low=value[index++].digitToIntOrNull(16)
                    require(high!=null&&low!=null){"Invalid quoted-printable escape"};output.write((high shl 4)or low)
                }else output.write(if(header&&char=='_')32 else char.code)
            };return output.toByteArray()
        }
        private fun decodeText(bytes:ByteArray,name:String):String {
            val normalized=name.trim().lowercase(Locale.ROOT).replace('_','-')
            val charset=when(normalized){"utf-8","utf8"->Charsets.UTF_8;"euc-kr","euckr","ks-c-5601-1987","ks-c-5601-1989","korean"->Charset.forName("EUC-KR");"us-ascii","ascii"->Charsets.US_ASCII;"iso-8859-1","latin1"->Charsets.ISO_8859_1;else->error("Unsupported mail charset")}
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        }
        private fun decodeHeader(value:String,warnings:MutableList<String>):String {
            // RFC2047 suppresses whitespace between adjacent encoded words.
            val joined=value.replace(Regex("(?<=\\?=)\\s+(?==\\?)"),"")
            return encodedWord.replace(joined){match->
                runCatching {
                    val bytes=if(match.groupValues[2].equals("b",true))Base64.getDecoder().decode(match.groupValues[3]) else quotedPrintable(match.groupValues[3],true)
                    decodeText(bytes,match.groupValues[1])
                }.getOrElse {warnings+="일부 인코딩된 헤더를 해석하지 못했습니다.";match.value}
            }.let {decoded->
                if(decoded.any {it.code>=128}&&!encodedWord.containsMatchIn(value))runCatching {decodeText(decoded.toByteArray(Charsets.ISO_8859_1),"UTF-8")}.getOrDefault(decoded) else decoded
            }.filterNot {it=='\r'||it=='\n'||it=='\u0000'}
        }
        private fun parameters(header:String):Parameterized {
            val pieces=mutableListOf<String>();val piece=StringBuilder();var quoted=false;var escaped=false
            for(char in header){
                if(escaped){piece.append(char);escaped=false}
                else if(char=='\\'&&quoted){escaped=true;piece.append(char)}
                else if(char=='"'){quoted=!quoted;piece.append(char)}
                else if(char==';'&&!quoted){pieces+=piece.toString();piece.setLength(0)}
                else piece.append(char)
            }
            require(!quoted&&!escaped){"Unclosed MIME parameter quote"};pieces+=piece.toString()
            val result=linkedMapOf<String,String>()
            for(raw in pieces.drop(1)){
                val equal=raw.indexOf('=');if(equal<1)continue
                val name=raw.substring(0,equal).trim().lowercase(Locale.ROOT);val value=raw.substring(equal+1).trim().removeSurrounding("\"").replace("\\\"","\"").replace("\\\\","\\")
                require(name !in result){"Duplicate MIME parameter"};result[name]=value
            }
            return Parameterized(pieces.first().trim().lowercase(Locale.ROOT),result)
        }
        private fun parameterName(values:Map<String,String>,name:String,warnings:MutableList<String>):String? {
            values["$name*"]?.let {extended->
                return runCatching {
                    val first=extended.indexOf('\'');val second=extended.indexOf('\'',first+1);require(first>0&&second>first)
                    val data=extended.substring(second+1);val output=ByteArrayOutputStream();var index=0
                    while(index<data.length){val char=data[index++];if(char=='%'){require(index+1<data.length);output.write(data.substring(index,index+2).toInt(16));index+=2}else{require(char.code<128);output.write(char.code)}}
                    decodeText(output.toByteArray(),extended.substring(0,first))
                }.getOrElse {warnings+="첨부 파일 이름의 확장 인코딩을 해석하지 못했습니다.";null}
            }
            return values[name]?.let {decodeHeader(it,warnings)}
        }
        private fun safeName(name:String?):String?=name?.replace('\\','/')?.substringAfterLast('/')
            ?.filterNot {it.code<32||it.code==127||it in '\u202A'..'\u202E'||it in '\u2066'..'\u2069'}?.take(255)?.ifBlank {null}
    }
}
