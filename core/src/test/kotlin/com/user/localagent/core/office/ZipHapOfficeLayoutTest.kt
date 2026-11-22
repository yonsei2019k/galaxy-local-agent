package com.user.localagent.core.office

import org.junit.Assert.*
import org.junit.Test

class ZipHapOfficeLayoutTest {
    @Test fun fixedRoomsKeepTheChairInMainAndGiveEachEmployeeOneRoom() {
        assertEquals(5,ZipHapRoom.entries.size)
        assertEquals(OfficeSeat.CHAIR,ZipHapRoom.MAIN.agent)
        assertNull(ZipHapRoom.OFFICE.agent)
        assertEquals(listOf("과장 김호영","대리 김규민","대리 김서율"),
            ZipHapRoom.entries.drop(2).map{it.label})
    }

    @Test fun onlyTheChairAssignsAndPublishesTheMainSummary() {
        val employees=OfficeSeat.entries.filter{it!=OfficeSeat.CHAIR}.toSet()
        assertEquals(employees,ZipHapOfficeLayout.assignmentRecipients(OfficeSeat.CHAIR,employees))
        for(employee in employees){
            assertEquals(OfficeSeat.CHAIR,ZipHapOfficeLayout.resultRecipient(employee))
            assertFalse(ZipHapOfficeLayout.mayPublishMainSummary(employee))
            assertThrows(IllegalArgumentException::class.java){
                ZipHapOfficeLayout.assignmentRecipients(employee,employees)
            }
        }
        assertTrue(ZipHapOfficeLayout.mayPublishMainSummary(OfficeSeat.CHAIR))
    }
}
