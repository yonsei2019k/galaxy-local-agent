package com.user.localagent.workspace

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

/** Process-wide storage mutation exclusion, independent of project identity and read authority. */
internal object WorkspaceMutationLocks {
    private val locks=ConcurrentHashMap<String,Mutex>()
    suspend fun <T> withBinding(binding:String,block:suspend()->T):T {
        require(binding.isNotBlank()&&binding.length<=4096)
        val mutex=locks.getOrPut(binding){Mutex()}
        mutex.lock()
        try{return block()}finally{mutex.unlock()}
    }
}
