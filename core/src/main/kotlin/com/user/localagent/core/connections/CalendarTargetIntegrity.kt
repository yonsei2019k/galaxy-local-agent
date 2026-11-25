package com.user.localagent.core.connections

import com.user.localagent.core.BookingReadback
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolRegistry
import java.security.MessageDigest
import java.time.OffsetDateTime
import kotlinx.serialization.json.*

/** Host-selected credential snapshot and account-qualified calendar selector. Never decoded from model JSON. */
class CalendarTargetReview internal constructor(val access:ConnectionAccess,val callId:String,val argumentsSha256:String,
    val calendarSelector:String,private val ownerAccountLabel:String?,private val title:String,private val start:String,private val end:String,
    private val privateEvent:Boolean, internal val bookingEventId:String?,
    private val bookingLabel:String?) {
    fun approvalText():String=buildString {
        append("Google Calendar 일정 생성\n계정: ").append(ownerAccountLabel?:"표시 이름 미확인 · "+access.principal.take(12))
        append("\n캘린더: ").append(if(calendarSelector=="primary")"이 계정의 기본 캘린더 (primary)"else calendarSelector)
        append("\n연결 버전: ").append(access.generation).append("\n제목: ").append(JsonPrimitive(title).toString())
        append("\n시작: ").append(start).append("\n종료: ").append(end)
        if(privateEvent)append("\n공개 범위: 비공개 · 초대 없음")
        if(bookingLabel==null)append("\n생성 후 실제 일정 ID·활성 상태·시각을 재조회합니다. 참석자 수신·수락 확인은 포함하지 않습니다.")
        else {
            append("\n확인된 예약: ").append(bookingLabel)
            append("\n같은 예약의 일정이 있으면 확인만 하고, 없으면 생성한 뒤 다시 확인합니다.")
        }
    }
    override fun toString()="CalendarTargetReview(private owner preview)"
}
class ApprovedCalendarTarget internal constructor(val review:CalendarTargetReview) {
    override fun toString()="ApprovedCalendarTarget(host-only exact approval)"
}
object CalendarTargetIntegrity {
    const val CREATE="create_google_calendar_event"
    private fun sha(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}
    private fun arguments(call:ToolCall)=sha(buildJsonObject{put("tool",call.name);put("arguments",JsonObject(call.arguments.toSortedMap()))}.toString())
    fun review(scopedCall:ToolCall,actualAccess:ConnectionAccess,ownerAccountLabel:String?):CalendarTargetReview =
        review(scopedCall,actualAccess,ownerAccountLabel,null)

    /** Only the trusted host supplies evidence from the checkout and readback flow. */
    fun reviewVerifiedBooking(scopedCall:ToolCall,actualAccess:ConnectionAccess,ownerAccountLabel:String?,
        booking:BookingReadback.VerifiedBooking):CalendarTargetReview =
        review(scopedCall,actualAccess,ownerAccountLabel,booking)

    private fun review(scopedCall:ToolCall,actualAccess:ConnectionAccess,ownerAccountLabel:String?,
        booking:BookingReadback.VerifiedBooking?):CalendarTargetReview {
        ToolRegistry.validate(scopedCall);require(scopedCall.name==CREATE)
        require(actualAccess.principal.isNotBlank()&&actualAccess.principal.length<=256&&actualAccess.generation>=0&&actualAccess.readAllowed&&actualAccess.writeAllowed)
        require(ownerAccountLabel==null||ownerAccountLabel.length<=320&&ownerAccountLabel.none{it.code<32})
        val a=scopedCall.arguments
        val selector=a["calendarId"]?.jsonPrimitive?.content?:"primary";ConnectionRequests.key(selector)
        val start=a.getValue("start").jsonPrimitive.content
        val end=a.getValue("end").jsonPrimitive.content
        val privateEvent=a["private"]==JsonPrimitive(true)
        val bookingId=booking?.let {
            require(privateEvent)
            require(OffsetDateTime.parse(start).toInstant()==it.checkIn.toInstant() &&
                OffsetDateTime.parse(end).toInstant()==it.checkOut.toInstant())
            sha(buildJsonArray {
                add("ziphap-private-booking-event-v1");add(actualAccess.principal)
                add(it.bookingOrigin);add(it.merchantId);add(it.reservationId)
            }.toString())
        }
        val bookingLabel=booking?.let {"${it.bookingOrigin} · ${JsonPrimitive(it.reservationId)} · ${JsonPrimitive(it.siteId)}"}
        return CalendarTargetReview(actualAccess,scopedCall.id,arguments(scopedCall),selector,ownerAccountLabel,
            a.getValue("title").jsonPrimitive.content,start,end,privateEvent,bookingId,bookingLabel)
    }
    /** Invoke only after actual owner UI confirmation of this exact preview; no tool API exposes this factory. */
    fun ownerConfirmed(review:CalendarTargetReview,actuallyDisplayedPreview:String):ApprovedCalendarTarget {
        require(actuallyDisplayedPreview==review.approvalText());return ApprovedCalendarTarget(review)
    }
    fun matches(approved:ApprovedCalendarTarget,scopedCall:ToolCall,currentAccess:ConnectionAccess):Boolean {
        val r=approved.review
        return scopedCall.name==CREATE&&currentAccess==r.access&&scopedCall.id==r.callId&&arguments(scopedCall)==r.argumentsSha256&&
            (scopedCall.arguments["calendarId"]?.jsonPrimitive?.content?:"primary")==r.calendarSelector
    }
    fun readback(expectedId:String,title:String,start:OffsetDateTime,end:OffsetDateTime,body:String,
        privateEvent:Boolean=false):ResponseProof = readback(expectedId,title,start,end,body,privateEvent,null)

    internal const val BOOKING_PROPERTY="ziphapBookingEvent"
    internal fun readback(expectedId:String,title:String,start:OffsetDateTime,end:OffsetDateTime,body:String,
        privateEvent:Boolean,bookingEventId:String?):ResponseProof=ResponseProof(runCatching {
        val data=Json.parseToJsonElement(body).jsonObject
        fun text(key:String)=data.getValue(key).jsonPrimitive.also{require(it.isString)}.content
        text("id")==expectedId&&text("status") in setOf("confirmed","tentative")&&text("summary")==title&&
            OffsetDateTime.parse(data.getValue("start").jsonObject.getValue("dateTime").jsonPrimitive.content).toInstant()==start.toInstant()&&
            OffsetDateTime.parse(data.getValue("end").jsonObject.getValue("dateTime").jsonPrimitive.content).toInstant()==end.toInstant()&&
            (!privateEvent||(text("visibility")=="private"&&data["attendeesOmitted"]!=JsonPrimitive(true)&&
                (data["attendees"]?.jsonArray?.isEmpty()!=false))) &&
            (bookingEventId==null || data["extendedProperties"]?.jsonObject?.get("private")?.jsonObject
                ?.get(BOOKING_PROPERTY)==JsonPrimitive(bookingEventId))
    }.getOrDefault(false),when {
        bookingEventId!=null -> "예약 연결·활성 일정·시각·비공개 설정·초대 없음 재조회"
        privateEvent -> "활성 일정·비공개 설정·초대 없음 재조회"
        else -> "활성 일정 ID·제목·시작·종료 실제 재조회 · 참석자 수신·수락 미확인"
    })

    internal fun eventLink(raw:String?):String? = runCatching {
        val uri=java.net.URI(raw?.takeIf {it.length<=1500}?:return null)
        require(uri.scheme=="https"&&uri.host in setOf("calendar.google.com","www.google.com")&&
            uri.port==-1&&uri.userInfo==null&&uri.rawFragment==null&&uri.path=="/calendar/event"&&
            uri.rawQuery?.matches(Regex("eid=[A-Za-z0-9_%-]+"))==true)
        uri.toASCIIString()
    }.getOrNull()
}
