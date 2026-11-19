package com.user.localagent

import android.content.Context
import java.io.File
import java.time.Instant

/** Failure causes for later repair: stage, exception chain and fixed codes only, never prompts, answers or message text. */
object FailureLog {
    private const val MAX_BYTES=512*1024L
    private fun token(value:String?)=value.orEmpty().takeIf{it.matches(Regex("[A-Za-z0-9_.-]{0,96}"))}.orEmpty()

    @Synchronized fun record(context:Context,stage:String,error:Throwable?,code:String?=null){
        append(context,stage,error,code,"")
    }

    @Synchronized fun cloudStop(context:Context,code:String,phase:String,sessionState:String?,
        pendingState:String?,sequence:Int,providerState:String,elapsedMs:Long) {
        append(context,"cloud_without_report",null,code,
            " phase=${token(phase)} session=${token(sessionState)} pending=${token(pendingState)} sequence=${sequence.coerceIn(0,18)} provider=${token(providerState)} elapsedMs=${elapsedMs.coerceAtLeast(0)}")
    }

    private fun append(context:Context,stage:String,error:Throwable?,code:String?,metadata:String){
        runCatching {
            val directory=File(context.getExternalFilesDir(null)?:context.filesDir,"diagnostics").apply {mkdirs()}
            val file=File(directory,"failures.log")
            if(file.length()>MAX_BYTES)file.renameTo(File(directory,"failures.old.log"))
            val chain=generateSequence(error){it.cause}.take(4).joinToString(" <- "){it.javaClass.simpleName}
            val frame=error?.stackTrace?.firstOrNull {it.className.startsWith("com.user.localagent")}?.let {"${it.fileName}:${it.lineNumber}"}
            file.appendText("${Instant.now()} stage=${token(stage)} code=${token(code)} chain=$chain at=${frame.orEmpty()}$metadata\n")
        }.onFailure{android.util.Log.w("ZipHapDiagnostics","Failure log persistence unavailable")}
    }
}
