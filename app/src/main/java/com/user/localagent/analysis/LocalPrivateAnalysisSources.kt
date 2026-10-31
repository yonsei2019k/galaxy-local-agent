package com.user.localagent.analysis

import android.Manifest
import android.content.Context
import android.provider.Telephony
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.user.localagent.audio.AudioTranscriptChunk
import com.user.localagent.audio.RecordingTranscriptManifest
import com.user.localagent.core.*
import com.user.localagent.data.LocalRepository
import com.user.localagent.data.NoteRecord
import com.user.localagent.imports.AndroidPhoneRecordSource
import com.user.localagent.imports.PhoneRecordSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.UUID as JavaUuid
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.security.MessageDigest
import java.time.OffsetDateTime
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Exact source ranges. Audio values identify decoder windows, not aligned words or speakers. */
data class PrivateSourceTimeRef(val startByte:Int,val endByte:Int,val atEpochMillis:Long?=null,
    val startMillis:Long?=null,val endMillis:Long?=null,val referenceId:String?=null,
    val participantKey:String?=null,val threadKey:String?=null,val recordType:Int?=null) {
    override fun toString()="PrivateSourceTimeRef(startByte=$startByte,endByte=$endByte,privateTimeAndIdentityOmitted=true)"
}
data class PrivateSourceSnapshot(val coreSource:PrivateAnalysisSource,val label:String,val sourceType:String,
    val recordCount:Long?,val metadata:JsonObject,val timeRefs:List<PrivateSourceTimeRef>,val originalSha256:String?=null) {
    override fun toString()="PrivateSourceSnapshot(sourceType=$sourceType,recordCount=$recordCount,privateMetadataOmitted=true)"
}

internal interface PrivateAnalysisNoteStore {
    suspend fun list():List<NoteRecord>
    suspend fun read(id:String):NoteRecord?
    suspend fun save(title:String,body:String,collection:String,id:String):NoteRecord
    suspend fun discardIncomplete(snapshotId:String,expected:List<IncompletePrivateSourceNote>):PrivateSourceCleanupResult
}
data class IncompletePrivateSourceNote(val noteId:String,val bodySha256:String)
data class PrivateSourceCleanupResult(val deleted:Int=0,val missing:Int=0,val retainedChanged:Int=0,val retainedUnverified:Int=0) {
    operator fun plus(other:PrivateSourceCleanupResult)=PrivateSourceCleanupResult(deleted+other.deleted,missing+other.missing,retainedChanged+other.retainedChanged,retainedUnverified+other.retainedUnverified)
}
internal data class PrivateSmsRow(val sourceId:Long,val atEpochMillis:Long?,val type:Int?,val address:String?,val threadId:Long?,val body:String,val bodyPresent:Boolean=true) {
    override fun toString()="PrivateSmsRow(privateDataOmitted=true)"
}
internal data class PrivateSmsScan(val providerCount:Long?,val visited:Long,val exhausted:Boolean)
internal interface PrivateSmsReader {
    fun hasPermission():Boolean
    suspend fun scan(start:Long?,end:Long?,accept:(PrivateSmsRow)->Boolean):PrivateSmsScan
}
internal fun interface PrivateSourceIdentity { fun alias(domain:String,value:String):String }

/** Reads only official SMS or the supplied repository's scoped notes. Never searches private paths or contacts. */
class LocalPrivateAnalysisSources internal constructor(
    private val notes:PrivateAnalysisNoteStore,private val scopeId:String,private val smsReader:PrivateSmsReader,
    private val identity:PrivateSourceIdentity,private val maxSmsRows:Int=100_000,
    private val maxTextBytes:Int=PrivateAnalysisSource.MAX_SOURCE_BYTES,
) {
    constructor(context:Context,repository:LocalRepository,scopeId:String,source:PhoneRecordSource=AndroidPhoneRecordSource(context),
        cleanupIncomplete:suspend(String,List<IncompletePrivateSourceNote>)->PrivateSourceCleanupResult={_,expected->PrivateSourceCleanupResult(retainedUnverified=expected.size)}):
        this(object:PrivateAnalysisNoteStore {
            override suspend fun list()=repository.notes.first()
            override suspend fun read(id:String)=repository.getNote(id)
            override suspend fun save(title:String,body:String,collection:String,id:String)=repository.saveNote(title,body,collection,id)
            override suspend fun discardIncomplete(snapshotId:String,expected:List<IncompletePrivateSourceNote>)=cleanupIncomplete(snapshotId,expected)
        },scopeId,AndroidPrivateSmsReader(source),KeystorePrivateSourceIdentity())

    init {require(scopeId.isNotBlank()&&scopeId.length<=256);require(maxSmsRows in 1..100_000&&maxTextBytes in 128..PrivateAnalysisSource.MAX_SOURCE_BYTES)}

    suspend fun sms(startIso:String?=null,endIso:String?=null):PrivateSourceSnapshot {
        val snapshotId=JavaUuid.randomUUID().toString();val created=mutableListOf<IncompletePrivateSourceNote>()
        try {return withContext(Dispatchers.IO) {
        val start=startIso?.let {OffsetDateTime.parse(it).toInstant().toEpochMilli()}
        val end=endIso?.let {OffsetDateTime.parse(it).toInstant().toEpochMilli()}
        require(start==null||start>=0);require(end==null||end>=0);require(start==null||end==null||end>start)
        check(smsReader.hasPermission()){"Android 문자 읽기 권한이 필요합니다."}
        val text=StringBuilder();val times=mutableListOf<PrivateSourceTimeRef>();val seen=mutableSetOf<Long>()
        var bytes=0;var included=0;var unknownTimes=0;var missingParticipants=0;var missingThreads=0;var missingBodies=0
        val limitations=linkedSetOf<String>()
        val scan=smsReader.scan(start,end) {row->
            require(row.sourceId>=0&&seen.add(row.sourceId)){"SMS 제공자의 안정적인 행 식별자를 확인하지 못했습니다."}
            if(included==maxSmsRows){limitations+="record_limit";false}
            else {
                val recordKey=identity.alias("sms-record",row.sourceId.toString())
                val participant=row.address?.takeIf {it.isNotEmpty()}?.let {identity.alias("sms-participant",it)}
                val thread=row.threadId?.takeIf {it>=0}?.let {identity.alias("sms-thread",it.toString())}
                val line=buildJsonObject {
                    put("recordKey",recordKey);put("participantKey",participant);put("threadKey",thread)
                    put("atEpochMillis",row.atEpochMillis);put("type",row.type);put("body",row.body);put("bodyPresent",row.bodyPresent)
                }.toString()+"\n"
                val lineBytes=line.toByteArray(Charsets.UTF_8).size
                if(lineBytes>maxTextBytes-bytes){limitations+="byte_limit";false}
                else {
                    if(row.atEpochMillis==null)unknownTimes++
                    if(participant==null)missingParticipants++
                    if(thread==null)missingThreads++
                    if(!row.bodyPresent){missingBodies++;limitations+="missing_body"}
                    times+=PrivateSourceTimeRef(bytes,bytes+lineBytes,atEpochMillis=row.atEpochMillis,referenceId=recordKey,
                        participantKey=participant,threadKey=thread,recordType=row.type)
                    text.append(line);bytes+=lineBytes;included++;true
                }
            }
        }
        check(smsReader.hasPermission()){"문자 분석 자료를 읽는 동안 권한이 해제되었습니다."}
        if(!scan.exhausted)limitations+="provider_not_exhausted"
        if(scan.providerCount!=null&&scan.providerCount!=included.toLong())limitations+="provider_count_differs_from_included"
        val complete=scan.exhausted&&limitations.isEmpty()
        val sourceId="sms-snapshot:$snapshotId"
        val sourceText=text.toString()
        val core=PrivateAnalysisSource.create(scopeId,sourceId,PrivateEvidenceKind.SMS,sourceText,
            if(complete)PrivateTextCompleteness.COMPLETE_SELECTED_TEXT else PrivateTextCompleteness.PARTIAL_SELECTED_TEXT)
        val result=PrivateSourceSnapshot(core,"Android SMS 조회 자료","sms",included.toLong(),buildJsonObject {
            put("source","android_sms_provider");put("bodyFormat","exact_provider_body_in_jsonl_v1")
            put("providerRecordCount",scan.providerCount);put("includedRecordCount",included);put("providerVisitedCount",scan.visited)
            put("providerCursorExhausted",scan.exhausted)
            put("enumerationComplete",complete);put("fullIncludedBodies",missingBodies==0);put("missingBodyCount",missingBodies);put("snapshotConsistency","observed_provider_cursor_not_transactionally_frozen")
            put("startEpochMillis",start);put("endEpochMillis",end);put("endInclusive",false);put("endExclusive",true)
            put("missingTimestampCount",unknownTimes);put("missingParticipantIdentityCount",missingParticipants);put("missingThreadIdentityCount",missingThreads)
            put("participantIdentity","keystore_hmac_of_exact_provider_address");put("threadIdentity","keystore_hmac_of_provider_thread_id")
            put("samePersonMayHaveDifferentProviderAddresses",true);put("contactNamesRead",false);put("providerAddressColumnReturned",false)
            put("mmsIncluded",false);put("messengerIncluded",false);put("wholePhoneHistoryVerified",false)
            put("limitations",JsonArray(limitations.map(::JsonPrimitive)));put("textHashBasis","exact_decoded_text_utf8")
            put("snapshotId",snapshotId);put("sourceLocator",buildJsonObject {put("kind","sms");put("snapshotId",snapshotId)})
        },times)
        persistSmsSnapshot(snapshotId,result,sourceText,created)
        result
        }}catch(error:Throwable){
            if(created.isNotEmpty())withContext(NonCancellable+Dispatchers.IO){try {notes.discardIncomplete(snapshotId,created.toList())}catch(cleanup:Exception){error.addSuppressed(cleanup)}}
            throw error
        }
    }

    /** Immutable capture resume: permission and scope are live; the mutable provider is not queried again. */
    suspend fun cachedSms(snapshotId:String):PrivateSourceSnapshot=withContext(Dispatchers.IO) {
        require(UUID.matches(snapshotId));check(smsReader.hasPermission()){"SMS 저장 자료를 읽으려면 현재 문자 읽기 권한이 필요합니다."}
        val roots=notes.list().filter {it.collection==SOURCE_COLLECTION}.mapNotNull {note->parseObject(note.body)?.takeIf {it.string("kind")=="sms_snapshot"&&it.string("snapshotId")==snapshotId}?.let {note to it}}
        require(roots.size==1){"현재 범위의 완료된 SMS 자료 묶음을 찾지 못했습니다."}
        val (root,meta)=roots.single();readUnchanged(root)
        require(meta.int("schemaVersion")==1&&meta.string("scopeId")==scopeId&&meta.string("sourceId")=="sms-snapshot:$snapshotId")
        val expectedHash=meta.string("contentSha256")?.takeIf {SHA.matches(it)}?:error("SMS 원문 해시가 없습니다.")
        val expectedChunks=meta.int("chunkCount")?:error("SMS 조각 수가 없습니다.");require(expectedChunks in 0..3600)
        val pages=meta["pages"] as? JsonArray?:error("SMS 페이지 목록이 없습니다.");require(pages.size==(expectedChunks+59)/60&&pages.size<=80)
        val text=StringBuilder();val captured=mutableListOf(root);val seen=mutableSetOf<String>();var chunks=0;var bytes=0
        for((pageIndex,element)in pages.withIndex()){
            currentCoroutineContext().ensureActive();check(smsReader.hasPermission()){"SMS 저장 자료를 읽는 동안 권한이 해제되었습니다."}
            val reference=element as? JsonObject?:error("SMS 페이지 형식 오류")
            val id=reference.string("noteId")?.takeIf {UUID.matches(it)}?:error("SMS 페이지 ID 오류");require(seen.add(id))
            val page=notes.read(id)?:error("SMS 페이지가 삭제되거나 범위에서 해제되었습니다.")
            require(page.collection==SOURCE_COLLECTION&&sha(page.body)==reference.string("sha256"))
            val body=parseObject(page.body)?:error("SMS 페이지 형식 오류")
            require(body.string("kind")=="sms_snapshot_page"&&body.int("schemaVersion")==1&&body.string("snapshotId")==snapshotId&&body.int("pageIndex")==pageIndex)
            val references=body["chunks"] as? JsonArray?:error("SMS 조각 목록 오류");require(references.size in 1..60)
            for(chunkElement in references){
                currentCoroutineContext().ensureActive();val ref=chunkElement as? JsonObject?:error("SMS 조각 참조 오류")
                val chunkId=ref.string("noteId")?.takeIf {UUID.matches(it)}?:error("SMS 조각 ID 오류");require(seen.add(chunkId)&&ref.int("index")==chunks)
                val note=notes.read(chunkId)?:error("SMS 원문 조각을 읽지 못했습니다.")
                require(note.collection==SOURCE_COLLECTION&&sha(note.body)==ref.string("sha256"))
                val header=parseObject(note.body.substringBefore('\n'))?:error("SMS 조각 형식 오류")
                require(header.string("kind")=="sms_snapshot_chunk"&&header.int("schemaVersion")==1&&header.string("snapshotId")==snapshotId&&header.int("index")==chunks)
                val part=note.body.substringAfter('\n',"");require(sha(part)==header.string("textSha256"));bytes=boundedAdd(bytes,part.toByteArray(Charsets.UTF_8).size)
                text.append(part);captured+=note;chunks++
            }
            captured+=page
        }
        val sourceText=text.toString();require(chunks==expectedChunks&&bytes==meta.int("byteLength")&&sha(sourceText)==expectedHash)
        val source=PrivateAnalysisSource.create(scopeId,meta.string("sourceId")!!,PrivateEvidenceKind.SMS,sourceText,
            PrivateTextCompleteness.entries.firstOrNull {it.name==meta.string("completeness")}?:error("SMS 범위 정보 오류"))
        val timeRefs=mutableListOf<PrivateSourceTimeRef>();var position=0
        sourceText.lineSequence().filter {it.isNotEmpty()}.forEach {line->
            currentCoroutineContext().ensureActive();require(timeRefs.size<100_000)
            val row=parseObject(line)?:error("SMS 저장 행 형식 오류");require((row["body"] as? JsonPrimitive)?.isString==true)
            val after=position+(line+"\n").toByteArray(Charsets.UTF_8).size
            timeRefs+=PrivateSourceTimeRef(position,after,atEpochMillis=row.long("atEpochMillis"),referenceId=row.string("recordKey"),
                participantKey=row.string("participantKey"),threadKey=row.string("threadKey"),recordType=row.int("type"));position=after
        }
        require(position==bytes&&timeRefs.size.toLong()==meta.long("recordCount"))
        captured.forEach {readUnchanged(it)};check(smsReader.hasPermission()){"SMS 저장 자료를 읽는 동안 권한이 해제되었습니다."}
        PrivateSourceSnapshot(source,"Android SMS 저장 자료","sms",meta.long("recordCount"),meta["metadata"] as? JsonObject?:error("SMS 범위 설명 오류"),timeRefs)
    }

    private suspend fun persistSmsSnapshot(snapshotId:String,snapshot:PrivateSourceSnapshot,text:String,created:MutableList<IncompletePrivateSourceNote>) {
        suspend fun save(body:String):NoteRecord {
            currentCoroutineContext().ensureActive();require(body.length in 1..12000)
            val id=JavaUuid.randomUUID().toString();created+=IncompletePrivateSourceNote(id,sha(body))
            val note=notes.save("문자 분석 원문",body,SOURCE_COLLECTION,id);require(note.id==id&&note.body==body&&note.collection==SOURCE_COLLECTION)
            readUnchanged(note);return note
        }
        val refs=mutableListOf<JsonObject>();var position=0;var index=0
        while(position<text.length){
            var end=minOf(position+6000,text.length);if(end<text.length&&text[end-1].isHighSurrogate()&&text[end].isLowSurrogate())end--
            val part=text.substring(position,end)
            val header=buildJsonObject {put("kind","sms_snapshot_chunk");put("schemaVersion",1);put("snapshotId",snapshotId);put("index",index);put("textSha256",sha(part))}
            val note=save(header.toString()+"\n"+part)
            refs+=buildJsonObject {put("noteId",note.id);put("sha256",sha(note.body));put("index",index)}
            index++;position=end
        }
        require(refs.size<=3600)
        val pages=mutableListOf<JsonObject>()
        for((pageIndex,group)in refs.chunked(60).withIndex()){
            val body=buildJsonObject {put("kind","sms_snapshot_page");put("schemaVersion",1);put("snapshotId",snapshotId);put("pageIndex",pageIndex);put("chunks",JsonArray(group))}.toString()
            val note=save(body);pages+=buildJsonObject {put("noteId",note.id);put("sha256",sha(note.body))}
        }
        require(pages.size<=80)
        val root=buildJsonObject {
            put("kind","sms_snapshot");put("schemaVersion",1);put("snapshotId",snapshotId);put("scopeId",scopeId);put("sourceId",snapshot.coreSource.ref.sourceId)
            put("contentSha256",snapshot.coreSource.ref.contentSha256);put("byteLength",snapshot.coreSource.utf8ByteLength);put("completeness",snapshot.coreSource.completeness.name)
            put("recordCount",snapshot.recordCount);put("chunkCount",refs.size);put("pages",JsonArray(pages));put("metadata",snapshot.metadata)
        }.toString()
        save(root);currentCoroutineContext().ensureActive()
    }

    suspend fun conversationImport(contentSha256:String):PrivateSourceSnapshot=withContext(Dispatchers.IO) {
        require(SHA.matches(contentSha256)){"가져오기 목록의 정확한 원본 SHA-256이 필요합니다."}
        val entries=importEntries(notes.list()).filter {it.meta.string("sha256")==contentSha256}
        val manifests=entries.filter {it.meta.string("kind")=="manifest"}.sortedWith(compareByDescending<ImportEntry>{it.note.updatedAt}.thenBy {it.note.id})
        val manifest=manifests.firstOrNull {completedImportChunks(entries,it)!=null}?:error("현재 범위에서 완료된 가져오기 원문을 찾지 못했습니다.")
        val parts=completedImportChunks(entries,manifest)!!
        val text=StringBuilder();var byteCount=0
        val captured=mutableListOf(manifest.note)
        for(entry in parts){
            currentCoroutineContext().ensureActive();val current=readUnchanged(entry.note)
            val body=current.body.substringAfter('\n',"")
            byteCount=boundedAdd(byteCount,body.toByteArray(Charsets.UTF_8).size);text.append(body);captured+=current
        }
        val manifestCurrent=readUnchanged(manifest.note)
        require(manifestCurrent.body==manifest.note.body)
        val encoding=manifest.meta.string("encoding")?:error("가져온 원문의 문자 인코딩을 확인할 수 없습니다.")
        require(encoding in setOf("UTF-8","EUC-KR")){"지원하는 가져오기 문자 인코딩이 아닙니다."}
        val sourceText=text.toString()
        val encoded=Charset.forName(encoding).newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(sourceText))
        val originalBytes=ByteArray(encoded.remaining());encoded.get(originalBytes)
        require(sha(originalBytes)==contentSha256){"가져오기 원문을 재구성한 해시가 원본 기록과 다릅니다. 원본 파일을 다시 선택해주세요."}
        captured.forEach {readUnchanged(it)}
        val core=PrivateAnalysisSource.create(scopeId,"conversation-import:$contentSha256",PrivateEvidenceKind.IMPORTED_CONVERSATION,sourceText,PrivateTextCompleteness.COMPLETE_SELECTED_TEXT)
        PrivateSourceSnapshot(core,"가져온 대화 원문","conversation_import",manifest.meta.long("recognized_messages"),buildJsonObject {
            put("source","owner_imported_single_file");put("originalFileSha256",contentSha256);put("originalFileHashRecomputed",true)
            put("originalEncoding",encoding);put("decodedTextSha256",core.ref.contentSha256);put("textHashBasis","exact_decoded_text_utf8")
            put("chunkCount",parts.size);put("enumerationComplete",true);put("fullImportedText",true)
            put("recognizedFormat",manifest.meta.string("format")?:"unknown");put("recordCountBasis","importer_recognized_messages_only")
            put("firstTimestamp",manifest.meta.string("first_timestamp"));put("lastTimestamp",manifest.meta.string("last_timestamp"))
            put("timestampAlignment","source_text_only_no_message_alignment");put("outsideSelectedFileIncluded",false);put("wholePhoneHistoryVerified",false)
            put("sourceLocator",buildJsonObject {put("kind","conversation_import");put("contentSha256",contentSha256)})
        },emptyList(),contentSha256)
    }

    suspend fun audioTranscript(transcriptId:String):PrivateSourceSnapshot=withContext(Dispatchers.IO) {
        if(transcriptId.startsWith("legacy:"))return@withContext legacyTranscript(transcriptId.removePrefix("legacy:"))
        require(UUID.matches(transcriptId)){"전사 목록에서 선택한 전사 ID가 필요합니다."}
        val all=notes.list()
        val roots=all.filter {it.collection==RecordingTranscriptManifest.COLLECTION}.mapNotNull {note->
            parseObject(note.body)?.takeIf {it.string("kind")==RecordingTranscriptManifest.KIND&&it.string("transcriptId")==transcriptId}?.let {note to it}
        }
        require(roots.size==1){"현재 범위에서 하나의 완료된 전사 묶음을 확인해야 합니다."}
        val (root,meta)=roots.single();readUnchanged(root)
        require(meta.int("schemaVersion")==RecordingTranscriptManifest.SCHEMA_VERSION&&meta["fullTextPersisted"]?.jsonPrimitive?.booleanOrNull==true)
        require(meta.string("sourceIdentity")=="recording-transcript:$transcriptId"&&meta.string("sourceTextHashFormat")=="ordered-note-bodies-utf8-lf-v1")
        val count=meta.int("chunkCount")?:error("전사 조각 개수를 확인할 수 없습니다.")
        val duration=meta.long("durationMillis")?:error("전사 시간 범위를 확인할 수 없습니다.")
        val expectedHash=meta.string("sourceTextSha256")?.takeIf {SHA.matches(it)}?:error("전사 원문 해시가 없습니다.")
        require(count in 1..RecordingTranscriptManifest.MAX_CHUNKS&&duration in 1..RecordingTranscriptManifest.MAX_DURATION_MILLIS)
        val pages=(meta["pages"] as? JsonArray)?:error("전사 페이지 목록이 필요합니다.")
        require(pages.size==(count+RecordingTranscriptManifest.PAGE_SIZE-1)/RecordingTranscriptManifest.PAGE_SIZE)
        val chunks=mutableListOf<AudioTranscriptChunk>();val captured=mutableListOf(root);val pageIds=mutableSetOf<String>()
        for((index,pageElement)in pages.withIndex()){
            currentCoroutineContext().ensureActive()
            val page=pageElement as? JsonObject?:error("전사 페이지 형식 오류")
            val id=page.string("noteId")?.takeIf {UUID.matches(it)}?:error("전사 페이지 ID 오류")
            require(pageIds.add(id)){"전사 페이지가 중복됐습니다."}
            val pageNote=notes.read(id)?:error("현재 범위에서 전사 페이지를 읽지 못했습니다.")
            require(pageNote.collection==RecordingTranscriptManifest.COLLECTION&&sha(pageNote.body)==page.string("sha256"))
            val body=parseObject(pageNote.body)?:error("전사 페이지 형식 오류")
            require(body.int("schemaVersion")==RecordingTranscriptManifest.SCHEMA_VERSION&&body.string("kind")=="${RecordingTranscriptManifest.KIND}_page"&&body.string("transcriptId")==transcriptId&&body.int("pageIndex")==index)
            val rows=body["chunks"] as? JsonArray?:error("전사 조각 목록이 없습니다.")
            require(rows.size in 1..RecordingTranscriptManifest.PAGE_SIZE)
            rows.forEach {rowElement->
                val row=rowElement as? JsonObject?:error("전사 조각 형식 오류")
                chunks+=AudioTranscriptChunk(row.int("index")?:-1,row.string("noteId").orEmpty(),row.long("startMillis")?:-1,row.long("endMillis")?:-1,row.string("noteSha256").orEmpty())
            }
            captured+=pageNote
        }
        require(chunks.size==count);RecordingTranscriptManifest.validateChunks(chunks,duration)
        val text=StringBuilder();val timeRefs=mutableListOf<PrivateSourceTimeRef>();var byteCount=0
        for(chunk in chunks){
            currentCoroutineContext().ensureActive()
            val note=notes.read(chunk.noteId)?:error("현재 범위에서 전사 원문을 읽지 못했습니다.")
            require(note.collection=="audio-transcripts"&&sha(note.body)==chunk.noteSha256){"저장된 전사 원문의 해시가 다릅니다."}
            val line=note.body+"\n";val after=boundedAdd(byteCount,line.toByteArray(Charsets.UTF_8).size)
            timeRefs+=PrivateSourceTimeRef(byteCount,after,startMillis=chunk.startMillis,endMillis=chunk.endMillis,referenceId=chunk.noteId)
            text.append(line);byteCount=after;captured+=note
        }
        val sourceText=text.toString();require(sha(sourceText)==expectedHash){"전사 전체 원문의 해시가 다릅니다."}
        captured.forEach {readUnchanged(it)}
        val core=PrivateAnalysisSource.create(scopeId,"recording-transcript:$transcriptId",PrivateEvidenceKind.AUDIO_TRANSCRIPT,sourceText,PrivateTextCompleteness.COMPLETE_SELECTED_TEXT)
        PrivateSourceSnapshot(core,meta.string("sourceDisplayName")?.take(200)?:"저장된 녹음 전사","audio_transcript",count.toLong(),buildJsonObject {
            put("source","verified_saved_recording_transcript");put("transcriptId",transcriptId);put("manifestNoteId",root.id);put("manifestSha256",sha(root.body))
            put("decodedTextSha256",core.ref.contentSha256);put("sourceTextSha256",expectedHash);put("textHashBasis","ordered_note_bodies_utf8_with_lf")
            put("durationMillis",duration);put("chunkCount",count);put("enumerationComplete",true);put("allSavedTranscriptTextRead",true)
            put("timestampPrecision","decoded_15_second_window");put("wordTimestamps",false);put("speakerDiarization",false);put("speakerIdentity",false)
            put("sourceAudioSha256Verified",false);put("recognitionAccuracyVerified",false);put("originalSpeechCompletenessVerified",false)
            put("analysisScope","saved_transcript_text_only");put("wholePhoneHistoryVerified",false)
            put("sourceLocator",buildJsonObject {put("kind","audio_transcript");put("transcriptId",transcriptId)})
        },timeRefs)
    }

    /** Metadata only; a catalog entry never means its body was read or analyzed. */
    suspend fun listSources(kind:String?=null,limit:Int=20):String=withContext(Dispatchers.IO) {
        require(kind==null||kind in setOf("sms","audio_transcript","conversation_import"))
        require(limit in 1..50)
        val all=notes.list();val entries=mutableListOf<JsonObject>()
        if(kind==null||kind=="sms")entries+=buildJsonObject {put("sourceType","sms");put("id","sms");put("label","Android SMS 조회 자료");put("requiresPermission",!smsReader.hasPermission());put("bodyFetched",false)}
        if(kind==null||kind=="sms")all.filter {it.collection==SOURCE_COLLECTION}.forEach {note->
            val meta=parseObject(note.body)?:return@forEach
            if(meta.string("kind")=="sms_snapshot"&&meta.int("schemaVersion")==1&&meta.string("scopeId")==scopeId)entries+=buildJsonObject {
                put("sourceType","sms");put("snapshotId",meta.string("snapshotId"));put("label","저장된 SMS 분석 자료");put("recordCount",meta.long("recordCount"));put("contentSha256",meta.string("contentSha256"));put("requiresPermission",!smsReader.hasPermission());put("bodyFetched",false)
            }
        }
        if(kind==null||kind=="conversation_import"){
            val imports=importEntries(all)
            imports.filter {it.meta.string("kind")=="manifest"&&completedImportChunks(imports,it)!=null}.distinctBy {it.meta.string("sha256")}.forEach {entry->
                entries+=buildJsonObject {
                    put("sourceType","conversation_import");put("contentSha256",entry.meta.string("sha256"));put("label","가져온 대화 원문")
                    put("recognizedMessages",entry.meta.long("recognized_messages"));put("chunkCount",entry.meta.int("chunks"));put("format",entry.meta.string("format"));put("bodyFetched",false);put("fullIntegrityChecked",false)
                }
            }
        }
        if(kind==null||kind=="audio_transcript"){
            val manifests=all.filter {it.collection==RecordingTranscriptManifest.COLLECTION}.mapNotNull {note->parseObject(note.body)?.let {note to it}}
            val referenced=manifests.filter {it.second.string("kind")=="${RecordingTranscriptManifest.KIND}_page"}.flatMap {(_,meta)->(meta["chunks"] as? JsonArray).orEmpty().mapNotNull {(it as? JsonObject)?.string("noteId")}}.toSet()
            manifests.filter {it.second.string("kind")==RecordingTranscriptManifest.KIND&&it.second.int("schemaVersion")==1}.forEach {(note,meta)->
                val id=meta.string("transcriptId")?.takeIf {UUID.matches(it)}?:return@forEach
                entries+=buildJsonObject {
                    put("sourceType","audio_transcript");put("transcriptId",id);put("manifestNoteId",note.id);put("label",meta.string("sourceDisplayName")?.take(200)?:"저장된 녹음 전사")
                    put("durationMillis",meta.long("durationMillis"));put("chunkCount",meta.int("chunkCount"));put("sourceTextSha256",meta.string("sourceTextSha256"));put("bodyFetched",false);put("fullIntegrityChecked",false)
                }
            }
            all.filter {it.collection=="audio-transcripts"&&it.id !in referenced}.forEach {note->
                if(UUID.matches(note.id))entries+=buildJsonObject {put("sourceType","audio_transcript");put("transcriptId","legacy:${note.id}");put("label",note.title.take(200));put("legacySingleNote",true);put("durationKnown",false);put("originalAudioSha256Known",false);put("bodyFetched",false)}
            }
        }
        var kept=entries.take(limit);var omitted=entries.size>kept.size
        fun render()=buildJsonObject {put("sourceTypes",buildJsonArray {listOf("sms","audio_transcript","conversation_import").forEach {add(it)}});put("sources",JsonArray(kept));put("catalogEntries",entries.size);put("hasMore",omitted);put("bodyFetched",false);put("wholePhoneHistoryVerified",false);put("scope","current_scoped_repository_and_granted_sms_capability_only")}.toString()
        var output=render()
        while(output.length>12_000&&kept.isNotEmpty()){kept=kept.dropLast(1);omitted=true;output=render()}
        output
    }

    private suspend fun legacyTranscript(id:String):PrivateSourceSnapshot {
        require(UUID.matches(id)){"전사 목록에서 선택한 기존 메모 ID가 필요합니다."}
        val note=notes.read(id)?:error("현재 범위에서 전사 메모를 읽지 못했습니다.")
        require(note.collection=="audio-transcripts");require(note.body.toByteArray(Charsets.UTF_8).size<=maxTextBytes)
        readUnchanged(note)
        val core=PrivateAnalysisSource.create(scopeId,"legacy-transcript:$id",PrivateEvidenceKind.AUDIO_TRANSCRIPT,note.body,PrivateTextCompleteness.COMPLETE_SELECTED_TEXT)
        return PrivateSourceSnapshot(core,note.title.take(200),"audio_transcript",1,buildJsonObject {
            put("source","legacy_single_transcript_note");put("transcriptId","legacy:$id");put("enumerationComplete",true)
            put("analysisScope","single_saved_note_only");put("durationKnown",false);put("originalAudioSha256Known",false)
            put("wordTimestamps",false);put("speakerDiarization",false);put("originalSpeechCompletenessVerified",false);put("wholeRecordingCoverageVerified",false)
            put("decodedTextSha256",core.ref.contentSha256);put("textHashBasis","exact_note_body_utf8");put("wholePhoneHistoryVerified",false)
            put("sourceLocator",buildJsonObject {put("kind","audio_transcript");put("transcriptId","legacy:$id")})
        },emptyList())
    }
    private suspend fun readUnchanged(expected:NoteRecord):NoteRecord {
        currentCoroutineContext().ensureActive()
        val actual=notes.read(expected.id)?:error("자료가 삭제되거나 현재 범위에서 해제되었습니다.")
        check(actual.id==expected.id&&actual.collection==expected.collection&&actual.updatedAt==expected.updatedAt&&actual.body==expected.body){"읽는 동안 자료가 변경되었습니다. 새 원문으로 다시 시작해주세요."}
        return actual
    }
    private fun boundedAdd(current:Int,next:Int):Int {require(next<=maxTextBytes-current){"선택한 원문이 분석 범위20MiB를 넘습니다."};return current+next}
    private data class ImportEntry(val note:NoteRecord,val meta:JsonObject)
    private fun importEntries(all:List<NoteRecord>)=all.filter {it.collection=="imported-chats"}.mapNotNull {note->
        val header=note.body.substringBefore('\n');if(header.length>4000)return@mapNotNull null
        val meta=parseObject(header)?:return@mapNotNull null
        if(meta.string("type")!="conversation-import-v1"||!SHA.matches(meta.string("sha256").orEmpty())||meta.string("run").isNullOrBlank()||(meta.int("chunks")?:0) !in 1..2000)null else ImportEntry(note,meta)
    }
    private fun completedImportChunks(all:List<ImportEntry>,manifest:ImportEntry):List<ImportEntry>? {
        val count=manifest.meta.int("chunks")?:return null
        val chunks=all.filter {it.meta.string("kind")=="chunk"&&it.meta.string("run")==manifest.meta.string("run")&&it.meta.string("sha256")==manifest.meta.string("sha256")}
            .sortedBy {it.meta.int("chunk")}
        if(chunks.size!=count||chunks.map {it.meta.int("chunk")}!=(1..count).toList()||chunks.any {it.meta.int("chunks")!=count||it.meta.string("encoding")!=manifest.meta.string("encoding")})return null
        return chunks
    }
    companion object {
        const val SOURCE_COLLECTION="private-analysis-sources"
        private val SHA=Regex("[a-f0-9]{64}")
        private val UUID=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private fun parseObject(raw:String)=runCatching {Json.parseToJsonElement(raw) as? JsonObject}.getOrNull()
        private fun JsonObject.string(key:String)=(get(key) as? JsonPrimitive)?.takeIf {it.isString}?.content
        private fun JsonObject.int(key:String)=(get(key) as? JsonPrimitive)?.takeUnless {it.isString}?.intOrNull
        private fun JsonObject.long(key:String)=(get(key) as? JsonPrimitive)?.takeUnless {it.isString}?.longOrNull
        private fun sha(raw:String)=sha(raw.toByteArray(Charsets.UTF_8))
        private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
    }
}

/** Bind-only provider filter shared with boundary tests: [start,end), never a text query. */
internal fun privateSmsSelection(start:Long?,end:Long?):Pair<String?,Array<String>?> {
    val clauses=mutableListOf<String>();val arguments=mutableListOf<String>()
    start?.let {clauses+="${Telephony.Sms.DATE} >= ?";arguments+=it.toString()}
    end?.let {clauses+="${Telephony.Sms.DATE} < ?";arguments+=it.toString()}
    return clauses.takeIf {it.isNotEmpty()}?.joinToString(" AND ") to arguments.takeIf {it.isNotEmpty()}?.toTypedArray()
}

private class AndroidPrivateSmsReader(private val source:PhoneRecordSource):PrivateSmsReader {
    override fun hasPermission()=source.hasPermission(Manifest.permission.READ_SMS)
    override suspend fun scan(start:Long?,end:Long?,accept:(PrivateSmsRow)->Boolean):PrivateSmsScan=withContext(Dispatchers.IO) {
        check(hasPermission()){"Android 문자 읽기 권한이 필요합니다."}
        val (selection,arguments)=privateSmsSelection(start,end)
        val columns=arrayOf(Telephony.Sms._ID,Telephony.Sms.DATE,Telephony.Sms.TYPE,Telephony.Sms.ADDRESS,Telephony.Sms.THREAD_ID,Telephony.Sms.BODY)
        val cursor=source.query(Telephony.Sms.CONTENT_URI,columns,selection,arguments,"${Telephony.Sms.DATE} ASC, ${Telephony.Sms._ID} ASC")?:error("Android SMS 제공자가 조회 결과를 반환하지 않았습니다.")
        cursor.use {c->
            val total=c.count.toLong();var visited=0L;var exhausted=true
            val id=c.getColumnIndexOrThrow(Telephony.Sms._ID);val body=c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val at=c.getColumnIndex(Telephony.Sms.DATE);val type=c.getColumnIndex(Telephony.Sms.TYPE);val address=c.getColumnIndex(Telephony.Sms.ADDRESS);val thread=c.getColumnIndex(Telephony.Sms.THREAD_ID)
            while(c.moveToNext()){
                currentCoroutineContext().ensureActive()
                if(visited%64==0L)check(hasPermission()){"문자 자료를 읽는 동안 권한이 해제되었습니다."}
                require(!c.isNull(id)){"SMS 행 식별자가 없습니다."}
                fun long(column:Int)=if(column<0||c.isNull(column))null else c.getLong(column)
                fun string(column:Int)=if(column<0||c.isNull(column))null else c.getString(column)
                val row=PrivateSmsRow(c.getLong(id),long(at),long(type)?.toInt(),string(address),long(thread),string(body).orEmpty(),!c.isNull(body))
                visited++
                if(!accept(row)){exhausted=false;break}
            }
            currentCoroutineContext().ensureActive();check(hasPermission()){"문자 자료를 읽는 동안 권한이 해제되었습니다."}
            PrivateSmsScan(total,visited,exhausted)
        }
    }
}

/** Stable aliases preserve distinct full provider identities without returning raw addresses or IDs. */
private class KeystorePrivateSourceIdentity:PrivateSourceIdentity {
    private val key:SecretKey by lazy {synchronized(LOCK){
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        if(!store.containsAlias(ALIAS))KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKey()
        store.getKey(ALIAS,null) as SecretKey
    }}
    override fun alias(domain:String,value:String):String {
        val bytes=(domain+"\u0000"+value).toByteArray(Charsets.UTF_8)
        return try {Mac.getInstance("HmacSHA256").apply {init(key)}.doFinal(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}}finally{bytes.fill(0)}
    }
    companion object {private val LOCK=Any();private const val ALIAS="ziphap-private-source-identity-v1"}
}
