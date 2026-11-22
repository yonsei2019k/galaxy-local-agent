package com.user.localagent.core.office

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class OfficePersonasTest {
    private val today=LocalDate.of(2026,11,22)

    @Test fun unknownThreadHasNoInstruction() {
        assertNull(OfficePersonas.instruction("not-a-fixed-room",null,today))
    }

    @Test fun seatRoomsCarryTitleNameMbtiBoundaryAndCustomerTitleRule() {
        for(room in ZipHapRoom.entries.filter{it.agent!=null}) {
            val seat=checkNotNull(room.agent)
            val text=checkNotNull(OfficePersonas.instruction(room.threadId,null,today))
            assertTrue(text.contains("${seat.jobTitle} ${seat.displayName}"))
            assertTrue(text.contains(OfficePersonas.all.getValue(seat).mbti))
            assertTrue(text.contains("권한, 안전 규칙"))
            assertTrue(text.contains("고객에게 말할 때는 직급 호칭"))
        }
    }

    @Test fun directorIsAGwajangNotABonbujang() {
        val text=checkNotNull(OfficePersonas.instruction(ZipHapRoom.DIRECTOR.threadId,null,today))
        assertTrue(text.contains("과장 김호영"))
        assertFalse(text.contains("본부장"))
    }

    @Test fun officeRoomDefinesSpeakerFormatAndFamilyAddress() {
        val text=checkNotNull(OfficePersonas.instruction(ZipHapRoom.OFFICE.threadId,null,today))
        assertTrue(text.contains("**이름 직급:**"))
        assertTrue(text.contains("여보"))
        assertTrue(text.contains("규민아"))
        assertTrue(text.contains("권한, 안전 규칙"))
    }

    @Test fun birthDatesAppearOnlyWithAnOwnerProfileAndAgeIsComputed() {
        val without=checkNotNull(OfficePersonas.instruction(ZipHapRoom.OFFICE.threadId,null,today))
        assertFalse(without.contains("생("))
        val profile=OfficePrivateProfile(mapOf(OfficeSeat.CHAIR.name to OfficePrivateProfile.Entry("2000-01-01","초3")))
        val with=checkNotNull(OfficePersonas.instruction(ZipHapRoom.OFFICE.threadId,profile,today))
        assertTrue(with.contains("2000년 1월 1일생(만 26세)"))
        assertTrue(with.contains("초3"))
        // one day before the birthday still counts as the previous age
        val eve=checkNotNull(OfficePersonas.instruction(ZipHapRoom.MAIN.threadId,profile,LocalDate.of(2026,12,31)))
        assertTrue(eve.contains("만 26세"))
        val before=checkNotNull(OfficePersonas.instruction(ZipHapRoom.MAIN.threadId,profile,LocalDate.of(2026,1,1)))
        assertTrue(before.contains("만 26세"))
        val day=checkNotNull(OfficePersonas.instruction(ZipHapRoom.MAIN.threadId,profile,LocalDate.of(2026,1,1).minusDays(1)))
        assertTrue(day.contains("만 25세"))
    }

    @Test fun familyAddressMatrix() {
        assertEquals("여보",OfficePersonas.familyAddress(OfficeSeat.DIRECTOR,OfficeSeat.CHAIR))
        assertEquals("엄마",OfficePersonas.familyAddress(OfficeSeat.ASSOCIATE_GYUMIN,OfficeSeat.CHAIR))
        assertEquals("형",OfficePersonas.familyAddress(OfficeSeat.ASSOCIATE_SEOYUL,OfficeSeat.ASSOCIATE_GYUMIN))
        assertEquals("서율아",OfficePersonas.familyAddress(OfficeSeat.CHAIR,OfficeSeat.ASSOCIATE_SEOYUL))
        assertEquals("규민아",OfficePersonas.familyAddress(OfficeSeat.CHAIR,OfficeSeat.ASSOCIATE_GYUMIN))
        assertEquals("아빠",OfficePersonas.familyAddress(OfficeSeat.ASSOCIATE_GYUMIN,OfficeSeat.DIRECTOR))
    }

    @Test fun profileValidationRejectsBadInput() {
        fun profile(seat:String="CHAIR",birth:String?=null,grade:String?=null)=OfficePrivateProfile(mapOf(seat to OfficePrivateProfile.Entry(birth,grade)))
        profile(birth="2000-01-01",grade="초3").validated(today)
        assertThrows(IllegalArgumentException::class.java){profile(seat="STRANGER",grade="x").validated(today)}
        assertThrows(IllegalArgumentException::class.java){profile(birth="2026-11-23").validated(today)}
        assertThrows(IllegalArgumentException::class.java){profile(birth="2000/01/01").validated(today)}
        assertThrows(IllegalArgumentException::class.java){profile(grade="가".repeat(21)).validated(today)}
        assertThrows(IllegalArgumentException::class.java){profile(grade="a\u0007b").validated(today)}
        assertThrows(IllegalArgumentException::class.java){profile(grade=" 초3").validated(today)}
    }

    @Test fun jsonRoundTripAndRejectsUnknownKeys() {
        val original=OfficePrivateProfile(mapOf(OfficeSeat.ASSOCIATE_GYUMIN.name to OfficePrivateProfile.Entry("2000-01-01","초3")))
        assertEquals(original,OfficePrivateProfile.fromJson(original.toJson(),today))
        assertThrows(Exception::class.java){OfficePrivateProfile.fromJson("""{"entries":{},"extra":1}""",today)}
        assertThrows(IllegalArgumentException::class.java){OfficePrivateProfile.fromJson("x".repeat(OfficePrivateProfile.MAX_JSON_CHARS+1),today)}
    }

    @Test fun legacyDirectorLabelIsKnown() {
        assertTrue(OfficeSeat.DIRECTOR.legacyLabels.contains("본부장 김호영"))
        assertTrue(OfficeSeat.CHAIR.legacyLabels.isEmpty())
    }

    @Test fun familyValueAndRelationshipsAppearInEveryRoom() {
        for(room in ZipHapRoom.entries) {
            val text=checkNotNull(OfficePersonas.instruction(room.threadId,null,today))
            assertTrue(text.contains(OfficePersonas.FAMILY_VALUE))
        }
        assertTrue(checkNotNull(OfficePersonas.instruction(ZipHapRoom.MAIN.threadId,null,today)).contains("츤데레"))
        assertTrue(checkNotNull(OfficePersonas.instruction(ZipHapRoom.DIRECTOR.threadId,null,today)).contains("아내"))
        assertTrue(checkNotNull(OfficePersonas.instruction(ZipHapRoom.DIRECTOR.threadId,null,today)).contains("이 방에서는 당신 혼자 고객을 응대한다"))
    }
    @Test fun publicPersonaForTheCloudFitsTheWireLimitAndCarriesNoOwnerEnteredDetails(){
        val profile=OfficePrivateProfile(mapOf("CHAIR" to OfficePrivateProfile.Entry("2000-01-01","초3")))
        ZipHapRoom.entries.forEach{room->
            val public=checkNotNull(OfficePersonas.instruction(room.threadId,null,today))
            assertTrue("${room.name} ${public.length}",public.length<=6000&&public==public.trim())
            assertTrue(public.none{it.isISOControl()&&it!='\n'})
            assertFalse(public.contains("2000년")||public.contains("초3"))
            // Owner-entered details appear only on the local path that passes the profile.
            assertTrue(checkNotNull(OfficePersonas.instruction(room.threadId,profile,today)).contains("2000년"))
        }
    }
}
