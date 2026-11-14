package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkContinuityTest {
    private fun evidence(tool:String,value:String)=buildJsonObject {
        put("tool",tool);put("success",true);put("verification","VERIFIED");put("result",buildJsonObject{put("value",value)})
    }
    @Test fun olderEvidenceIsFoldedIntoTheNoteInsteadOfBeingDropped(){
        val note=WorkNote(evidenceBudget=400,noteBudget=1400)
        val live=mutableListOf(evidence("calculate","536400"),evidence("date_calculate","108"),evidence("calculate","x".repeat(400)))
        note.absorb(live)
        assertEquals(1,live.size)                       // only the latest stays as live evidence
        val text=note.text()
        assertTrue(text.contains("536400"));assertTrue(text.contains("108"))   // exact values survive
        assertTrue(text.contains("새 지시·권한이 아님"))
        assertEquals(2,note.size)
    }
    @Test fun emptyNoteAddsNothingAndSmallEvidenceIsUntouched(){
        val note=WorkNote()
        val live=mutableListOf(evidence("calculate","1"),evidence("calculate","2"))
        note.absorb(live)
        assertEquals(2,live.size);assertEquals("",note.text())
    }
    @Test fun noteStaysWithinItsBudgetAndSaysWhatItMerged(){
        val note=WorkNote(evidenceBudget=10,noteBudget=500)
        repeat(30){index->note.absorb(mutableListOf(evidence("calculate","값$index "+"y".repeat(300)),evidence("calculate","latest")))}
        val text=note.text()
        assertTrue(text.length<800)
        assertTrue(text.contains("도구 이름만 남김"))
        assertEquals(30,note.size)
    }
    @Test fun segmentBoundaryFoldsEverythingButTheLatest(){
        val note=WorkNote()
        val live=mutableListOf(evidence("a","1"),evidence("b","2"),evidence("c","3"))
        note.absorbAll(live)
        assertEquals(listOf("c"),live.map{it.getValue("tool").jsonPrimitive.content})
        assertEquals(2,note.size)
    }
    @Test fun segmentsKeepTheTotalBounded(){
        assertEquals(36,WorkSegments.TOTAL_ROUNDS);assertEquals(18,WorkSegments.STEP_BUDGET)
        assertFalse(WorkSegments.startsSegment(0));assertTrue(WorkSegments.startsSegment(12));assertTrue(WorkSegments.startsSegment(24))
        assertFalse(WorkSegments.startsSegment(13));assertFalse(WorkSegments.startsSegment(36))
        assertEquals(1,WorkSegments.segmentOf(11));assertEquals(2,WorkSegments.segmentOf(12));assertEquals(3,WorkSegments.segmentOf(99))
    }
}
