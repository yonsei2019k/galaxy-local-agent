package com.user.localagent.core

import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Deterministic, bounded computation with no network, clock, account or mutation dependency. */
object OfficeToolEngine {
    private val math=MathContext(34,RoundingMode.HALF_EVEN)
    private val maxMagnitude=BigDecimal("1e100")
    private val minMagnitude=BigDecimal("1e-100")

    fun execute(call:ToolCall):String {
        require(call.name in OfficeToolCatalog.names){"지원하지 않는 오프라인 계산입니다."}
        OfficeToolCatalog.validate(call)
        return when(call.name){
            "calculate"->calculation(call.arguments)
            "date_calculate"->dateCalculation(call.arguments)
            "find_free_time"->freeTime(call.arguments)
            else->error("지원하지 않는 오프라인 계산입니다.")
        }.toString()
    }

    internal fun evaluateDecimal(expression:String):BigDecimal=DecimalParser(expression).parse()

    private class DecimalParser(private val source:String) {
        private var index=0
        private var nodes=0
        init {require(source.isNotBlank()&&source.length<=512){"수식은1~512자여야 합니다."}}
        fun parse():BigDecimal {
            val result=expression(0);space();require(index==source.length){"지원하지 않는 수식 문자 또는 형식입니다."};return result
        }
        private fun space(){while(index<source.length&&source[index] in " \t\r\n")index++}
        private fun step(depth:Int) {require(++nodes<=128&&depth<=24){"수식의 연산 수 또는 중첩 제한을 넘었습니다."}}
        private fun expression(depth:Int):BigDecimal {
            var value=term(depth)
            while(true){space();val operator=source.getOrNull(index);if(operator!='+'&&operator!='-')return value;index++;step(depth);val right=term(depth);value=bounded(if(operator=='+')value.add(right,math) else value.subtract(right,math))}
        }
        private fun term(depth:Int):BigDecimal {
            var value=unary(depth)
            while(true){
                space();val operator=source.getOrNull(index);if(operator!='*'&&operator!='/')return value
                index++;step(depth);val right=unary(depth)
                if(operator=='/')require(right.signum()!=0){"0으로 나눌 수 없습니다."}
                value=bounded(if(operator=='*')value.multiply(right,math) else value.divide(right,math))
            }
        }
        private fun unary(depth:Int):BigDecimal {
            space();val sign=source.getOrNull(index)
            if(sign=='+'||sign=='-'){index++;step(depth);val value=unary(depth+1);return if(sign=='-')value.negate() else value}
            return atom(depth)
        }
        private fun atom(depth:Int):BigDecimal {
            step(depth);space()
            if(source.getOrNull(index)=='('){index++;val value=expression(depth+1);space();require(source.getOrNull(index)==')'){"닫는 괄호가 필요합니다."};index++;return value}
            val start=index;var digits=0;var dot=false
            while(index<source.length){
                val character=source[index]
                if(character in '0'..'9'){digits++;index++}
                else if(character=='.'&&!dot){dot=true;index++}
                else break
            }
            require(digits in 1..40){"숫자는1~40자리여야 합니다."}
            return bounded(BigDecimal(source.substring(start,index),math))
        }
        private fun bounded(value:BigDecimal):BigDecimal {
            val magnitude=value.abs()
            require(magnitude<=maxMagnitude&&(value.signum()==0||magnitude>=minMagnitude)){"계산값의 크기가 지원 범위(10^-100~10^100)를 벗어났습니다."}
            return value
        }
    }

    private fun calculation(arguments:JsonObject):JsonObject {
        val expression=arguments.getValue("expression").jsonPrimitive.content
        val places=arguments["decimalPlaces"]?.jsonPrimitive?.int?:8
        val result=evaluateDecimal(expression)
        val displayed=result.setScale(places,RoundingMode.HALF_EVEN).stripTrailingZeros()
        return buildJsonObject {
            put("status","ok");put("source","local_decimal_arithmetic");put("expression",expression)
            put("value",displayed.toPlainString());put("workingValue",result.stripTrailingZeros().toPlainString())
            put("workingPrecisionDigits",34);put("rounding","HALF_EVEN");put("decimalPlaces",places)
            put("displayRoundingApplied",displayed.compareTo(result)!=0)
        }
    }

    private fun dateCalculation(arguments:JsonObject):JsonObject {
        val operation=arguments.getValue("operation").jsonPrimitive.content
        val date=LocalDate.parse(arguments.getValue("date").jsonPrimitive.content)
        return buildJsonObject {
            put("status","ok");put("source","local_calendar_date_arithmetic");put("operation",operation);put("date",date.toString())
            if(operation=="add_days"){
                val days=arguments.getValue("days").jsonPrimitive.int
                put("days",days);put("resultDate",date.plusDays(days.toLong()).toString())
            }else{
                val other=LocalDate.parse(arguments.getValue("otherDate").jsonPrimitive.content)
                put("otherDate",other.toString());put("differenceDays",ChronoUnit.DAYS.between(date,other))
                put("direction","otherDate_minus_date")
            }
            put("calendar","ISO-8601");put("includesWorkdayRules",false)
        }
    }

    private data class Interval(val start:Instant,val end:Instant)
    private fun parseIntervals(value:JsonElement):List<Interval> = value.jsonArray.map {item->
        val objectValue=item.jsonObject
        Interval(OffsetDateTime.parse(objectValue.getValue("start").jsonPrimitive.content).toInstant(),OffsetDateTime.parse(objectValue.getValue("end").jsonPrimitive.content).toInstant())
    }
    private fun merge(intervals:List<Interval>):List<Interval> {
        val result=mutableListOf<Interval>()
        intervals.sortedWith(compareBy<Interval> {it.start}.thenBy {it.end}).forEach {interval->
            val previous=result.lastOrNull()
            if(previous!=null&&interval.start<=previous.end)result[result.lastIndex]=Interval(previous.start,maxOf(previous.end,interval.end)) else result+=interval
        }
        return result
    }
    private fun clip(interval:Interval,horizon:Interval):Interval? {
        val start=maxOf(interval.start,horizon.start);val end=minOf(interval.end,horizon.end)
        return if(end>start)Interval(start,end) else null
    }

    private fun freeTime(arguments:JsonObject):JsonObject {
        fun time(key:String)=OffsetDateTime.parse(arguments.getValue(key).jsonPrimitive.content).toInstant()
        val horizon=Interval(time("start"),time("end"))
        val zone=ZoneId.of(arguments.getValue("timeZone").jsonPrimitive.content)
        val duration=Duration.ofMinutes(arguments.getValue("durationMinutes").jsonPrimitive.long)
        val buffer=Duration.ofMinutes(arguments["bufferMinutes"]?.jsonPrimitive?.long?:0L)
        val limit=arguments["limit"]?.jsonPrimitive?.int?:10
        val inputBusy=parseIntervals(arguments.getValue("busy"))
        val busy=merge(inputBusy.mapNotNull {clip(Interval(it.start.minus(buffer),it.end.plus(buffer)),horizon)})
        val allowed=merge(arguments["allowed"]?.let(::parseIntervals)?.mapNotNull {clip(it,horizon)}?:listOf(horizon))
        val free=mutableListOf<Interval>()
        allowed.forEach {window->
            var cursor=window.start
            busy.forEach {blocked->
                if(blocked.end>cursor&&blocked.start<window.end){
                    val gapEnd=minOf(blocked.start,window.end)
                    if(gapEnd>cursor&&Duration.between(cursor,gapEnd)>=duration)free+=Interval(cursor,gapEnd)
                    cursor=maxOf(cursor,minOf(blocked.end,window.end))
                }
            }
            if(window.end>cursor&&Duration.between(cursor,window.end)>=duration)free+=Interval(cursor,window.end)
        }
        return buildJsonObject {
            put("status","ok");put("source","caller_supplied_intervals_only");put("calendarRead",false)
            put("timeZone",zone.id);put("start",horizon.start.atZone(zone).toOffsetDateTime().toString());put("end",horizon.end.atZone(zone).toOffsetDateTime().toString())
            put("inputBusyCount",inputBusy.size);put("mergedBusyCount",busy.size);put("allowedSpecified",arguments.containsKey("allowed"))
            put("durationMinutes",duration.toMinutes());put("bufferMinutes",buffer.toMinutes())
            put("matchingWindowCount",free.size);put("hasMore",free.size>limit)
            put("windows",buildJsonArray {free.take(limit).forEach {interval->add(buildJsonObject {
                put("start",interval.start.atZone(zone).toOffsetDateTime().toString())
                put("end",interval.end.atZone(zone).toOffsetDateTime().toString())
                put("durationSeconds",Duration.between(interval.start,interval.end).seconds)
            })}})
        }
    }
}
