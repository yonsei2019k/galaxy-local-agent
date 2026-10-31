package com.user.localagent.analysis

import org.junit.Assert.*
import org.junit.Test

class PrivateTimeReferenceIndexTest {
    @Test fun adjacentRecordBoundaryNeverBorrowsThePreviousPersonsTime() {
        val a=PrivateSourceTimeRef(0,100,atEpochMillis=10,participantKey="synthetic-a")
        val b=PrivateSourceTimeRef(100,200,atEpochMillis=20,participantKey="synthetic-b")
        val index=PrivateTimeReferenceIndex(listOf(a,b),200)
        assertEquals(listOf(b),index.overlapping(100,101,2))
        assertEquals(listOf(a,b),index.overlapping(99,101,2))
        assertEquals(emptyList<PrivateSourceTimeRef>(),index.overlapping(200,200,2))
    }
    @Test fun gapsAndResultLimitPreserveOnlyActualOverlappingByteSpans() {
        val refs=listOf(PrivateSourceTimeRef(10,20),PrivateSourceTimeRef(30,40),PrivateSourceTimeRef(40,50))
        val index=PrivateTimeReferenceIndex(refs,100)
        assertTrue(index.overlapping(20,30,3).isEmpty())
        assertEquals(refs.drop(1).take(1),index.overlapping(35,50,1))
    }
    @Test fun largeLibraryTailLookupDoesNotReadAnUnrelatedTime() {
        val refs=(0 until 100_000).map {PrivateSourceTimeRef(it*10,it*10+10,atEpochMillis=it.toLong())}
        val index=PrivateTimeReferenceIndex(refs,1_000_000)
        assertEquals(99_999L,index.overlapping(999_995,1_000_000,3).single().atEpochMillis)
    }
    @Test fun malformedOrOverlappingSourceRangesAreRejectedInsteadOfCreatingWrongCitations() {
        for(refs in listOf(listOf(PrivateSourceTimeRef(2,1)),listOf(PrivateSourceTimeRef(0,10),PrivateSourceTimeRef(9,20)),listOf(PrivateSourceTimeRef(0,21)))) {
            assertTrue(runCatching {PrivateTimeReferenceIndex(refs,20)}.isFailure)
        }
    }
    @Test fun sourceListIsCapturedAndCannotChangeWhileTheReportIsRendered() {
        val original=mutableListOf(PrivateSourceTimeRef(0,10,atEpochMillis=1))
        val index=PrivateTimeReferenceIndex(original,20)
        original.clear();original+=PrivateSourceTimeRef(0,20,atEpochMillis=2)
        assertEquals(1L,index.overlapping(5,6,1).single().atEpochMillis)
    }
}
