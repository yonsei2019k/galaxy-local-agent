package com.user.localagent

import android.content.Context
import java.io.File
import java.time.Instant

/** Failure causes for later repair: stage, exception chain and fixed codes only, never prompts, answers or message text. */
object FailureLog {
    private const val MAX_BYTES=512*1024L
    @Synchronized fun record(context:Context,stage:String,error:Throwable?,code:String?=null){
        runCatching {
            val directory=File(context.getExternalFilesDir(null)?:context.filesDir,"diagnostics").apply {mkdirs()}
            val file=File(directory,"failures.log")
            if(file.length()>MAX_BYTES)file.renameTo(File(directory,"failures.old.log"))
            val chain=generateSequence(error){it.cause}.take(4).joinToString(" <- "){it.javaClass.simpleName}
            val frame=error?.stackTrace?.firstOrNull {it.className.startsWith("com.user.localagent")}?.let {"${it.fileName}:${it.lineNumber}"}
            file.appendText("${Instant.now()} stage=$stage code=${code.orEmpty()} chain=$chain at=${frame.orEmpty()}\n")
        }
    }
}
