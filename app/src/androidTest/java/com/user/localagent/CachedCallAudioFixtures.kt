package com.user.localagent

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Random
import kotlin.math.*
import kotlinx.serialization.json.*

/** Synthetic test audio only. No microphone, recordings, contacts, network, or transcript logging. */
internal object CachedCallAudioFixtures {
    const val SCRIPT="민서님, 이천삼십 년 일월 이일 오후 세 시가 아니라 세 시 삼십 분입니다. 메일은 보내지 말고 초안만 만드세요."
    data class Variant(val id:String,val file:File,val sha256:String,val durationMillis:Long,val measuredSnrDb:Double?)
    fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    fun pcm(bytes:ByteArray):ShortArray {
        require(bytes.size in 44..1_000_000)
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes,0,4,Charsets.US_ASCII)=="RIFF"&&String(bytes,8,4,Charsets.US_ASCII)=="WAVE")
        require(b.getShort(20).toInt()==1&&b.getShort(22).toInt()==1&&b.getInt(24)==16_000&&b.getShort(34).toInt()==16)
        require(String(bytes,36,4,Charsets.US_ASCII)=="data"&&b.getInt(40)==bytes.size-44&&(bytes.size-44)%2==0)
        return ShortArray((bytes.size-44)/2){b.getShort(44+it*2)}
    }
    fun wav(samples:ShortArray,rate:Int=16_000):ByteArray {
        require(rate in setOf(8000,16000)&&samples.size<=30*rate)
        val b=ByteBuffer.allocate(44+samples.size*2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36+samples.size*2).put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate*2).putShort(2).putShort(16)
        b.put("data".toByteArray(Charsets.US_ASCII)).putInt(samples.size*2);samples.forEach{b.putShort(it)}
        return b.array()
    }
    fun variants(pcm16:ShortArray,directory:File):List<Variant> {
        require(pcm16.size in 1600..30*16_000)
        val pcm8=ShortArray(pcm16.size/2){((pcm16[it*2].toInt()+pcm16[it*2+1].toInt())/2).toShort()}
        val rms=sqrt(pcm8.sumOf{it.toDouble()*it}/pcm8.size);require(rms>50){"Synthetic speech is silent or too quiet"}
        val random=Random(20261031L);val sigma=rms/10.0.pow(15.0/20)
        val noisy=ShortArray(pcm8.size){(pcm8[it]+random.nextGaussian()*sigma).roundToInt().coerceIn(-32768,32767).toShort()}
        fun up(input:ShortArray)=ShortArray(input.size*2){index->val i=index/2;if(index%2==0)input[i]else((input[i].toInt()+input[minOf(i+1,input.lastIndex)].toInt())/2).toShort()}
        val clean=up(pcm8);val noise=up(noisy)
        val error=sqrt(noise.indices.sumOf{(noise[it].toDouble()-clean[it]).pow(2)}/noise.size)
        val signal=sqrt(clean.sumOf{it.toDouble()*it}/clean.size)
        return listOf("telephone8k_clean" to clean,"telephone8k_noise15db" to noise).map{(id,samples)->
            val bytes=wav(samples);val file=File(directory,"$id.wav");check(file.createNewFile());file.writeBytes(bytes)
            Variant(id,file,sha(bytes),samples.size*1000L/16000,if(id.endsWith("clean"))null else if(error>0)20*log10(signal/error)else null)
        }
    }
    fun scores(transcript:String):JsonObject {
        val compact=Normalizer.normalize(transcript,Normalizer.Form.NFC).replace(Regex("\\s+"),"")
        val checks=linkedMapOf(
            "synthetic_name" to compact.contains("민서"),
            "explicit_month_day" to Regex("일월이일|1월2일|1월이일|일월2일").containsMatchIn(compact),
            "corrected_time" to Regex("세시삼십분|3시30분|세시30분|3시삼십분|세시반|3시반").containsMatchIn(compact),
            "correction_marker" to Regex("(?:세시|3시)(?:가)?아니라|(?:세시|3시)말고|정정").containsMatchIn(compact),
            "sending_negation" to Regex("보내지말|보내지마|보내면안").containsMatchIn(compact),
            "draft_only" to compact.contains("초안"),
        )
        val reference=Normalizer.normalize(SCRIPT,Normalizer.Form.NFC).replace(Regex("\\s+"),"")
        val ref=reference.codePoints().toArray();val hyp=compact.codePoints().toArray()
        require(hyp.size<=10_000)
        var previous=IntArray(hyp.size+1){it}
        for(i in ref.indices){val current=IntArray(hyp.size+1);current[0]=i+1;for(j in hyp.indices)current[j+1]=minOf(previous[j+1]+1,current[j]+1,previous[j]+if(ref[i]==hyp[j])0 else 1);previous=current}
        val refWords=SCRIPT.trim().split(Regex("\\s+"));val hypWords=transcript.trim().takeIf{it.isNotEmpty()}?.split(Regex("\\s+")).orEmpty()
        var wordRow=IntArray(hypWords.size+1){it}
        for(i in refWords.indices){val next=IntArray(hypWords.size+1);next[0]=i+1;for(j in hypWords.indices)next[j+1]=minOf(wordRow[j+1]+1,next[j]+1,wordRow[j]+if(refWords[i]==hypWords[j])0 else 1);wordRow=next}
        return buildJsonObject {
            put("critical_checks",JsonObject(checks.mapValues{JsonPrimitive(it.value)}));put("critical_matches",checks.values.count{it});put("critical_total",checks.size)
            put("korean_cer",previous.last().toDouble()/ref.size);put("wer",wordRow.last().toDouble()/refWords.size)
            put("output_characters",transcript.length);put("all_lexical_checks_matched",checks.values.all{it})
            put("metric_scope","synthetic lexical anchors; digits/punctuation unchanged; not semantic action or general Korean accuracy")
        }
    }
}
