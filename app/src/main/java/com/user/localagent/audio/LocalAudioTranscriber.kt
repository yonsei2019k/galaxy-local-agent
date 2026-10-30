package com.user.localagent.audio

import android.net.Uri
import com.user.localagent.AppContainer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.UUID

data class AudioTranscriptionResult(val chunkCount:Int,val noteIds:List<String>,val durationMillis:Long)

/** Caller owns RuntimeGate. Uses one resident audio-capable model; no audio is sent to a server. */
class LocalAudioTranscriber(private val app:AppContainer,private val decoder:AudioChunkDecoder) {
    suspend fun transcribe(uri:Uri,onProgress:(Int,Long)->Unit={_,_->}):AudioTranscriptionResult {
        val saved=mutableListOf<String>()
        try {
            app.models.refresh()
            val model=app.models.models.value.firstOrNull {it.id=="gemma-e2b"&&it.verified}
                ?:error("음성 전사에 사용할 Gemma 4 E2B를 모델 화면에서 먼저 내려받아주세요. 대화용 Qwen 선택은 유지됩니다.")
            app.engine.load(model.path,com.user.localagent.inference.InferenceOptions(audioEnabled=true,maxOutputTokens=1024))
            var chunks=0;var duration=0L
            val run=UUID.randomUUID().toString()
            decoder.decode(uri) {chunk->
                currentCoroutineContext().ensureActive()
                onProgress(chunk.index,chunk.startMillis)
                val text=StringBuilder()
                withTimeout(180_000){app.engine.transcribeAudio(chunk.file).collect {part->
                    require(text.length+part.length<=10_000){"전사 조각이 출력 제한을 넘었습니다."}
                    text.append(part)
                }}
                currentCoroutineContext().ensureActive()
                val id=UUID.randomUUID().toString();saved+=id
                app.repository.saveNote("녹음 전사 · ${chunk.startMillis/1000}초", "전사 자료 $run · ${chunk.startMillis/1000}–${(chunk.startMillis+chunk.durationMillis)/1000}초\n"+text.toString().ifBlank {"[인식된 음성 없음]"},"audio-transcripts",id)
                chunks++;duration=maxOf(duration,chunk.startMillis+chunk.durationMillis)
            }
            return AudioTranscriptionResult(chunks,saved.toList(),duration)
        }catch(error:Throwable){
            withContext(NonCancellable+Dispatchers.IO){saved.forEach {id->runCatching {app.repository.deleteNote(id)}}}
            throw error
        }finally {withContext(NonCancellable){app.engine.unload()}}
    }
}
