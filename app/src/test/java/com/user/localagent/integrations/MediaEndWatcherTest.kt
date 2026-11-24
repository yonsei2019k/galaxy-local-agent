package com.user.localagent.integrations

import com.user.localagent.core.BrowserMediaSample
import com.user.localagent.core.WakeEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaEndWatcherTest {
    private val url="https://example.org/lesson"
    private val initial=BrowserMediaSample("${"a".repeat(32)}:1",0,0,56_000,false,false,false,4,1.0)

    @Test fun changedPlayerOrLostConditionsDoesNotMeanEnded() = runBlocking {
        val other=initial.copy(epoch=1,ended=true,positionMs=56_000)
        fun watcher(connected:Boolean)=MediaEndWatcher({MediaEndWatcher.Observation(url,other)},{connected},{0L},{})
        assertEquals(WakeEvent.PLAYER_GONE,watcher(true).watch(url,initial))
        assertEquals(WakeEvent.CONDITIONS_UNMET,watcher(false).watch(url,initial))
    }

    @Test fun actualTimeProgressAndEndSettlesTheSamePlayer() = runBlocking {
        var now=0L
        val watcher=MediaEndWatcher(
            {MediaEndWatcher.Observation(url,initial.copy(positionMs=now,ended=now==56_000L))},
            {true},{now},{now+=it},
        )
        assertEquals(WakeEvent.MEDIA_ENDED,watcher.watch(url,initial))
    }

    @Test fun stalledMediaStopsAfterTheHostBudget() = runBlocking {
        var now=0L
        val watcher=MediaEndWatcher({MediaEndWatcher.Observation(url,initial)},{true},{now},{now+=it})
        assertEquals(WakeEvent.MEDIA_STALLED,watcher.watch(url,initial))
    }
}
