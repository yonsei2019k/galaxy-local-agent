package com.user.localagent.core.office

import org.junit.Assert.*
import org.junit.Test

class OfficeWorkTest {
    private val id="00000000-0000-4000-8000-000000000001"
    private val chair=OfficeSeat.CHAIR
    private val first=OfficeSeat.DIRECTOR
    private val second=OfficeSeat.ASSOCIATE_GYUMIN
    private val a=OfficeWork.Report("run-first","조건을 확인했습니다.",true)
    private val b=OfficeWork.Report("run-second","재고를 확인하지 못했습니다.",false)

    @Test fun chairWaitsForEveryAssignedEmployeeAndSeesFailures(){
        val work=OfficeWork.assign(id,chair,"조건을 확인해 주세요.",setOf(first,second))
        val partial=work.receive(id,first,a)
        assertThrows(IllegalStateException::class.java){partial.collect(chair)}
        val done=partial.receive(id,second,b)
        assertFalse(done.collect(chair).getValue(second).succeeded)
        assertThrows(IllegalStateException::class.java){done.collect(first)}
    }

    @Test fun duplicatesCannotReplaceOrMoveReports(){
        val work=OfficeWork.assign(id,chair,"비교해 주세요.",setOf(first,second)).receive(id,first,a)
        assertSame(work,work.receive(id,first,a))
        assertThrows(IllegalStateException::class.java){work.receive(id,first,b)}
        assertThrows(IllegalStateException::class.java){work.receive(id,second,a)}
        assertThrows(IllegalArgumentException::class.java){work.receive("wrong",second,b)}
        assertThrows(IllegalArgumentException::class.java){work.receive(id,OfficeSeat.ASSOCIATE_SEOYUL,b)}
    }
}
