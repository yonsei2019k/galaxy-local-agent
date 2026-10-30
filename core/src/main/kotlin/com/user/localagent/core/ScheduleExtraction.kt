package com.user.localagent.core

import kotlinx.serialization.Serializable
import java.time.*
import java.time.temporal.ChronoUnit

@Serializable data class ScheduleCandidate(val id:String,val sourceId:String,val sourceTimestampMillis:Long,
    val bodySha256:String,val title:String,val startIso:String?,val endIso:String?,val needsReview:Boolean,
    val reviewReason:String?,val excerpt:String)

data class ExtractedSchedule(val title:String,val startIso:String?,val endIso:String?,val needsReview:Boolean,val reviewReason:String?)

/** Conservative local extraction: SMS content is quoted data, never instructions or calendar authority. */
object ScheduleExtraction {
    val zone:ZoneId=ZoneId.of("Asia/Seoul")
    private val keywords=Regex("예약|약속|회의|미팅|방문|진료|검진|수업|공연|출발|면접|상담|접종|검사")
    private val cancelled=Regex("(?:^|\\n|\\])\\s*(?:예약|약속|회의)\\s*취소(?:\\s|$|\\])|(?:예약|약속|회의)(?:이|가)?\\s*취소(?:\\s*(?:완료|안내|확정|처리)|되었습니다|됐습니다)|취소(?:되었|됐|되었습니다|완료|처리되)")
    private val fullDate=Regex("(?<![0-9])([0-9]{4})\\s*(?:[-./]|년)\\s*([0-9]{1,2})\\s*(?:[-./]|월)\\s*([0-9]{1,2})(?:일)?(?![0-9])")
    private val monthDate=Regex("(?<![0-9])([0-9]{1,2})\\s*(?:월\\s*|[./])([0-9]{1,2})(?:일)?(?![0-9])")
    private val relativeDate=Regex("오늘|내일|모레")
    private val clock=Regex("(?:(오전|오후|AM|PM|am|pm)\\s*)?([0-9]{1,2})\\s*(:|시)\\s*(?:([0-9]{1,2})\\s*분?)?(반)?")
    private data class DateMatch(val range:IntRange,val date:LocalDate?,val assumedYear:Boolean=false)

    fun extract(text:String,sourceTimestampMillis:Long):List<ExtractedSchedule> {
        if(text.length>200_000||!keywords.containsMatchIn(text))return emptyList()
        if(cancelled.containsMatchIn(text))return emptyList()
        val anchor=Instant.ofEpochMilli(sourceTimestampMillis).atZone(zone).toLocalDate()
        val dates=mutableListOf<DateMatch>()
        fullDate.findAll(text).take(8).forEach {m->dates+=DateMatch(m.range,date(m.groupValues[1].toInt(),m.groupValues[2].toInt(),m.groupValues[3].toInt()))}
        monthDate.findAll(text).take(12).filter {m->dates.none {it.range.first<=m.range.first&&it.range.last>=m.range.last}}.forEach {m->
            val options=(-1..1).mapNotNull {offset->date(anchor.year+offset,m.groupValues[1].toInt(),m.groupValues[2].toInt())}
            val best=options.minByOrNull {kotlin.math.abs(ChronoUnit.DAYS.between(anchor,it))}
            dates+=DateMatch(m.range,best,true)
        }
        relativeDate.findAll(text).take(8).forEach {m->dates+=DateMatch(m.range,anchor.plusDays(when(m.value){"내일"->1L;"모레"->2L;else->0L}))}
        val clocks=clock.findAll(text).take(12).toList()
        if(dates.isEmpty()&&clocks.isEmpty())return emptyList()
        val title=title(text)
        val selected=dates.sortedBy {it.range.first}.firstOrNull()
        val date=selected?.date
        val timeMatch=if(selected==null)clocks.firstOrNull()else clocks.filter {it.range.first>=selected.range.last&&it.range.first-selected.range.last<=160}.firstOrNull()
            ?:clocks.minByOrNull {kotlin.math.abs(it.range.first-selected.range.last)}?.takeIf {kotlin.math.abs(it.range.first-selected.range.last)<=160}
        val reasons=mutableListOf<String>()
        if(date==null)reasons+=if(selected==null)"문자에 날짜가 없어 확인이 필요합니다." else "유효한 날짜인지 확인이 필요합니다."
        var time:LocalTime?=null
        if(timeMatch==null)reasons+="문자에 시간이 없어 확인이 필요합니다." else {
            val h=timeMatch.groupValues[2].toInt();val marker=timeMatch.groupValues[1].lowercase();val minute=timeMatch.groupValues[4].takeIf {it.isNotEmpty()}?.toInt()?:if(timeMatch.groupValues[5].isNotEmpty())30 else 0
            if(timeMatch.groupValues[3]==":"&&(timeMatch.groupValues[4].length!=2||text.getOrNull(timeMatch.range.last+1)?.isDigit()==true))reasons+="콜론 뒤의 분은 두 자리로 확인해야 합니다."
            else if(marker.isEmpty()&&timeMatch.groupValues[3]=="시"&&h in 1..12)reasons+="오전·오후가 명시되지 않아 확인이 필요합니다."
            else {
                val hour=when(marker){"오전","am"->if(h==12)0 else h;"오후","pm"->if(h==12)12 else h+12;else->h}
                if((marker.isNotEmpty()&&h !in 1..12)||hour !in 0..23||minute !in 0..59)reasons+="유효한 시간인지 확인이 필요합니다."
                else time=LocalTime.of(hour,minute)
            }
        }
        if(dates.mapNotNull {it.date}.distinct().size>1)reasons+="날짜가 여러 개 있어 일정 날짜를 확인해주세요."
        if(clocks.map {it.value.trim()}.distinct().size>1)reasons+="시간이 여러 개 있어 일정 시간을 확인해주세요."
        if(selected?.assumedYear==true&&date!=null&&kotlin.math.abs(ChronoUnit.DAYS.between(anchor,date))>180)reasons+="문자 수신일과 멀어 연도를 확인해주세요."
        if(text.contains("(광고)")||text.contains("[광고]"))reasons+="광고에 포함된 일정인지 확인해주세요."
        val start=if(date!=null&&time!=null)date.atTime(time).atZone(zone).toOffsetDateTime()else null
        return listOf(ExtractedSchedule(title,start?.toString(),start?.plusHours(1)?.toString(),reasons.isNotEmpty(),reasons.takeIf {it.isNotEmpty()}?.joinToString(" ")))
    }
    private fun date(year:Int,month:Int,day:Int):LocalDate?=try{LocalDate.of(year,month,day)}catch(_:DateTimeException){null}
    private fun title(text:String):String {
        val organization=Regex("\\[([^]\\r\\n]{1,40})]").findAll(text).map {it.groupValues[1]}.firstOrNull {it !in setOf("Web발신","웹발신","국외발신","광고")}
        val kind=keywords.find(text)?.value?:"일정"
        return listOfNotNull(organization,if(kind=="예약")"예약 일정" else kind).joinToString(" · ").take(120)
    }
}
