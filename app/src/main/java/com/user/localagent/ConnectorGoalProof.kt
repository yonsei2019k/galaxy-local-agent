package com.user.localagent

import com.user.localagent.core.*
import kotlinx.serialization.json.*
import java.time.OffsetDateTime

/** Receipts from an approved connector write and its authenticated readback. */
internal fun connectorGoalProof(call:ToolCall, result:JsonObject):GoalProofTarget? {
    if(result["verified"]!=JsonPrimitive(true))return null
    val remoteId=(result["remoteId"] as? JsonPrimitive)?.takeIf {it.isString && it.content.isNotBlank()}?:return null
    fun argument(key:String)=(call.arguments[key] as? JsonPrimitive)?.takeIf {it.isString}?.content
    return runCatching {
        val fields:List<String>
        val kind:GoalProofKind
        when(call.name){
            "send_gmail_message"->{
                require(result["service"]==JsonPrimitive("gmail") && result["sent"]==JsonPrimitive(true))
                require(result["mimeSha256"]?.jsonPrimitive?.content?.matches(Regex("[a-f0-9]{64}"))==true)
                fields=listOf(checkNotNull(argument("to")),checkNotNull(argument("subject")))
                kind=GoalProofKind.GMAIL_SENT
            }
            "create_google_calendar_event"->{
                val calendar=argument("calendarId")?:"primary"
                require(result["eventActive"]==JsonPrimitive(true) && result["calendarSelector"]==JsonPrimitive(calendar))
                require(result["accountBindingSha256"]?.jsonPrimitive?.content?.matches(Regex("[a-f0-9]{64}"))==true)
                val event=result.getValue("event").jsonObject
                require(event["id"]==remoteId && event["title"]==call.arguments["title"])
                require(event["status"]==JsonPrimitive("confirmed"))
                val start=checkNotNull(argument("start"))
                val end=checkNotNull(argument("end"))
                fun instant(value:String)=OffsetDateTime.parse(value).toInstant()
                require(instant(start)==instant(event.getValue("start").jsonPrimitive.content))
                require(instant(end)==instant(event.getValue("end").jsonPrimitive.content))
                fields=listOf(calendar,checkNotNull(argument("title")),start,end)
                kind=GoalProofKind.CALENDAR_EVENT
            }
            else->return null
        }
        GoalProofTarget(kind,JsonArray(fields.map(::JsonPrimitive)).toString()).validate()
    }.getOrNull()
}
