package com.user.localagent.data

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Instances of the same actual file share ordering; equal filenames in separate roots do not. */
internal object DraftRevisions {
    private val counters=ConcurrentHashMap<String,AtomicLong>()
    fun forFile(file:File):AtomicLong=counters.getOrPut(file.canonicalPath){AtomicLong()}
}
