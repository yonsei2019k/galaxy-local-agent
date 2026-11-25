package com.user.localagent.core

import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Currency
import java.util.Locale

/**
 * Reconciles two host-observed, authenticated readbacks against the checkout captured before handoff.
 * Connectors must construct [Source] from the actual final response and its locally assigned ID.
 * Checkout HTML, model reports, an app-return event, and a user pressing "done" are not these inputs.
 * This policy validates evidence only. It grants no payment, download or calendar authority.
 */
object BookingReadback {
    data class Expected(
        val workId: String,
        val bookingOrigin: String,
        val paymentOrigin: String,
        val merchantId: String,
        val reservationId: String,
        val checkoutId: String,
        val siteId: String,
        val checkIn: OffsetDateTime,
        val checkOut: OffsetDateTime,
        val guests: Int,
        val amountMinor: Long,
        val currency: String,
        val notBefore: Instant,
    ) {
        override fun toString() = "BookingReadback.Expected(private checkout)"
    }

    data class Source(val url: String, val workId: String, val responseId: String, val observedAt: Instant) {
        override fun toString() = "BookingReadback.Source(private observation)"
    }

    enum class ReservationState { CONFIRMED, PENDING, CANCELLED, UNKNOWN }
    enum class PaymentState { PAID, PENDING, DECLINED, CANCELLED, REFUNDED, UNKNOWN }

    data class Reservation(
        val source: Source,
        val merchantId: String,
        val reservationId: String,
        val checkoutId: String,
        val siteId: String,
        val checkIn: OffsetDateTime,
        val checkOut: OffsetDateTime,
        val guests: Int,
        val amountMinor: Long,
        val currency: String,
        val state: ReservationState,
        val confirmationUrl: String? = null,
    ) {
        override fun toString() = "BookingReadback.Reservation(state=$state)"
    }

    data class Payment(
        val source: Source,
        val merchantId: String,
        val reservationId: String,
        val checkoutId: String,
        val amountMinor: Long,
        val currency: String,
        val state: PaymentState,
        val transactionId: String? = null,
        val receiptUrl: String? = null,
    ) {
        override fun toString() = "BookingReadback.Payment(state=$state)"
    }

    enum class EvidenceStatus { VERIFIED, MISSING, MISMATCH, NOT_INDEPENDENT, INVALID_EXPECTATION }

    data class Check<T>(val evidence: EvidenceStatus, val state: T? = null)

    class Verification internal constructor(
        val reservation: Check<ReservationState>,
        val payment: Check<PaymentState>,
        val verifiedBooking: VerifiedBooking? = null,
    )

    /** Proof is created inside core after both independent readbacks match this exact checkout. */
    class VerifiedBooking internal constructor(
        val workId: String,
        val bookingOrigin: String,
        val merchantId: String,
        val reservationId: String,
        val siteId: String,
        val checkIn: OffsetDateTime,
        val checkOut: OffsetDateTime,
        val guests: Int,
        val amountMinor: Long,
        val currency: String,
        val confirmationUrl: String?,
        val receiptUrl: String?,
    ) {
        override fun toString() = "BookingReadback.VerifiedBooking(private reservation)"
    }

    fun verify(expected: Expected, reservation: Reservation?, payment: Payment?, now: Instant): Verification {
        val bookingOrigin = origin(expected.bookingOrigin, originOnly = true)
        val paymentOrigin = origin(expected.paymentOrigin, originOnly = true)
        if (bookingOrigin == null || paymentOrigin == null || !validExpected(expected, now)) {
            return Verification(Check(EvidenceStatus.INVALID_EXPECTATION), Check(EvidenceStatus.INVALID_EXPECTATION))
        }

        val reservationCheck: Check<ReservationState> = when {
            reservation == null -> Check(EvidenceStatus.MISSING)
            matches(reservation.source, bookingOrigin, expected, now) &&
                reservation.merchantId == expected.merchantId &&
                reservation.reservationId == expected.reservationId &&
                reservation.checkoutId == expected.checkoutId &&
                reservation.siteId == expected.siteId &&
                reservation.checkIn == expected.checkIn && reservation.checkOut == expected.checkOut &&
                reservation.guests == expected.guests &&
                reservation.amountMinor == expected.amountMinor && reservation.currency == expected.currency ->
                Check(EvidenceStatus.VERIFIED, reservation.state)
            else -> Check(EvidenceStatus.MISMATCH)
        }
        val paymentCheck: Check<PaymentState> = when {
            payment == null -> Check(EvidenceStatus.MISSING)
            matches(payment.source, paymentOrigin, expected, now) &&
                payment.merchantId == expected.merchantId &&
                payment.reservationId == expected.reservationId &&
                payment.checkoutId == expected.checkoutId &&
                payment.amountMinor == expected.amountMinor && payment.currency == expected.currency &&
                (payment.state !in setOf(PaymentState.PAID, PaymentState.REFUNDED) ||
                    payment.transactionId?.let(::validIdentifier) == true) ->
                Check(EvidenceStatus.VERIFIED, payment.state)
            else -> Check(EvidenceStatus.MISMATCH)
        }
        if (reservation == null || payment == null || reservationCheck.evidence != EvidenceStatus.VERIFIED ||
            paymentCheck.evidence != EvidenceStatus.VERIFIED) return Verification(reservationCheck, paymentCheck)
        if (reservation.source.responseId == payment.source.responseId) {
            return Verification(Check(EvidenceStatus.NOT_INDEPENDENT), Check(EvidenceStatus.NOT_INDEPENDENT))
        }
        val proof = if (reservationCheck.state == ReservationState.CONFIRMED && paymentCheck.state == PaymentState.PAID) {
            VerifiedBooking(expected.workId, bookingOrigin, expected.merchantId, expected.reservationId,
                expected.siteId, expected.checkIn, expected.checkOut, expected.guests, expected.amountMinor, expected.currency,
                displayLink(reservation.confirmationUrl, bookingOrigin), displayLink(payment.receiptUrl, paymentOrigin))
        } else null
        return Verification(reservationCheck, paymentCheck, proof)
    }

    private fun validExpected(expected: Expected, now: Instant): Boolean =
        listOf(expected.workId, expected.merchantId, expected.reservationId, expected.checkoutId, expected.siteId)
            .all(::validIdentifier) &&
            expected.checkOut.isAfter(expected.checkIn) &&
            expected.guests > 0 && expected.amountMinor > 0 && isCurrency(expected.currency) &&
            !expected.notBefore.isAfter(now)

    private fun validIdentifier(value: String): Boolean =
        value.isNotBlank() && value.length <= 256 && value == value.trim() && value.none(Char::isISOControl)

    private fun isCurrency(code: String): Boolean =
        code.matches(Regex("[A-Z]{3}")) && code != "XXX" && runCatching { Currency.getInstance(code) }.isSuccess

    private fun matches(source: Source, expectedOrigin: String, expected: Expected, now: Instant): Boolean =
        origin(source.url) == expectedOrigin && source.workId == expected.workId &&
            validIdentifier(source.responseId) && !source.observedAt.isBefore(expected.notBefore) &&
            !source.observedAt.isAfter(now)

    private fun origin(raw: String, originOnly: Boolean = false): String? {
        if (!WebAgentPolicy.urlAllowed(raw)) return null
        val uri = URI(raw)
        if (!uri.scheme.equals("https", true) || uri.port !in setOf(-1, 443) || uri.rawFragment != null) return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host.endsWith('.') || host.all { it.isDigit() || it == '.' } || ':' in host) return null
        if (originOnly && (uri.rawPath !in setOf("", "/") || uri.rawQuery != null)) return null
        return "https://$host"
    }

    private fun displayLink(raw: String?, expectedOrigin: String): String? {
        if (raw == null || origin(raw) != expectedOrigin) return null
        // Signed or session URLs stay in the acquiring connector. Only a query-free link may leave it.
        return raw.takeIf { URI(it).rawQuery == null && URI(it).rawPath.isNotBlank() }
    }
}
