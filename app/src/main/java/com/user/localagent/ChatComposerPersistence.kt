package com.user.localagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class ComposerDraftSnapshot(val threadId:String,val text:String,val modelId:String,val thinking:Boolean)

/** Accepted input survives selection-job cancellation, and each room's writes retain input order. */
internal class ChatComposerPersistence(private val scope:CoroutineScope,private val write:suspend(ComposerDraftSnapshot)->Unit) {
    private val lock=Any()
    private val jobs=mutableMapOf<String,Job>()
    fun enqueue(snapshot:ComposerDraftSnapshot):Job=synchronized(lock){
        val previous=jobs[snapshot.threadId]
        scope.launch(start=CoroutineStart.LAZY){previous?.join();write(snapshot)}.also{job->jobs[snapshot.threadId]=job;job.start()}
    }
    suspend fun awaitLatest(threadId:String){synchronized(lock){jobs[threadId]}?.join()}
}
