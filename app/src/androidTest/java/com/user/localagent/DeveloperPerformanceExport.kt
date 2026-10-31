package com.user.localagent

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

/** Explicit export of already completed cached-device evidence. No inference or provider access. */
@RunWith(AndroidJUnit4::class)
class DeveloperPerformanceExport {
    @Test fun exportLatestCompletedCachedEndurance()=runBlocking<Unit>(Dispatchers.IO){
        val arguments=InstrumentationRegistry.getArguments()
        assumeTrue("developerPerformanceExportApproved=true required",arguments.getString("developerPerformanceExportApproved")=="true")
        val cutoff=checkNotNull(arguments.getString("notBeforeEpochMillis")?.toLongOrNull()){"An explicit benchmark start cutoff is required"}
        require(cutoff>0&&cutoff<=System.currentTimeMillis()){"The explicit cutoff is invalid"}
        val expected=arguments.getString("expectedModelId")?:"gemma-e2b"
        require(expected=="gemma-e2b"){"This approved exporter is pinned to the cached E2B benchmark"}
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.getExternalFilesDir(null)?:context.filesDir,"benchmarks").canonicalFile
        check(directory.isDirectory||directory.mkdirs())
        val output=contained(directory,"cached-endurance-approved-export.json")
        val atomic=AtomicFile(output)
        // A failed new export must not leave an old PASS at the delivery filename.
        atomic.delete()
        val receipts=File(context.filesDir,"validation-reports").canonicalFile
        check(receipts.isDirectory){"No private cached-endurance receipts exist"}
        val pattern=Regex("cached-endurance-([0-9]{13})-[a-f0-9-]{36}\\.json")
        val candidates=receipts.listFiles().orEmpty().mapNotNull{file->
            val match=pattern.matchEntire(file.name)?:return@mapNotNull null
            file to match.groupValues[1].toLong()
        }
        require(candidates.size<=10000){"Receipt enumeration exceeds its bounded limit"}
        // Select newest first, including failed/cancelled/other-model runs. Never fall back to old PASS.
        val latest=checkNotNull(candidates.maxByOrNull{it.second}){"No timestamped cached-endurance receipt exists"}
        require(latest.second>=cutoff){"The latest receipt predates the explicitly approved run"}
        val receipt=readJson(contained(receipts,latest.first.name),65536)
        require(text(receipt,"stage")=="completed"&&receipt.getValue("failure_class")==JsonNull){"The newest cached run did not complete successfully"}
        require(flag(receipt,"cached_only")&&flag(receipt,"device_only")&&flag(receipt,"cached_sha_unchanged")&&flag(receipt,"owner_selection_unchanged")){"Cached-device evidence or owner-selection checks did not pass"}
        require(text(receipt,"model_id")==expected){"The latest run used another model"}
        val sha=text(receipt,"model_sha256").lowercase()
        require(sha.matches(Regex("[a-f0-9]{64}")))
        val app=(context.applicationContext as LocalAgentApplication).container
        app.models.refresh() // Existing verified metadata API only; no engine/model selection/download.
        val model=checkNotNull(app.models.models.value.singleOrNull{it.id==expected&&it.verified&&it.runtime=="gguf"}){"The pinned cached model is not verified"}
        require(model.sha256.equals(sha,true)){"The completed run hash differs from installed verified metadata"}
        val benchmarkName=text(receipt,"benchmark_filename")
        val benchmarkMatch=Regex("benchmark-([0-9]{13})-[a-f0-9]{8}\\.json").matchEntire(benchmarkName)
            ?:error("The receipt does not name an actual runner artifact")
        require(benchmarkMatch.groupValues[1].toLong()>=latest.second)
        val runner=readJson(contained(directory,benchmarkName),8*1024*1024)
        validateRunner(runner,cutoff,File(model.path).name)
        val export=exportMetrics(receipt,runner,latest.second,cutoff,expected,sha).toString()
        val stream=atomic.startWrite()
        try{stream.write(export.toByteArray(Charsets.UTF_8));atomic.finishWrite(stream)}catch(error:Throwable){atomic.failWrite(stream);throw error}
        require(readJson(output,8*1024*1024)==Json.parseToJsonElement(export).jsonObject){"Approved metric export readback failed"}
    }

    private fun validateRunner(report:JsonObject,cutoff:Long,modelFile:String){
        require(text(report,"stage")=="completed"&&report.getValue("failure")==JsonNull&&report.getValue("unload_failure")==JsonNull){"Actual runner did not complete cleanly"}
        require(Instant.parse(text(report,"timestamp")).toEpochMilli()>=cutoff)
        require(text(report,"model_file")==modelFile&&text(report,"backend")=="CPU")
        require(integer(report,"requested_endurance_minutes")==10L&&integer(report,"elapsed_ms")>=600000)
        require(integer(report,"context")==4096L&&integer(report,"max_output_tokens")==512L&&!flag(report,"thinking"))
        val iterations=integer(report,"iterations").also{require(it in 1..10000)}
        val samples=report.getValue("samples").jsonArray
        require(samples.size.toLong()==iterations)
        samples.forEachIndexed{index,value->val sample=value.jsonObject
            require(integer(sample,"iteration")==index+1L&&text(sample,"status")=="completed"&&sample.getValue("failure")==JsonNull){"A measured sample did not complete"}
        }
    }

    private fun exportMetrics(receipt:JsonObject,report:JsonObject,started:Long,cutoff:Long,modelId:String,sha:String)=buildJsonObject{
        put("exported_at",Instant.now().toString());put("timestamp",text(report,"timestamp"));put("receipt_started_at_epoch_ms",started);put("approved_not_before_epoch_ms",cutoff)
        put("model_id",modelId);put("model_sha256",sha);put("stage","completed");put("device_only",true)
        put("cached_sha_unchanged",true);put("owner_selection_unchanged",true)
        put("android_api",integer(report,"android_api").also{require(it in 31..100)})
        put("backend","CPU");put("context",4096);put("max_output_tokens",512);put("thinking",false);put("requested_endurance_minutes",10)
        put("load_ms",number(report,"load_ms"));put("elapsed_ms",integer(report,"elapsed_ms"));put("iterations",integer(report,"iterations"));put("peak_pss_kb",number(report,"peak_pss_kb"))
        put("start_battery",battery(report["start_battery"]));put("end_battery",battery(report["end_battery"]))
        put("samples",buildJsonArray{report.getValue("samples").jsonArray.forEach{value->val sample=value.jsonObject;add(buildJsonObject{
            put("iteration",integer(sample,"iteration"));put("status","completed")
            for(key in listOf("ttft_ms","total_ms","visible_characters","peak_pss_kb","prefill_tokens","decode_tokens","prefill_tokens_per_sec","decode_tokens_per_sec"))put(key,number(sample,key))
            put("thermal_status",integer(sample,"thermal_status").also{require(it in 0..6)})
            put("battery",battery(sample["battery"]))
        })}})
        put("unsupported_metrics",buildJsonArray{add("energy_joules");add("gpu_memory_bytes")})
        put("raw_error_exported",false);put("private_paths_exported",false);put("device_identifiers_exported",false)
        require(integer(receipt,"elapsed_ms")>=600000)
    }

    private fun battery(value:JsonElement?):JsonElement{
        if(value==null||value==JsonNull)return JsonNull
        val data=value.jsonObject
        return buildJsonObject{
            put("level",number(data,"level"));put("scale",number(data,"scale"));put("temperature_c",number(data,"temperature_c"))
        }
    }
    private fun number(data:JsonObject,key:String):JsonElement{
        val value=data[key]?:return JsonNull
        if(value==JsonNull)return value
        val primitive=value.jsonPrimitive
        require(!primitive.isString)
        val number=checkNotNull(primitive.doubleOrNull)
        require(number.isFinite()&&number>=0){"A measured numeric field is invalid"}
        return primitive
    }
    private fun integer(data:JsonObject,key:String):Long{
        val value=data.getValue(key).jsonPrimitive;require(!value.isString)
        return checkNotNull(value.longOrNull).also{require(it>=0)}
    }
    private fun text(data:JsonObject,key:String):String=data.getValue(key).jsonPrimitive.let{require(it.isString);it.content}
    private fun flag(data:JsonObject,key:String):Boolean=data.getValue(key).jsonPrimitive.let{require(!it.isString);checkNotNull(it.booleanOrNull)}
    private fun contained(directory:File,name:String):File{
        require(name.isNotBlank()&&name==File(name).name&&'/' !in name&&'\\' !in name)
        return File(directory,name).canonicalFile.also{require(it.parentFile==directory.canonicalFile)}
    }
    private fun readJson(file:File,limit:Int):JsonObject{
        require(file.isFile&&file.length() in 1..limit.toLong()){"The approved metadata artifact is absent or oversized"}
        val bytes=file.inputStream().use{input->val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val count=input.read(buffer);if(count<0)break;require(output.size()+count<=limit);output.write(buffer,0,count)};output.toByteArray()}
        return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }
}
