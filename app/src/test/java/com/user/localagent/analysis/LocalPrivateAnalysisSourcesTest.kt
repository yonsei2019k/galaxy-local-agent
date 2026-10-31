package com.user.localagent.analysis

import com.user.localagent.audio.*
import com.user.localagent.core.*
import com.user.localagent.data.NoteRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.UUID

class LocalPrivateAnalysisSourcesTest {
    private val scope="synthetic-scoped-project"
    private class Store(initial:List<NoteRecord> =emptyList()):PrivateAnalysisNoteStore {
        val rows=initial.associateBy {it.id}.toMutableMap();var saveCalls=0;var cancelAtSave:Int?=null;var cleanupCalls=0
        override suspend fun list()=rows.values.toList()
        override suspend fun read(id:String)=rows[id]
        override suspend fun save(title:String,body:String,collection:String,id:String):NoteRecord {
            saveCalls++;if(saveCalls==cancelAtSave)throw CancellationException("synthetic save cancellation")
            return NoteRecord(id,title,body,collection,1).also {rows[id]=it}
        }
        override suspend fun discardIncomplete(snapshotId:String,expected:List<IncompletePrivateSourceNote>):PrivateSourceCleanupResult {
            cleanupCalls++;var deleted=0
            expected.forEach {row->if(rows[row.noteId]?.body?.let(::sha)==row.bodySha256){rows.remove(row.noteId);deleted++}}
            return PrivateSourceCleanupResult(deleted=deleted)
        }
    }
    private class Sms(var items:List<PrivateSmsRow> =emptyList()):PrivateSmsReader {
        var granted=true;var scans=0;var revokeAfterScan=false
        override fun hasPermission()=granted
        override suspend fun scan(start:Long?,end:Long?,accept:(PrivateSmsRow)->Boolean):PrivateSmsScan {
            scans++;val filtered=items.filter {(start==null||it.atEpochMillis!=null&&it.atEpochMillis>=start)&&(end==null||it.atEpochMillis!=null&&it.atEpochMillis<end)}
            var visited=0L;var exhausted=true
            for(item in filtered){visited++;if(!accept(item)){exhausted=false;break}}
            if(revokeAfterScan)granted=false
            return PrivateSmsScan(filtered.size.toLong(),visited,exhausted)
        }
    }
    private fun adapter(store:Store=Store(),sms:Sms=Sms(),maxRows:Int=100000,maxBytes:Int=20*1024*1024)=LocalPrivateAnalysisSources(store,scope,sms,PrivateSourceIdentity {domain,value->sha("$domain\u0000$value")},maxRows,maxBytes)
    private fun text(source:PrivateAnalysisSource):String {
        val result=StringBuilder();var cursor:PrivateChunkCursor?=null
        do {val page=PrivateAnalysisEvidence.page(source,16000,0,64,cursor);page.chunks.forEach {result.append(it.text)};cursor=page.next}while(cursor!=null)
        return result.toString()
    }
    private fun row(id:Long,body:String="synthetic body",at:Long=1700000000000,address:String="SYNTHETIC-ADDRESS-$id",thread:Long=id)=PrivateSmsRow(id,at,1,address,thread,body)

    @Test fun smsReadsFullBodiesKeepsDistinctOpaqueParticipantsAndPersistsAnImmutableCapture()=runBlocking {
        val body="전체 원문 "+"x".repeat(8000)
        val store=Store();val sms=Sms(listOf(row(1,body,address="01011112222"),row(2,"second",address="01099992222")))
        val source=adapter(store,sms).sms()
        assertEquals(1,sms.scans);assertEquals(2L,source.recordCount)
        assertEquals(PrivateTextCompleteness.COMPLETE_SELECTED_TEXT,source.coreSource.completeness)
        val lines=text(source.coreSource).lineSequence().filter {it.isNotBlank()}.map {Json.parseToJsonElement(it).jsonObject}.toList()
        assertEquals(body,lines[0].getValue("body").jsonPrimitive.content)
        assertNotEquals(lines[0]["participantKey"],lines[1]["participantKey"])
        assertNotEquals(lines[0]["threadKey"],lines[1]["threadKey"])
        assertFalse(text(source.coreSource).contains("01011112222"))
        assertEquals(2,source.timeRefs.size);assertEquals(0,source.timeRefs[0].startByte)
        assertEquals(lines[0].getValue("participantKey").jsonPrimitive.content,source.timeRefs[0].participantKey)
        assertEquals(lines[0].getValue("threadKey").jsonPrimitive.content,source.timeRefs[0].threadKey)
        assertEquals(1,source.timeRefs[0].recordType)
        assertTrue(source.timeRefs[0].endByte>8000)
        assertEquals(source.coreSource.utf8ByteLength,source.timeRefs.last().endByte)
        assertTrue(store.rows.values.all {it.body.length<=12000&&it.collection==LocalPrivateAnalysisSources.SOURCE_COLLECTION})
        assertTrue(store.rows.values.any {it.body.contains("\"kind\":\"sms_snapshot\"")})
    }
    @Test fun cachedSmsResumesWithoutReadingTheChangedProviderAgain()=runBlocking {
        val store=Store();val sms=Sms(listOf(row(1,"captured body")))
        val sources=adapter(store,sms);val original=sources.sms()
        val id=original.metadata.getValue("snapshotId").jsonPrimitive.content
        sms.items=listOf(row(2,"arrived later"))
        val restored=sources.cachedSms(id)
        assertEquals(1,sms.scans);assertEquals(original.coreSource.ref,restored.coreSource.ref)
        assertEquals(text(original.coreSource),text(restored.coreSource));assertEquals(original.timeRefs,restored.timeRefs)
        assertEquals(original.metadata,restored.metadata)
    }
    @Test fun cachedSmsRejectsTamperedChunkAndLivePermissionLoss()=runBlocking {
        val store=Store();val sms=Sms(listOf(row(1)))
        val sources=adapter(store,sms);val captured=sources.sms();val id=captured.metadata.getValue("snapshotId").jsonPrimitive.content
        sms.granted=false
        assertTrue(runCatching {sources.cachedSms(id)}.isFailure);assertEquals(1,sms.scans)
        sms.granted=true
        val chunk=store.rows.values.first {it.body.startsWith("{\"kind\":\"sms_snapshot_chunk\"")}
        store.rows[chunk.id]=chunk.copy(body=chunk.body+"changed")
        assertTrue(runCatching {sources.cachedSms(id)}.isFailure)
    }
    @Test fun smsLimitsArePartialAndProviderPermissionRevocationDoesNotPublishCache()=runBlocking {
        val source=adapter(sms=Sms(listOf(row(1),row(2))),maxRows=1).sms()
        assertEquals(1L,source.recordCount);assertEquals(PrivateTextCompleteness.PARTIAL_SELECTED_TEXT,source.coreSource.completeness)
        assertFalse(source.metadata.getValue("enumerationComplete").jsonPrimitive.boolean)
        assertTrue(source.metadata.getValue("limitations").jsonArray.any {it.jsonPrimitive.content=="record_limit"})
        val store=Store();val revoked=Sms(listOf(row(1))).apply {revokeAfterScan=true}
        assertTrue(runCatching {adapter(store,revoked).sms()}.isFailure);assertTrue(store.rows.isEmpty())
    }
    @Test fun providerNullBodiesAreExplicitMissingTextRatherThanCompleteEmptyBodies()=runBlocking {
        val result=adapter(sms=Sms(listOf(row(1,"").copy(bodyPresent=false)))).sms()
        assertEquals(PrivateTextCompleteness.PARTIAL_SELECTED_TEXT,result.coreSource.completeness)
        assertEquals(1,result.metadata.getValue("missingBodyCount").jsonPrimitive.int)
        assertFalse(result.metadata.getValue("fullIncludedBodies").jsonPrimitive.boolean)
        assertTrue(result.metadata.getValue("limitations").jsonArray.any {it.jsonPrimitive.content=="missing_body"})
    }
    @Test fun cancellationDuringSnapshotPersistenceUsesOwnedRollbackAndPropagates()=runBlocking {
        val store=Store().apply {cancelAtSave=2};val sms=Sms(listOf(row(1,"x".repeat(9000))))
        try {adapter(store,sms).sms();fail("cancel swallowed")}catch(_:CancellationException){ }
        assertEquals(1,store.cleanupCalls);assertTrue(store.rows.isEmpty())
    }
    @Test fun smsExplicitPeriodIsPassedToTheReaderAndEmptyCaptureRoundTrips()=runBlocking {
        val store=Store();val sms=Sms(listOf(row(1,at=1700000000000)))
        val sources=adapter(store,sms)
        val empty=sources.sms("2028-01-01T00:00:00+09:00","2028-01-02T00:00:00+09:00")
        assertEquals(0L,empty.recordCount);assertEquals(0,empty.coreSource.utf8ByteLength)
        val cached=sources.cachedSms(empty.metadata.getValue("snapshotId").jsonPrimitive.content)
        assertEquals(empty.coreSource.ref,cached.coreSource.ref);assertTrue(cached.timeRefs.isEmpty())
    }
    @Test fun smsPeriodIncludesExactStartAndExcludesExactEndUsingBoundProviderSql()=runBlocking {
        val startIso="2028-01-01T00:00:00+09:00";val endIso="2028-01-02T00:00:00+09:00"
        val start=java.time.OffsetDateTime.parse(startIso).toInstant().toEpochMilli()
        val end=java.time.OffsetDateTime.parse(endIso).toInstant().toEpochMilli()
        val sms=Sms(listOf(row(1,"before",start-1),row(2,"exact start",start),row(3,"inside",end-1),row(4,"exact end",end)))
        val sources=adapter(sms=sms);val snapshot=sources.sms(startIso,endIso)
        assertEquals(2L,snapshot.recordCount)
        val bodies=text(snapshot.coreSource).lineSequence().filter {it.isNotBlank()}.map {Json.parseToJsonElement(it).jsonObject.getValue("body").jsonPrimitive.content}.toList()
        assertEquals(listOf("exact start","inside"),bodies)
        assertFalse(snapshot.metadata.getValue("endInclusive").jsonPrimitive.boolean)
        assertTrue(snapshot.metadata.getValue("endExclusive").jsonPrimitive.boolean)
        val (selection,args)=privateSmsSelection(start,end)
        assertEquals("date >= ? AND date < ?",selection);assertArrayEquals(arrayOf(start.toString(),end.toString()),args)
        assertTrue(runCatching {sources.sms(startIso,startIso)}.isFailure)
    }
    private fun imported(content:String,encoding:String="UTF-8"):Pair<Store,String> {
        val originalHash=sha(content.toByteArray(Charset.forName(encoding)));val run=UUID.randomUUID().toString()
        val parts=content.chunked(7)
        fun header(kind:String,index:Int?=null)=buildJsonObject {
            put("type","conversation-import-v1");put("kind",kind);put("sha256",originalHash);put("run",run);put("chunks",parts.size)
            put("encoding",encoding);put("format","synthetic");put("recognized_messages",2)
            index?.let {put("chunk",it)}
        }.toString()
        val notes=parts.mapIndexed {index,part->NoteRecord(UUID.randomUUID().toString(),"synthetic",header("chunk",index+1)+"\n"+part,"imported-chats",1)}+
            NoteRecord(UUID.randomUUID().toString(),"synthetic",header("manifest"),"imported-chats",1)
        return Store(notes) to originalHash
    }
    @Test fun importedEucKrOriginalFileHashIsDistinctFromReconstructedUtf8TextHash()=runBlocking {
        val body="합성 대화 원문입니다. 다음 주에 만나요."
        val (store,hash)=imported(body,"EUC-KR")
        val result=adapter(store).conversationImport(hash)
        assertEquals(body,text(result.coreSource));assertEquals(hash,result.originalSha256)
        assertNotEquals(hash,result.coreSource.ref.contentSha256)
        assertTrue(result.metadata.getValue("originalFileHashRecomputed").jsonPrimitive.boolean)
    }
    @Test fun importedIncompleteOrAlteredChunksCannotBecomeVerifiedSource()=runBlocking {
        val (store,hash)=imported("synthetic text with several parts")
        val chunk=store.rows.values.first {it.body.contains("\"kind\":\"chunk\"")}
        store.rows[chunk.id]=chunk.copy(body=chunk.body+"tampered")
        assertTrue(runCatching {adapter(store).conversationImport(hash)}.isFailure)
        store.rows.remove(chunk.id)
        assertTrue(runCatching {adapter(store).conversationImport(hash)}.isFailure)
    }
    private fun recording():Triple<Store,String,String> {
        val transcript=UUID.randomUUID().toString();val noteId=UUID.randomUUID().toString();val pageId=UUID.randomUUID().toString()
        val note=NoteRecord(noteId,"synthetic transcript","전사 자료 $transcript · 0–15초\nsynthetic observed words","audio-transcripts",1)
        val chunk=AudioTranscriptChunk(0,noteId,0,15000,RecordingTranscriptManifest.sha256(note.body))
        val page=NoteRecord(pageId,"synthetic page",RecordingTranscriptManifest.pageJson(transcript,0,listOf(chunk)),RecordingTranscriptManifest.COLLECTION,1)
        val selection=RecordingSelection(UUID.randomUUID().toString(),"synthetic.m4a",15000,"synthetic-thread","synthetic-run","content://media/external/audio/media/1",1)
        val root=NoteRecord(UUID.randomUUID().toString(),"synthetic root",RecordingTranscriptManifest.rootJson(transcript,selection,15000,"synthetic-asr",RecordingTranscriptManifest.sourceTextSha256(listOf(note.body)),1,listOf(TranscriptManifestPage(pageId,RecordingTranscriptManifest.sha256(page.body)))),RecordingTranscriptManifest.COLLECTION,1)
        return Triple(Store(listOf(note,page,root)),transcript,noteId)
    }
    @Test fun recordingManifestReconstructsExactBodiesWithLfAndKeepsWindowTimeLimits()=runBlocking {
        val (store,id,noteId)=recording()
        val result=adapter(store).audioTranscript(id)
        assertEquals(store.rows.getValue(noteId).body+"\n",text(result.coreSource))
        assertEquals(15000L,result.timeRefs.single().endMillis);assertEquals(0L,result.timeRefs.single().startMillis)
        assertNull(result.originalSha256)
        assertFalse(result.metadata.getValue("wordTimestamps").jsonPrimitive.boolean)
        assertFalse(result.metadata.getValue("originalSpeechCompletenessVerified").jsonPrimitive.boolean)
    }
    @Test fun recordingChangedBodyFailsHashesAndLegacyNoteHasNoInventedTime()=runBlocking {
        val (store,id,noteId)=recording();val changed=store.rows.getValue(noteId)
        store.rows[noteId]=changed.copy(body=changed.body+" changed")
        assertTrue(runCatching {adapter(store).audioTranscript(id)}.isFailure)
        val legacy=adapter(store).audioTranscript("legacy:$noteId")
        assertTrue(legacy.timeRefs.isEmpty());assertNull(legacy.originalSha256)
        assertFalse(legacy.metadata.getValue("durationKnown").jsonPrimitive.boolean)
    }
    @Test fun catalogIsBoundedMetadataOnlyAndListsRootManifestsInsteadOfPages()=runBlocking {
        val (store,id,_)=recording();val sms=Sms(listOf(row(1)))
        val result=Json.parseToJsonElement(adapter(store,sms).listSources("audio_transcript",1)).jsonObject
        assertEquals(0,sms.scans)
        val sources=result.getValue("sources").jsonArray
        assertEquals(1,sources.size);assertEquals(id,sources[0].jsonObject.getValue("transcriptId").jsonPrimitive.content)
        assertFalse(result.toString().contains("synthetic observed words"))
        assertFalse(result.getValue("bodyFetched").jsonPrimitive.boolean)
        assertTrue(runCatching {adapter(store).listSources("invalid")}.isFailure)
    }
    companion object {
        private fun sha(value:String)=sha(value.toByteArray(Charsets.UTF_8))
        private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
    }
}
