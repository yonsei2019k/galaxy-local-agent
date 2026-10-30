package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test
import java.time.OffsetDateTime

class ScheduleExtractionTest {
    private val anchor=OffsetDateTime.parse("2026-10-30T12:00:00+09:00").toInstant().toEpochMilli()
    private fun one(text:String,at:Long=anchor)=ScheduleExtraction.extract(text,at).single()
    @Test fun explicitKoreanAppointmentAndDefaultHour(){val c=one("[치과] 진료 예약 2026년 11월 2일 오후 3시 30분");assertEquals("2026-11-02T15:30+09:00",c.startIso);assertEquals("2026-11-02T16:30+09:00",c.endIso);assertFalse(c.needsReview)}
    @Test fun dotIsoAnd24HourClocks(){assertEquals("2026-11-02T15:00+09:00",one("면접 2026.11.2 15:00").startIso);assertEquals("2026-11-02T09:00+09:00",one("검진 2026-11-02 09:00").startIso)}
    @Test fun noonAndMidnight(){assertEquals("2026-11-02T12:00+09:00",one("출발 2026-11-02 오후 12시").startIso);assertEquals("2026-11-02T00:00+09:00",one("출발 2026-11-02 오전 12시").startIso)}
    @Test fun relativeDatesUseMessageSeoulDate(){val at=OffsetDateTime.parse("2026-10-30T16:00:00Z").toInstant().toEpochMilli();assertEquals("2026-11-01T10:00+09:00",one("예약 내일 오전 10시",at).startIso);assertEquals("2026-11-02T10:00+09:00",one("예약 모레 오전 10시",at).startIso)}
    @Test fun yearlessDateAnchorsToSmsAndCrossesYear(){val at=OffsetDateTime.parse("2026-12-30T12:00:00+09:00").toInstant().toEpochMilli();assertEquals("2027-01-02T15:00+09:00",one("예약 1월 2일 오후3시",at).startIso);assertEquals("2026-11-02T15:00+09:00",one("예약 11/2 15:00").startIso)}
    @Test fun missingOrAmbiguousHourDoesNotInventTime(){val absent=one("검진 예약 2026.11.2");assertTrue(absent.needsReview);assertNull(absent.startIso);val unclear=one("진료 예약 2026.11.2 3시");assertTrue(unclear.needsReview);assertNull(unclear.startIso)}
    @Test fun colonMinutesAreRequiredAndValidated(){listOf("13:","13:5","13:123","13:99").forEach {val c=one("예약 2026.11.2 $it");assertTrue(c.needsReview);assertNull(c.startIso)}}
    @Test fun multipleDatesAndTimesRequireReview(){assertTrue(one("회의 2026.11.2 15:00 / 2026.11.3 16:00").needsReview);assertTrue(one("예약 2026.11.2 15:00~16:00").needsReview)}
    @Test fun finalizedCancellationWinsOverFooterPolicy(){assertTrue(ScheduleExtraction.extract("[예약취소] 예약 취소 안내 2026.11.2 15:00. 취소를 원하시면 연락",anchor).isEmpty());assertTrue(ScheduleExtraction.extract("예약이 취소되었습니다. 2026.11.2 오후3시",anchor).isEmpty())}
    @Test fun ordinaryCancellationInstructionsDoNotCancelBooking(){assertFalse(ScheduleExtraction.extract("예약 완료 2026.11.2 오후3시. 취소하려면 연락하세요.",anchor).isEmpty())}
    @Test fun invalidCalendarDateIsReviewOnly(){val c=one("예약 2026.2.30 오후3시");assertTrue(c.needsReview);assertNull(c.startIso)}
    @Test fun nonScheduleMessagesAndMarketing(){assertTrue(ScheduleExtraction.extract("인증번호 123456",anchor).isEmpty());assertTrue(one("(광고) 공연 2026.11.2 오후3시").needsReview)}
}
