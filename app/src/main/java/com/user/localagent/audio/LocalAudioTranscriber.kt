package com.user.localagent.audio

import android.net.Uri
import com.user.localagent.AppContainer
import com.user.localagent.data.LocalRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.UUID

data class AudioTranscriptionResult(val chunkCount:Int,val noteIds:List<String>,val durationMillis:Long,val engineId:String?=null,
    val chunks:List<AudioTranscriptChunk> = emptyList(),val transcriptRunId:String?=null)

/** Caller owns RuntimeGate. Uses one resident audio-capable model; no audio is sent to a server. */
class LocalAudioTranscriber(private val app:AppContainer,private val decoder:AudioChunkDecoder,
    private val repository:LocalRepository=app.repository,private val verifyAccess:()->Unit={},
    private val discardIncompleteNotes:(suspend(String,List<IncompleteAudioNote>)->AudioCleanupResult)?=null,
    private val requestEngine:com.user.localagent.inference.InferenceEngine=app.engine) {
    var lastCleanupResult:AudioCleanupResult?=null
        private set
    suspend fun transcribe(uri:Uri,onProgress:(Int,Long)->Unit={_,_->}):AudioTranscriptionResult {
        val saved=mutableListOf<String>()
        val owned=mutableListOf<IncompleteAudioNote>()
        val descriptors=mutableListOf<AudioTranscriptChunk>()
        val run=UUID.randomUUID().toString()
        try {
            verifyAccess()
            app.models.refresh()
            val model=app.models.asrModel()
            requestEngine.load(model.path,app.models.asrOptions(model).copy(deviceOnly=true))
            var chunks=0;var duration=0L
            decoder.decode(uri,chunkSeconds=15) {chunk->
                currentCoroutineContext().ensureActive()
                verifyAccess()
                onProgress(chunk.index,chunk.startMillis)
                val text=StringBuilder()
                withTimeout(600_000){requestEngine.transcribeAudio(chunk.file).collect {part->
                    require(text.length+part.length<=10_000){"전사 조각이 출력 제한을 넘었습니다."}
                    text.append(part)
                }}
                currentCoroutineContext().ensureActive()
                verifyAccess()
                val id=UUID.randomUUID().toString();saved+=id
                val body="전사 자료 $run · ${chunk.startMillis/1000}–${(chunk.startMillis+chunk.durationMillis)/1000}초\n"+text.toString().ifBlank {"[인식된 음성 없음]"}
                owned+=IncompleteAudioNote(id,RecordingTranscriptManifest.sha256(body))
                val note=repository.saveNote("녹음 전사 · ${chunk.startMillis/1000}초",body,"audio-transcripts",id)
                check(repository.getNote(id)==note){"전사 자료 저장 후 재조회를 확인하지 못했습니다."}
                descriptors+=AudioTranscriptChunk(chunk.index,id,chunk.startMillis,chunk.startMillis+chunk.durationMillis,
                    RecordingTranscriptManifest.sha256(note.body))
                chunks++;duration=maxOf(duration,chunk.startMillis+chunk.durationMillis)
            }
            verifyAccess()
            return AudioTranscriptionResult(chunks,saved.toList(),duration,model.id,descriptors.toList(),run)
        }catch(error:Throwable){
            lastCleanupResult=withContext(NonCancellable+Dispatchers.IO){
                runCatching {discardIncompleteNotes?.invoke(run,owned.toList())?:cleanupCurrentScope(run,owned)}
                    .getOrElse {AudioCleanupResult(retainedUnverified=owned.size)}
            }
            throw error
        }finally {withContext(NonCancellable){requestEngine.unload()}}
    }
    private suspend fun cleanupCurrentScope(run:String,notes:List<IncompleteAudioNote>):AudioCleanupResult {
        var result=AudioCleanupResult()
        for(expected in notes)try {
            val actual=repository.getNote(expected.noteId)
            if(actual==null){result+=AudioCleanupResult(missing=1);continue}
            if(actual.collection!="audio-transcripts"||!actual.body.startsWith("전사 자료 $run · ")||
                RecordingTranscriptManifest.sha256(actual.body)!=expected.bodySha256){result+=AudioCleanupResult(retainedChanged=1);continue}
            repository.deleteNote(expected.noteId)
            result+=if(repository.getNote(expected.noteId)==null)AudioCleanupResult(deleted=1)else AudioCleanupResult(retainedUnverified=1)
        }catch(_:Exception){result+=AudioCleanupResult(retainedUnverified=1)}
        return result
    }
}
