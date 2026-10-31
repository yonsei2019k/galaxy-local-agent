package com.user.localagent

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ChatComposerPersistenceTest {
    @Test fun slowRoomAWriteDoesNotBlockBAndReselectWaitsForLatestTextAndModel()=runBlocking {
        val entered=CompletableDeferred<Unit>();val allowA=CompletableDeferred<Unit>()
        val stored=linkedMapOf<String,ComposerDraftSnapshot>();val order=mutableListOf<String>()
        val saves=ChatComposerPersistence(this){snapshot->
            if(snapshot.threadId=="a"&&snapshot.text=="first"){entered.complete(Unit);allowA.await()}
            stored[snapshot.threadId]=snapshot;order+="${snapshot.threadId}:${snapshot.text}"
        }
        saves.enqueue(ComposerDraftSnapshot("a","first","model-old",false));entered.await()
        val latest=ComposerDraftSnapshot("a","latest 한글🙂","model-new",true)
        saves.enqueue(latest)
        val other=ComposerDraftSnapshot("b","independent","model-b",false)
        saves.enqueue(other)
        withTimeout(2000){saves.awaitLatest("b")};assertEquals(other,stored["b"])
        val returning=async{saves.awaitLatest("a");stored["a"]};yield();assertFalse(returning.isCompleted)
        allowA.complete(Unit)
        assertEquals(latest,withTimeout(2000){returning.await()})
        assertEquals(listOf("a:first","a:latest 한글🙂"),order.filter{it.startsWith("a:")})
        assertEquals(other,stored["b"])
    }

    @Test fun cancellingASelectionWaiterNeverCancelsAcceptedComposerWrites()=runBlocking {
        val entered=CompletableDeferred<Unit>();val allowWrite=CompletableDeferred<Unit>()
        var stored:ComposerDraftSnapshot?=null
        val saves=ChatComposerPersistence(this){snapshot->entered.complete(Unit);allowWrite.await();stored=snapshot}
        val accepted=ComposerDraftSnapshot("a","accepted unsent draft","chosen-model",true)
        val writer=saves.enqueue(accepted);entered.await()
        val cancelledSelection=launch{saves.awaitLatest("a")}
        yield();cancelledSelection.cancelAndJoin();assertTrue(writer.isActive)
        allowWrite.complete(Unit);withTimeout(2000){saves.awaitLatest("a")}
        assertEquals(accepted,stored);assertFalse(writer.isCancelled)
    }
}
