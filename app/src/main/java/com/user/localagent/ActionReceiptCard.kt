package com.user.localagent

import com.user.localagent.core.ComposedReport
import com.user.localagent.core.ReceiptStatus
import kotlinx.serialization.json.*

/**
 * Action receipts stored on the run for the chat card. The host keeps the exact values the tool used or
 * re-read; the UI shows them as labeled fields and the status words, never as a host sentence.
 */
internal object ActionReceiptCard {
    const val KEY="hostReceiptV1"
    private const val MAX_ITEMS=24
    private const val MAX_FIELDS=12
    private const val MAX_VALUE=4000
    private val statuses=ReceiptStatus.entries.map {it.id}.toSet()

    /** Nothing is stored when there is no receipt and the run is not missing proof. */
    fun write(context:JsonObject,report:ComposedReport):JsonObject {
        if(report.receipts.isEmpty()&&report.complete)return JsonObject(context-KEY)
        return JsonObject(context+(KEY to report.receiptJson()))
    }

    /** Validated card JSON with a plain title on every item, or null. */
    fun read(context:JsonObject):JsonObject? {
        val value=context[KEY] as? JsonObject?:return null
        val complete=(value["complete"] as? JsonPrimitive)?.takeUnless {it.isString}?.booleanOrNull?:return null
        val items=(value["items"] as? JsonArray)?.take(MAX_ITEMS)?.mapNotNull {item(it as? JsonObject)}?:return null
        if(items.isEmpty()&&complete)return null
        return buildJsonObject {
            put("complete",complete)
            put("items",JsonArray(items))
        }
    }

    private fun item(value:JsonObject?):JsonObject? {
        value?:return null
        fun text(key:String)=(value[key] as? JsonPrimitive)?.takeIf {it.isString}?.content
        val tool=text("tool")?:return null
        val status=text("status")?.takeIf {it in statuses}?:return null
        val title=text("title")?.takeIf {it.isNotBlank()&&it.length<=60&&it.none(Char::isISOControl)}?:ToolReportLabels.label(tool)
        val fields=(value["fields"] as? JsonArray)?.take(MAX_FIELDS)?.mapNotNull {field->
            val entry=field as? JsonObject?:return@mapNotNull null
            val label=(entry["label"] as? JsonPrimitive)?.takeIf {it.isString}?.content
                ?.takeIf {it.isNotBlank()&&it.length<=40&&it.none(Char::isISOControl)}?:return@mapNotNull null
            val text=(entry["value"] as? JsonPrimitive)?.takeIf {it.isString}?.content?:return@mapNotNull null
            buildJsonObject {put("label",label);put("value",text.replace("\u0000","").take(MAX_VALUE))}
        }.orEmpty()
        val link=text("link")?.takeIf {it.startsWith("https://")&&it.length<=2048&&it.none(Char::isWhitespace)}
        return buildJsonObject {
            put("title",title);put("status",status);put("verified",status==ReceiptStatus.VERIFIED.id)
            put("fields",JsonArray(fields))
            link?.let {put("link",it)}
        }
    }
}
