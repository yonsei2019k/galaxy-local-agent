package com.user.localagent.ui

import org.junit.Assert.*
import org.junit.Test

class ConversationWorkSummaryTest {
    private fun run(id:String,room:String="room",updated:Long=1)=UiRunActivity(id,room,"answer-$id","running",updatedAt=updated)

    @Test fun activeSummaryCannotUseOtherRoomsOrOldRuns(){
        val active=run("active")
        val records=listOf(run("old",updated=100),run("active","other",200),active)
        assertEquals(active,summaryActivity(records,"room","active",true))
        assertNull(summaryActivity(records,"room","missing",true))
        assertNull(summaryActivity(records,"room",null,true))
    }

    @Test fun idleSummaryUsesLatestActualRoomRecord(){
        val latest=run("latest",updated=20).copy(status="failed")
        assertEquals(latest,summaryActivity(listOf(run("old"),latest,run("foreign","other",30)),"room",null,false))
    }
}
