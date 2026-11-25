package com.user.localagent.core.connections

import com.user.localagent.core.BookingReadback
import com.user.localagent.core.ToolCall
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BookingCalendarFollowUpTest {
    private val checkIn=OffsetDateTime.parse("2026-11-28T15:00:00+09:00")
    private val checkOut=OffsetDateTime.parse("2026-11-29T11:00:00+09:00")

    private fun booking(workId:String,reservationId:String="fixture-booking"):BookingReadback.VerifiedBooking {
        val now=Instant.parse("2026-11-25T10:00:00Z")
        val expected=BookingReadback.Expected(workId,"https://merchant.example.com","https://payment.example.com",
            "fixture-merchant",reservationId,"fixture-checkout","A-06",checkIn,checkOut,4,150_000,"KRW",now.minusSeconds(60))
        val reservation=BookingReadback.Reservation(
            BookingReadback.Source("${expected.bookingOrigin}/reservations",workId,"merchant-readback",now),
            expected.merchantId,reservationId,expected.checkoutId,expected.siteId,checkIn,checkOut,expected.guests,
            expected.amountMinor,expected.currency,BookingReadback.ReservationState.CONFIRMED)
        val payment=BookingReadback.Payment(
            BookingReadback.Source("${expected.paymentOrigin}/receipts",workId,"payment-readback",now),
            expected.merchantId,reservationId,expected.checkoutId,expected.amountMinor,expected.currency,
            BookingReadback.PaymentState.PAID,"fixture-transaction")
        return checkNotNull(BookingReadback.verify(expected,reservation,payment,now).verifiedBooking)
    }

    private fun call(id:String,title:String="글램핑 예약",calendarId:String?=null,
        privateEvent:Boolean=true,start:String=checkIn.toString(),end:String=checkOut.toString()) =
        ToolCall(id,CalendarTargetIntegrity.CREATE,buildJsonObject {
            put("title",title)
            put("start",start)
            put("end",end)
            put("private",privateEvent)
            calendarId?.let {put("calendarId",it)}
        })

    private fun approve(host:FixtureHost,call:ToolCall,proof:BookingReadback.VerifiedBooking):ApprovedCalendarTarget {
        val review=CalendarTargetIntegrity.reviewVerifiedBooking(call,host.access,"Fixture owner",proof)
        assertTrue(review.approvalText().contains(proof.reservationId))
        return CalendarTargetIntegrity.ownerConfirmed(review,review.approvalText())
    }

    @Test fun independentlyVerifiedBookingAcrossRunsOnlyCreatesOnePrivateEvent()=runBlocking {
        val host=FixtureHost()
        val first=call("first-run")
        val firstApproval=approve(host,first,booking("first-run"))
        val runtime=ConnectionRuntime(host)
        assertTrue(runtime.executeApprovedCalendar(first,firstApproval).success)
        assertEquals(listOf("GET","POST","GET"),host.methods)
        val saved=host.events.values.single()
        assertEquals("private",saved.getValue("visibility").jsonPrimitive.content)
        assertFalse("attendees" in saved)
        assertFalse(saved.toString().contains("fixture-booking"))
        assertTrue(host.posts.single().url.endsWith("?sendUpdates=none"))

        host.access=host.access.copy(generation=2)
        val next=call("later-run",calendarId="primary",start=checkIn.toInstant().toString(),end=checkOut.toInstant().toString())
        val reopenedRuntime=ConnectionRuntime(host)
        assertFalse(reopenedRuntime.executeApprovedCalendar(next,firstApproval).success)
        assertFalse(reopenedRuntime.execute(next).success)
        assertEquals(listOf("GET","POST","GET"),host.methods)
        val replay=reopenedRuntime.executeApprovedCalendar(next,approve(host,next,booking("later-run")))
        assertTrue(replay.data,replay.success)
        assertEquals(listOf("GET","POST","GET","GET"),host.methods)
        assertEquals(1,host.events.size)
    }

    @Test fun changedApprovedDestinationOrTitleCannotCreateASecondEvent()=runBlocking {
        val host=FixtureHost()
        val proof=booking("first-run")
        val runtime=ConnectionRuntime(host)
        val first=call("first-run")
        assertTrue(runtime.executeApprovedCalendar(first,approve(host,first,proof)).success)
        for (changed in listOf(call("new-title",title="다른 제목"),call("new-target",calendarId="other"))) {
            val outcome=runtime.executeApprovedCalendar(changed,approve(host,changed,proof))
            assertEquals("operation_key_conflict",outcome.code)
        }
        assertEquals(listOf("GET","POST","GET"),host.methods)
    }

    @Test fun cancellationAfterProviderCommitRecoversByReadbackOnly()=runBlocking {
        val host=FixtureHost().also {it.cancelAfterPost=true}
        val first=call("interrupted-run")
        try {
            ConnectionRuntime(host).executeApprovedCalendar(first,approve(host,first,booking("interrupted-run")))
            fail("Cancellation must be propagated")
        } catch (_:CancellationException) {}

        val next=call("recovered-run")
        val outcome=ConnectionRuntime(host).executeApprovedCalendar(next,approve(host,next,booking("recovered-run")))
        assertTrue(outcome.data,outcome.success)
        assertEquals(listOf("GET","POST","GET"),host.methods)
    }

    @Test fun reconciliationWithAbsentJournalRequiresExactPrivateBookingMarker()=runBlocking {
        val host=FixtureHost()
        val proof=booking("first-run")
        val runtime=ConnectionRuntime(host)
        val first=call("first-run")
        assertTrue(runtime.executeApprovedCalendar(first,approve(host,first,proof)).success)

        host.journal.clear()
        val next=call("fresh-run")
        assertTrue(ConnectionRuntime(host).executeApprovedCalendar(next,approve(host,next,proof)).success)
        assertEquals(listOf("GET","POST","GET","GET"),host.methods)

        host.journal.clear()
        val key=host.events.keys.single()
        host.events[key]=JsonObject(host.events.getValue(key).filterKeys {it!="extendedProperties"})
        val outcome=ConnectionRuntime(host).executeApprovedCalendar(next,approve(host,next,proof))
        assertEquals("readback_mismatch",outcome.code)
        assertEquals(1,host.posts.size)
    }

    @Test fun anotherReservationCanCreateItsOwnEventForTheSameTime()=runBlocking {
        val host=FixtureHost()
        val runtime=ConnectionRuntime(host)
        val first=call("first-run")
        val next=call("new-reservation")
        assertTrue(runtime.executeApprovedCalendar(first,approve(host,first,booking("first-run"))).success)
        assertTrue(runtime.executeApprovedCalendar(next,approve(host,next,booking("new-reservation","other-reservation"))).success)
        assertEquals(2,host.events.size)
    }

    @Test fun reviewRejectsPublicEventAndTimesThatDifferFromVerifiedBooking() {
        val proof=booking("first-run")
        val access=FixtureHost().access
        val invalid=listOf(call("public",privateEvent=false),
            call("different-start",start=checkIn.plusMinutes(1).toString()),
            call("different-end",end=checkOut.plusMinutes(1).toString()))
        for (event in invalid) {
            assertThrows(IllegalArgumentException::class.java) {
                CalendarTargetIntegrity.reviewVerifiedBooking(event,access,"Fixture owner",proof)
            }
        }
    }

    private class FixtureHost:ConnectionHost {
        var access=ConnectionAccess("fixture-account",1,true,true)
        val journal=mutableMapOf<String,MutationRecord>()
        val events=mutableMapOf<String,JsonObject>()
        val methods=mutableListOf<String>()
        val posts=mutableListOf<ApiRequest>()
        var cancelAfterPost=false

        override suspend fun access(service:ConnectedService)=access
        override fun mutation(key:String)=journal[key]
        override fun saveMutation(key:String,record:MutationRecord){journal[key]=record}
        override fun reserveRequest(service:ConnectedService)=true
        override suspend fun send(service:ConnectedService,request:ApiRequest):ApiResponse {
            methods.add(request.method)
            if (request.method=="GET") {
                val event=events[request.url] ?: return ApiResponse(404,"{}")
                return ApiResponse(200,event.toString())
            }
            posts.add(request)
            val payload=Json.parseToJsonElement(checkNotNull(request.body)).jsonObject
            val id=payload.getValue("id").jsonPrimitive.content
            val key="${request.url.substringBefore('?')}/$id"
            assertFalse(key in events)
            events[key]=JsonObject(payload+("status" to JsonPrimitive("confirmed")))
            if (cancelAfterPost) throw CancellationException("Fixture interrupted after provider commit")
            return ApiResponse(200,buildJsonObject {put("id",id)}.toString())
        }
    }
}
