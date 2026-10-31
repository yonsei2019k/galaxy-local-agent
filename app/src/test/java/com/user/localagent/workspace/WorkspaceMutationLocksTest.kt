package com.user.localagent.workspace

import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceMutationLocksTest {
    @Test fun twoProjectAliasesCannotOverwriteAfterTheFirstChangedTheReadVersion()=runBlocking {
        val binding="public-fake-provider:${UUID.randomUUID()}";var body="original";val expected=body
        val writing=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var secondEntered=false
        val first=async{WorkspaceMutationLocks.withBinding(binding){writing.complete(Unit);release.await();body="first verified update"}}
        writing.await()
        val second=async{runCatching{WorkspaceMutationLocks.withBinding(binding){secondEntered=true;check(body==expected){"source version changed"};body="stale update"}}}
        yield();assertFalse(secondEntered);release.complete(Unit);first.await()
        assertTrue(second.await().exceptionOrNull() is IllegalStateException)
        assertEquals("first verified update",body)
    }
    @Test fun cancellingAQueuedMutationCannotReleaseTheCurrentWritersLock()=runBlocking {
        val binding="public-cancel-provider:${UUID.randomUUID()}";val held=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val first=launch{WorkspaceMutationLocks.withBinding(binding){held.complete(Unit);release.await()}}
        held.await();var cancelledEntered=false
        val waiting=launch{WorkspaceMutationLocks.withBinding(binding){cancelledEntered=true}}
        yield();waiting.cancelAndJoin();assertFalse(cancelledEntered)
        var thirdEntered=false
        val third=launch{WorkspaceMutationLocks.withBinding(binding){thirdEntered=true}}
        yield();assertFalse(thirdEntered);release.complete(Unit);first.join();third.join();assertTrue(thirdEntered)
    }
}
