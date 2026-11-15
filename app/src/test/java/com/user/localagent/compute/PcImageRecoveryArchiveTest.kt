package com.user.localagent.compute

import com.user.localagent.core.PcImageModelCatalog
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PcImageRecoveryArchiveTest {
    private val now=100_000_000L
    private fun ticket(created:Long=now)=PcImageRecoveryTicket(UUID.randomUUID().toString(),UUID.randomUUID().toString(),
        "a".repeat(64),PcImageModelCatalog.QUALITY_ID,73,50,created,buildJsonObject{put("originalPrompt","synthetic fixture")})
    private fun encoded(vararg tickets:PcImageRecoveryTicket)=JsonArray(tickets.map{Json.parseToJsonElement(it.json())}).toString()
    private inner class Fixture {
        var active="";var archived=""
        var setActive:(String)->Unit={active=it};var setArchive:(String)->Unit={archived=it}
        fun helper()=PcImageRecoveryArchive({active},{setActive(it)},{archived},{setArchive(it)},{now})
    }
    @Test fun explicitArchiveThenRestorePreservesAllOriginalConditionsWithoutChangingModel(){
        val f=Fixture();val original=ticket();f.active=original.json();val h=f.helper()
        assertTrue(h.archiveActive());assertEquals("",f.active)
        assertEquals(listOf(original),h.available())
        assertEquals(original,h.restoreMostRecent())
        assertEquals(original,PcImageRecoveryTicket.parse(f.active));assertTrue(h.available().isEmpty())
    }
    @Test fun failedArchiveWriteOrReadbackNeverClearsActive(){
        for(throws in listOf(true,false)){
            val f=Fixture();val original=ticket();f.active=original.json()
            f.setArchive={if(throws)error("synthetic write failure")}
            assertThrows(IllegalStateException::class.java){f.helper().archiveActive()}
            assertEquals(original.json(),f.active);assertEquals("",f.archived)
        }
    }
    @Test fun failedActiveClearLeavesAnExactDuplicateAndCanBeRetriedWithoutConsumingCapacity(){
        val f=Fixture();val original=ticket();f.active=original.json();f.setActive={error("synthetic clear failure")}
        assertThrows(IllegalStateException::class.java){f.helper().archiveActive()}
        assertEquals(original.json(),f.active);assertEquals(encoded(original),f.archived)
        assertTrue(f.helper().available().isEmpty())
        f.setActive={f.active=it}
        assertTrue(f.helper().archiveActive());assertEquals(listOf(original),f.helper().available())
    }
    @Test fun failedRestoreWriteOrReadbackRetainsArchive(){
        for(throws in listOf(true,false)){
            val f=Fixture();val original=ticket();f.archived=encoded(original)
            f.setActive={if(throws)error("synthetic active write failure")}
            assertThrows(IllegalStateException::class.java){f.helper().restoreMostRecent()}
            assertEquals("",f.active);assertEquals(encoded(original),f.archived)
        }
    }
    @Test fun failedArchiveRemovalAfterRestoreLeavesActiveAndArchivedCopyWithoutDoubleCounting(){
        val f=Fixture();val original=ticket();f.archived=encoded(original);f.setArchive={error("synthetic removal failure")}
        assertThrows(IllegalStateException::class.java){f.helper().restoreMostRecent()}
        assertEquals(original.json(),f.active);assertEquals(encoded(original),f.archived)
        assertTrue(f.helper().available().isEmpty())
    }
    @Test fun malformedOrConflictingArchiveBlocksMutation(){
        val original=ticket()
        for(raw in listOf("{","{}",encoded(original,original.copy(seed=74)))){
            val f=Fixture();f.active=original.json();f.archived=raw
            assertThrows(Exception::class.java){f.helper().archiveActive()}
            assertEquals(original.json(),f.active);assertEquals(raw,f.archived)
            f.active=""
            assertThrows(Exception::class.java){f.helper().restoreMostRecent()}
            assertEquals("",f.active);assertEquals(raw,f.archived)
        }
    }
    @Test fun fullValidArchiveRefusesNewTicketWithoutRemovingAnyValidWork(){
        val f=Fixture();val originals=List(4){ticket()};f.archived=encoded(*originals.toTypedArray());val next=ticket();f.active=next.json()
        val before=f.archived
        assertThrows(IllegalArgumentException::class.java){f.helper().archiveActive()}
        assertEquals(before,f.archived);assertEquals(next.json(),f.active)
    }
    @Test fun explicitArchivePrunesOnlyExpiredMetadataAndNeverPrunesStillValidWork(){
        val f=Fixture();val valid=List(3){ticket()};val expired=ticket(now-25*60*60_000L)
        f.archived=encoded(*(valid+expired).toTypedArray());val before=f.archived
        assertEquals(valid.asReversed(),f.helper().available());assertEquals(before,f.archived)
        val next=ticket();f.active=next.json();assertTrue(f.helper().archiveActive())
        assertEquals((valid+next).asReversed(),f.helper().available())
        val allExpired=Fixture();allExpired.archived=encoded(*List(4){ticket(now-25*60*60_000L)}.toTypedArray())
        allExpired.active=next.json();assertTrue(allExpired.helper().archiveActive())
        assertEquals(listOf(next),allExpired.helper().available())
    }
    @Test fun restoreRequiresEmptyActiveAndUsesMostRecentlyArchivedValidTicket(){
        val f=Fixture();val first=ticket();val last=ticket(now-10_000);f.archived=encoded(first,last)
        val current=ticket();f.active=current.json();val before=f.archived
        assertThrows(IllegalStateException::class.java){f.helper().restoreMostRecent()}
        assertEquals(current.json(),f.active);assertEquals(before,f.archived)
        f.active="";assertEquals(last,f.helper().restoreMostRecent())
        assertEquals(listOf(first),f.helper().available())
    }
    @Test fun byteBoundRejectsOversizedUnicodeArchiveBeforeAnySourceRemoval(){
        val f=Fixture();val first=ticket().copy(context=buildJsonObject{put("input","가".repeat(11_000))})
        f.archived=encoded(first);val next=ticket().copy(context=first.context);f.active=next.json();val before=f.archived
        assertThrows(IllegalArgumentException::class.java){f.helper().archiveActive()}
        assertEquals(next.json(),f.active);assertEquals(before,f.archived)
    }
    @Test fun changedActiveDuringArchiveCannotBeClearedByTheOldCopy(){
        val f=Fixture();val original=ticket();val replacement=ticket();f.active=original.json()
        f.setArchive={f.archived=it;f.active=replacement.json()}
        assertThrows(IllegalStateException::class.java){f.helper().archiveActive()}
        assertEquals(replacement.json(),f.active);assertEquals(encoded(original),f.archived)
    }
}
