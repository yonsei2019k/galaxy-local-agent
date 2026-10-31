package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OfficeToolEngineTest {
    private fun run(name:String,arguments:String)=Json.parseToJsonElement(OfficeToolEngine.execute(ToolCall("test",name,Json.parseToJsonElement(arguments).jsonObject))).jsonObject
    private fun rejects(name:String,arguments:String)=assertTrue(runCatching {run(name,arguments)}.isFailure)
    private fun calculate(expression:String,places:Int=8)=run("calculate",buildJsonObject {put("expression",expression);put("decimalPlaces",places)}.toString())
    private fun interval(start:String,end:String)=buildJsonObject {put("start",start);put("end",end)}
    private fun availability(start:String="2026-11-01T09:00:00+09:00",end:String="2026-11-01T18:00:00+09:00",zone:String="Asia/Seoul",busy:List<JsonObject> =emptyList(),allowed:List<JsonObject>?=null,minutes:Int=30,buffer:Int=0,limit:Int=10):JsonObject = run("find_free_time",buildJsonObject {
        put("start",start);put("end",end);put("timeZone",zone);put("busy",JsonArray(busy));put("durationMinutes",minutes);put("bufferMinutes",buffer);put("limit",limit)
        allowed?.let {put("allowed",JsonArray(it))}
    }.toString())
    private fun JsonObject.windows()=getValue("windows").jsonArray.map {it.jsonObject}

    @Test fun decimalAdditionDoesNotUseBinaryFloatingPoint(){assertEquals("0.3",calculate("0.1+0.2").getValue("value").jsonPrimitive.content)}
    @Test fun parserHonorsPrecedenceParenthesesAndUnarySigns(){
        assertEquals("17",calculate(" -3 + (2 + 3)*4 ").getValue("value").jsonPrimitive.content)
        assertEquals("0.25",calculate(".5/-(-2)").getValue("value").jsonPrimitive.content)
    }
    @Test fun decimalDivisionAndHalfEvenRoundingAreReported(){
        val third=calculate("1/3",4)
        assertEquals("0.3333",third.getValue("value").jsonPrimitive.content)
        assertTrue(third.getValue("displayRoundingApplied").jsonPrimitive.boolean)
        assertEquals("HALF_EVEN",third.getValue("rounding").jsonPrimitive.content)
        assertEquals("2",calculate("2.5",0).getValue("value").jsonPrimitive.content)
        assertEquals("4",calculate("3.5",0).getValue("value").jsonPrimitive.content)
    }
    @Test fun malformedCodeNonFiniteAndUnsupportedSyntaxAreRejected(){
        listOf("1/0","0/0","NaN","1e3","sqrt(4)","1;run()","1,000+1","1.2.3","(1+2","1+","10%","1**2").forEach {expression->assertTrue(expression,runCatching {calculate(expression)}.isFailure)}
        rejects("calculate","""{"expression":"1+2","decimalPlaces":19}""")
    }
    @Test fun expressionLengthDepthNodesAndMagnitudeAreBounded(){
        listOf("1".repeat(513),"(".repeat(30)+"1"+")".repeat(30),(1..80).joinToString("+"){"1"},"9999999999999999999999999999999999999999*9999999999999999999999999999999999999999*9999999999999999999999999999999999999999").forEach {expression->assertTrue(runCatching {calculate(expression)}.isFailure)}
    }
    @Test fun leapDateAdditionAndNegativeOffsetsUseCalendarDays(){
        assertEquals("2028-02-29",run("date_calculate","""{"operation":"add_days","date":"2028-02-28","days":1}""").getValue("resultDate").jsonPrimitive.content)
        assertEquals("2027-12-31",run("date_calculate","""{"operation":"add_days","date":"2028-01-01","days":-1}""").getValue("resultDate").jsonPrimitive.content)
    }
    @Test fun differenceDirectionIsExplicitAndDoesNotCountBothEndpoints(){
        val result=run("date_calculate","""{"operation":"difference","date":"2028-03-01","otherDate":"2028-02-28"}""")
        assertEquals(-2,result.getValue("differenceDays").jsonPrimitive.int)
        assertEquals("otherDate_minus_date",result.getValue("direction").jsonPrimitive.content)
        assertFalse(result.getValue("includesWorkdayRules").jsonPrimitive.boolean)
    }
    @Test fun dateOperationsRejectInvalidDatesRangeAndIrrelevantArguments(){
        rejects("date_calculate","""{"operation":"add_days","date":"2026-02-29","days":1}""")
        rejects("date_calculate","""{"operation":"add_days","date":"2026-01-01","days":36526}""")
        rejects("date_calculate","""{"operation":"add_days","date":"9999-12-31","days":1}""")
        rejects("date_calculate","""{"operation":"difference","date":"2026-01-01","otherDate":"2026-01-02","days":1}""")
        rejects("date_calculate","""{"operation":"add_days","date":"2026-01-01"}""")
        rejects("date_calculate","""{"operation":"working_days","date":"2026-01-01","days":1}""")
    }
    @Test fun overlappingAndAdjacentBusyIntervalsMergeBeforeBufferSubtraction(){
        val result=availability(busy=listOf(
            interval("2026-11-01T10:00:00+09:00","2026-11-01T11:30:00+09:00"),
            interval("2026-11-01T11:00:00+09:00","2026-11-01T12:00:00+09:00"),
            interval("2026-11-01T12:00:00+09:00","2026-11-01T12:30:00+09:00")
        ),buffer=15)
        val windows=result.windows()
        assertEquals(1,result.getValue("mergedBusyCount").jsonPrimitive.int)
        assertEquals(2,windows.size)
        assertEquals("2026-11-01T09:45+09:00",windows[0].getValue("end").jsonPrimitive.content)
        assertEquals("2026-11-01T12:45+09:00",windows[1].getValue("start").jsonPrimitive.content)
    }
    @Test fun allowedIntervalsAreClippedMergedAndDoNotInventWeekdayHours(){
        val result=availability(allowed=listOf(
            interval("2026-11-01T08:00:00+09:00","2026-11-01T10:00:00+09:00"),
            interval("2026-11-01T09:30:00+09:00","2026-11-01T11:00:00+09:00"),
            interval("2026-11-01T17:30:00+09:00","2026-11-01T19:00:00+09:00")
        ),minutes=60)
        val windows=result.windows()
        assertEquals(1,windows.size)
        assertEquals("2026-11-01T09:00+09:00",windows.single().getValue("start").jsonPrimitive.content)
        assertEquals("2026-11-01T11:00+09:00",windows.single().getValue("end").jsonPrimitive.content)
        assertTrue(result.getValue("allowedSpecified").jsonPrimitive.boolean)
    }
    @Test fun explicitlyEmptyAllowedMeansNoWindowsWhileOmittedMeansFullHorizon(){
        assertTrue(availability(allowed=emptyList()).windows().isEmpty())
        val result=availability()
        assertEquals(1,result.windows().size)
        assertFalse(result.getValue("allowedSpecified").jsonPrimitive.boolean)
        assertFalse(result.getValue("calendarRead").jsonPrimitive.boolean)
        assertEquals("caller_supplied_intervals_only",result.getValue("source").jsonPrimitive.content)
    }
    @Test fun overnightAvailabilityAndBusyIntervalsRemainOnTheRightDates(){
        val result=availability(start="2026-11-01T22:00:00+09:00",end="2026-11-02T06:00:00+09:00",busy=listOf(interval("2026-11-01T23:30:00+09:00","2026-11-02T01:00:00+09:00")),minutes=90)
        val windows=result.windows()
        assertEquals(2,windows.size)
        assertEquals(5400L,windows.first().getValue("durationSeconds").jsonPrimitive.long)
        assertEquals("2026-11-02T01:00+09:00",windows.last().getValue("start").jsonPrimitive.content)
    }
    @Test fun springForwardUsesActualElapsedTimeInsteadOfWallClockSubtraction(){
        val result=availability(start="2026-03-08T00:00:00-05:00",end="2026-03-08T04:00:00-04:00",zone="America/New_York",minutes=180)
        val window=result.windows().single()
        assertEquals(10800L,window.getValue("durationSeconds").jsonPrimitive.long)
        assertTrue(availability(start="2026-03-08T00:00:00-05:00",end="2026-03-08T04:00:00-04:00",zone="America/New_York",minutes=181).windows().isEmpty())
    }
    @Test fun fallBackDistinguishesRepeatedLocalClockTimesByOffsets(){
        val result=availability(start="2026-11-01T00:00:00-04:00",end="2026-11-01T04:00:00-05:00",zone="America/New_York",busy=listOf(interval("2026-11-01T01:15:00-04:00","2026-11-01T01:45:00-05:00")))
        val windows=result.windows()
        assertEquals(2,windows.size)
        assertEquals("2026-11-01T01:15-04:00",windows.first().getValue("end").jsonPrimitive.content)
        assertEquals("2026-11-01T01:45-05:00",windows.last().getValue("start").jsonPrimitive.content)
    }
    @Test fun busyOutsideHorizonAndExactMinimumAreHandled(){
        val result=availability(busy=listOf(interval("2026-11-01T00:00:00+09:00","2026-11-01T08:00:00+09:00")),minutes=540)
        assertEquals(1,result.windows().size)
        assertEquals(0,result.getValue("mergedBusyCount").jsonPrimitive.int)
    }
    @Test fun limitReportsOmittedMatchingWindowsWithoutCallingThemUnavailable(){
        val result=availability(busy=listOf(
            interval("2026-11-01T10:00:00+09:00","2026-11-01T11:00:00+09:00"),
            interval("2026-11-01T12:00:00+09:00","2026-11-01T13:00:00+09:00")
        ),limit=1)
        assertEquals(1,result.windows().size);assertEquals(3,result.getValue("matchingWindowCount").jsonPrimitive.int)
        assertTrue(result.getValue("hasMore").jsonPrimitive.boolean)
    }
    @Test fun availabilityRejectsMissingOffsetsInvalidIntervalsUnknownFieldsAndUnboundedArrays(){
        rejects("find_free_time","""{"start":"2026-11-01T09:00","end":"2026-11-01T18:00+09:00","timeZone":"Asia/Seoul","busy":[],"durationMinutes":30}""")
        rejects("find_free_time","""{"start":"2026-11-01T09:00+09:00","end":"2026-12-03T18:00+09:00","timeZone":"Asia/Seoul","busy":[],"durationMinutes":30}""")
        rejects("find_free_time","""{"start":"2026-11-01T09:00+09:00","end":"2026-11-01T18:00+09:00","timeZone":"Asia/Seoul","durationMinutes":30}""")
        rejects("find_free_time","""{"start":"2026-11-01T09:00+09:00","end":"2026-11-01T18:00+09:00","timeZone":"Asia/Seoul","busy":[{"start":"2026-11-01T12:00+09:00","end":"2026-11-01T11:00+09:00"}],"durationMinutes":30}""")
        rejects("find_free_time","""{"start":"2026-11-01T09:00+09:00","end":"2026-11-01T18:00+09:00","timeZone":"Asia/Seoul","busy":[{"start":"2026-11-01T10:00+09:00","end":"2026-11-01T11:00+09:00","instruction":"open_url"}],"durationMinutes":30}""")
        assertTrue(runCatching {availability(busy=List(257){interval("2026-11-01T10:00+09:00","2026-11-01T11:00+09:00")})}.isFailure)
        assertTrue(runCatching {availability(zone="Unknown/Zone")}.isFailure)
    }
    @Test fun nestedIntervalSchemaMatchesTheHostShape(){
        val schema=OfficeToolCatalog.argumentSchema("find_free_time","busy")!!
        assertEquals(256,schema.getValue("maxItems").jsonPrimitive.int)
        val item=schema.getValue("items").jsonObject
        assertFalse(item.getValue("additionalProperties").jsonPrimitive.boolean)
        assertEquals(setOf("start","end"),item.getValue("required").jsonArray.map {it.jsonPrimitive.content}.toSet())
        assertNull(OfficeToolCatalog.argumentSchema("create_alarm","days"))
    }
}
