package com.user.localagent.core

import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Offline computation only. Inputs are data, never code, account access or calendar authority. */
object OfficeToolCatalog {
    private fun text(required:Boolean=true,max:Int=80)=Argument("string",required,max)
    val definitions=listOf(
        ToolDefinition("calculate",Risk.LOW,mapOf("expression" to text(max=512),"decimalPlaces" to Argument("integer",false)),
            "Evaluate bounded decimal arithmetic locally: ASCII numbers, + - * / and parentheses, with unary signs. No code, functions, exponents, units or percent syntax; express percentages as /100. Up to34 significant working digits, HALF_EVEN rounding; optional decimalPlaces0..18 (default8)."),
        ToolDefinition("date_calculate",Risk.LOW,mapOf("operation" to text(max=20),"date" to text(max=10),"days" to Argument("integer",false),"otherDate" to text(false,10)),
            "Compute calendar dates locally from explicit ISO yyyy-MM-dd inputs. operation add_days needs date and integer days(-36525..36525); difference needs date and otherDate and returns otherDate minus date in calendar days. No inferred dates, timezone or workweek."),
        ToolDefinition("find_free_time",Risk.LOW,mapOf("start" to text(),"end" to text(),"timeZone" to text(),"busy" to Argument("array"),"allowed" to Argument("array",false),"durationMinutes" to Argument("integer"),"bufferMinutes" to Argument("integer",false),"limit" to Argument("integer",false)),
            "Compute free windows from caller-supplied intervals only; reads no calendars. start/end and every busy/allowed item {start,end} require RFC3339 offsets. timeZone is an explicit ZoneId. Horizon<=31days; arrays<=256. busy is required (may be []). allowed omitted means full horizon; [] means no allowed time. Merge overlaps, buffer busy intervals0..1440min (default0), retain free windows>=durationMinutes1..44640; return earliest limit1..50 (default10). Does not infer weekdays, work patterns, travel buffers or account completeness.")
    )
    val names=definitions.map {it.name}.toSet()
    private val intervals=setOf("busy","allowed")
    private val intervalKeys=setOf("start","end")

    /** Hook before the legacy alarm-only array validator in ToolRegistry. */
    fun isIntervalArgument(name:String,key:String)=name=="find_free_time"&&key in intervals
    fun validateIntervals(value:JsonElement) {
        require(value is JsonArray&&value.size<=256){"시간 구간 배열은256개 이하여야 합니다."}
        value.forEach {item->
            require(item is JsonObject&&item.keys==intervalKeys){"시간 구간에는 start/end만 필요합니다."}
            intervalKeys.forEach {key->
                val text=item.getValue(key)
                require(text is JsonPrimitive&&text.isString&&text.content.length in 1..80){"시간 구간은 명시적인 RFC3339 문자열이어야 합니다."}
            }
            val start=OffsetDateTime.parse(item.getValue("start").jsonPrimitive.content).toInstant()
            val end=OffsetDateTime.parse(item.getValue("end").jsonPrimitive.content).toInstant()
            require(end>start){"시간 구간의 종료는 시작 이후여야 합니다."}
            require(java.time.Duration.between(start,end)<=java.time.Duration.ofDays(3660)){"개별 시간 구간이 너무 깁니다."}
        }
    }

    /** Additional strict, action-specific validation; call after generic field/type validation. */
    fun validate(call:ToolCall) {
        if(call.name !in names)return
        val definition=definitions.first {it.name==call.name}
        require(call.arguments.keys.all {it in definition.arguments}){"허용되지 않은 계산 인자입니다."}
        definition.arguments.forEach {(key,spec)->
            val value=call.arguments[key]
            require(!spec.required||value!=null){"필수 계산 인자가 없습니다: $key"}
            if(value!=null)when(spec.type){
                "string"->require(value is JsonPrimitive&&value.isString&&value.content.isNotBlank()&&value.content.length<=spec.maxLength&&'\u0000' !in value.content){"$key 문자열 형식 또는 길이 오류"}
                "integer"->require(value is JsonPrimitive&&!value.isString&&value.intOrNull!=null){"$key 정수 필요"}
                "array"->validateIntervals(value)
            }
        }
        fun string(key:String)=call.arguments.getValue(key).jsonPrimitive.content
        fun integer(key:String)=call.arguments.getValue(key).jsonPrimitive.int
        when(call.name){
            "calculate"->{
                call.arguments["decimalPlaces"]?.let {require(it.jsonPrimitive.int in 0..18){"소수 자릿수는0~18이어야 합니다."}}
                OfficeToolEngine.evaluateDecimal(string("expression"))
            }
            "date_calculate"->{
                val date=LocalDate.parse(string("date"));require(date.year in 1..9999){"날짜는0001~9999년이어야 합니다."}
                when(string("operation")){
                    "add_days"->{
                        require(call.arguments.containsKey("days")&&!call.arguments.containsKey("otherDate")){"add_days에는 days만 추가로 필요합니다."}
                        require(integer("days") in -36525..36525){"날짜 이동은36525일 이하여야 합니다."}
                        require(date.plusDays(integer("days").toLong()).year in 1..9999){"결과 날짜가 지원 범위를 벗어납니다."}
                    }
                    "difference"->{
                        require(call.arguments.containsKey("otherDate")&&!call.arguments.containsKey("days")){"difference에는 otherDate만 추가로 필요합니다."}
                        val other=LocalDate.parse(string("otherDate"));require(other.year in 1..9999){"날짜는0001~9999년이어야 합니다."}
                        require(kotlin.math.abs(ChronoUnit.DAYS.between(date,other))<=36525){"날짜 차이는36525일 이하여야 합니다."}
                    }
                    else->error("operation은 add_days 또는 difference여야 합니다.")
                }
            }
            "find_free_time"->{
                val start=OffsetDateTime.parse(string("start")).toInstant();val end=OffsetDateTime.parse(string("end")).toInstant()
                require(end>start&&java.time.Duration.between(start,end)<=java.time.Duration.ofDays(31)){"조회 기간은 시작 이후부터31일 이하여야 합니다."}
                ZoneId.of(string("timeZone"))
                require(integer("durationMinutes") in 1..44640){"최소 시간은1~44640분이어야 합니다."}
                call.arguments["bufferMinutes"]?.let {require(it.jsonPrimitive.int in 0..1440){"여유 시간은0~1440분이어야 합니다."}}
                call.arguments["limit"]?.let {require(it.jsonPrimitive.int in 1..50){"결과 개수는1~50이어야 합니다."}}
                validateIntervals(call.arguments.getValue("busy"))
                call.arguments["allowed"]?.let(::validateIntervals)
            }
        }
    }

    /** Hook to override a field in ToolRegistry.schemaJson (also consumed by ToolCallSchema). */
    fun argumentSchema(name:String,key:String):JsonObject? {
        fun integer(min:Int,max:Int)=buildJsonObject {put("type","integer");put("minimum",min);put("maximum",max)}
        return when {
            name=="calculate"&&key=="decimalPlaces"->integer(0,18)
            name=="date_calculate"&&key=="operation"->buildJsonObject {put("type","string");put("enum",JsonArray(listOf("add_days","difference").map(::JsonPrimitive)));put("maxLength",20)}
            name=="date_calculate"&&key in setOf("date","otherDate")->buildJsonObject {put("type","string");put("minLength",10);put("maxLength",10);put("pattern","^[0-9]{4}-[0-9]{2}-[0-9]{2}$")}
            name=="date_calculate"&&key=="days"->integer(-36525,36525)
            name=="find_free_time"&&key=="durationMinutes"->integer(1,44640)
            name=="find_free_time"&&key=="bufferMinutes"->integer(0,1440)
            name=="find_free_time"&&key=="limit"->integer(1,50)
            isIntervalArgument(name,key)->buildJsonObject {
                put("type","array");put("maxItems",256)
                put("items",buildJsonObject {
                    put("type","object");put("additionalProperties",false)
                    put("properties",buildJsonObject {intervalKeys.forEach {field->put(field,buildJsonObject {put("type","string");put("format","date-time");put("maxLength",80);put("minLength",1)})}})
                    put("required",JsonArray(intervalKeys.map(::JsonPrimitive)))
                })
            }
            else->null
        }
    }
}
