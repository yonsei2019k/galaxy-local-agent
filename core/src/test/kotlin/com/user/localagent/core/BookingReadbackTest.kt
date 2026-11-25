package com.user.localagent.core

import com.user.localagent.core.BookingReadback.EvidenceStatus
import com.user.localagent.core.BookingReadback.PaymentState
import com.user.localagent.core.BookingReadback.ReservationState
import java.time.Instant
import java.time.OffsetDateTime
import org.junit.Assert.*
import org.junit.Test

class BookingReadbackTest {
    private val openedAt = Instant.parse("2026-11-25T12:00:00Z")
    private val observedAt = openedAt.plusSeconds(60)
    private val now = openedAt.plusSeconds(120)
    private val expected = BookingReadback.Expected(
        "work-1", "https://booking.example.com", "https://pay.example.com", "merchant-1",
        "reservation-1", "checkout-1", "A-06", OffsetDateTime.parse("2026-12-01T15:00+09:00"),
        OffsetDateTime.parse("2026-12-02T11:00+09:00"), 4, 150_000, "KRW", openedAt,
    )
    private val reservation = BookingReadback.Reservation(
        BookingReadback.Source("https://booking.example.com/history/confirmed", "work-1", "response-1", observedAt),
        expected.merchantId, expected.reservationId, expected.checkoutId, expected.siteId, expected.checkIn,
        expected.checkOut, expected.guests, expected.amountMinor, expected.currency, ReservationState.CONFIRMED,
        "https://booking.example.com/confirmation/reservation-1",
    )
    private val payment = BookingReadback.Payment(
        BookingReadback.Source("https://pay.example.com/transactions/observed", "work-1", "response-2", observedAt),
        expected.merchantId, expected.reservationId, expected.checkoutId, expected.amountMinor, expected.currency,
        PaymentState.PAID, "transaction-1", "https://pay.example.com/receipts/transaction-1",
    )

    @Test fun exactIndependentReadbacksProduceProofAndSafeLinks() {
        val result = BookingReadback.verify(expected, reservation, payment, now)
        assertEquals(BookingReadback.Check(EvidenceStatus.VERIFIED, ReservationState.CONFIRMED), result.reservation)
        assertEquals(BookingReadback.Check(EvidenceStatus.VERIFIED, PaymentState.PAID), result.payment)
        val verified = checkNotNull(result.verifiedBooking)
        assertEquals(expected.bookingOrigin, verified.bookingOrigin)
        assertEquals(expected.reservationId, verified.reservationId)
        assertEquals(expected.siteId, verified.siteId)
        assertEquals(expected.checkIn, verified.checkIn)
        assertEquals(reservation.confirmationUrl, verified.confirmationUrl)
        assertEquals(payment.receiptUrl, verified.receiptUrl)
        assertFalse(verified.toString().contains(expected.reservationId))
    }

    @Test fun enoughToReportOneStateDoesNotProveTheOther() {
        val reservationOnly = BookingReadback.verify(expected, reservation, null, now)
        assertEquals(EvidenceStatus.VERIFIED, reservationOnly.reservation.evidence)
        assertEquals(EvidenceStatus.MISSING, reservationOnly.payment.evidence)
        assertNull(reservationOnly.verifiedBooking)
        val chargeOnly = BookingReadback.verify(expected, reservation.copy(state = ReservationState.CANCELLED), payment, now)
        assertEquals(ReservationState.CANCELLED, chargeOnly.reservation.state)
        assertEquals(PaymentState.PAID, chargeOnly.payment.state)
        assertNull(chargeOnly.verifiedBooking)
        for (state in PaymentState.entries.filterNot { it == PaymentState.PAID }) {
            val result = BookingReadback.verify(expected, reservation, payment.copy(state = state), now)
            assertEquals(state, result.payment.state)
            assertNull(result.verifiedBooking)
        }
    }

    @Test fun sameResponseCannotServeAsTwoIndependentReadbacks() {
        val duplicate = payment.copy(source = payment.source.copy(responseId = reservation.source.responseId))
        val result = BookingReadback.verify(expected, reservation, duplicate, now)
        assertEquals(EvidenceStatus.NOT_INDEPENDENT, result.reservation.evidence)
        assertEquals(EvidenceStatus.NOT_INDEPENDENT, result.payment.evidence)
        assertNull(result.verifiedBooking)
    }

    @Test fun staleFutureForgedAndCrossWorkSourcesFailClosed() {
        for (badSource in listOf(
            payment.source.copy(observedAt = openedAt.minusNanos(1)), payment.source.copy(observedAt = now.plusNanos(1)),
            payment.source.copy(workId = "another-work"), payment.source.copy(responseId = ""),
            payment.source.copy(url = "https://pay.example.com.evil.test/transaction"),
            payment.source.copy(url = "https://pay.example.com@evil.test/transaction"),
            payment.source.copy(url = "http://pay.example.com/transaction"),
        )) {
            val result = BookingReadback.verify(expected, reservation, payment.copy(source = badSource), now)
            assertEquals(EvidenceStatus.MISMATCH, result.payment.evidence)
            assertEquals(EvidenceStatus.VERIFIED, result.reservation.evidence)
            assertNull(result.verifiedBooking)
        }
    }

    @Test fun matchingFieldsMustBelongToTheSameStayAndCharge() {
        for (wrongStay in listOf(
            reservation.copy(siteId = "A-07"), reservation.copy(guests = 3),
            reservation.copy(checkOut = reservation.checkOut.plusDays(1)), reservation.copy(amountMinor = 150_001),
        )) {
            val result = BookingReadback.verify(expected, wrongStay, payment, now)
            assertEquals(EvidenceStatus.MISMATCH, result.reservation.evidence)
            assertNull(result.verifiedBooking)
        }
        for (wrongCharge in listOf(
            payment.copy(merchantId = "another-merchant"), payment.copy(reservationId = "another-reservation"),
            payment.copy(checkoutId = "another-checkout"), payment.copy(amountMinor = 149_999),
            payment.copy(currency = "USD"), payment.copy(transactionId = null),
        )) {
            val result = BookingReadback.verify(expected, reservation, wrongCharge, now)
            assertEquals(EvidenceStatus.MISMATCH, result.payment.evidence)
            assertNull(result.verifiedBooking)
        }
    }

    @Test fun untrustedAndSignedReceiptLinksAreDroppedBeforePresentation() {
        val result = BookingReadback.verify(expected,
            reservation.copy(confirmationUrl = "https://booking.example.com/confirmation?token=secret"),
            payment.copy(receiptUrl = "https://evil.test/receipts/transaction-1"), now)
        val verified = checkNotNull(result.verifiedBooking)
        assertNull(verified.confirmationUrl)
        assertNull(verified.receiptUrl)
    }

    @Test fun malformedExpectationCannotCreateProof() {
        for (bad in listOf(
            expected.copy(bookingOrigin = "https://booking.example.com/other"),
            expected.copy(paymentOrigin = "https://127.0.0.1"), expected.copy(checkOut = expected.checkIn),
            expected.copy(currency = "xxx"), expected.copy(amountMinor = 0), expected.copy(notBefore = now.plusSeconds(1)),
        )) {
            val result = BookingReadback.verify(bad, reservation, payment, now)
            assertEquals(EvidenceStatus.INVALID_EXPECTATION, result.reservation.evidence)
            assertEquals(EvidenceStatus.INVALID_EXPECTATION, result.payment.evidence)
            assertNull(result.verifiedBooking)
        }
    }
}
