package com.user.localagent.tools

import com.user.localagent.audio.ListedRecording
import com.user.localagent.audio.RecordingListingItem
import com.user.localagent.imports.PhoneAudioListing
import kotlinx.serialization.json.*

/** Only host-registered opaque IDs become model selections. The actual URI stays in the host store. */
internal fun recordingListingJson(value:PhoneAudioListing,register:((List<RecordingListingItem>)->List<ListedRecording>)?):JsonObject {
    val items=value.items.take(20)
    val listed=register?.invoke(items.map {RecordingListingItem(it.displayName,it.contentUri,it.durationMillis)})
    if(listed!=null){
        require(listed.size==items.size&&listed.map {it.recordingId}.distinct().size==listed.size)
        listed.zip(items).forEach {(entry,actual)->
            require(entry.recordingId.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))&&entry.displayName==actual.displayName&&entry.durationMillis==actual.durationMillis){"실제 녹음 목록과 선택 식별자를 확인하지 못했습니다."}
        }
    }
    fun clip(text:String):String {var end=minOf(text.length,200);if(end>0&&end<text.length&&text[end-1].isHighSurrogate()&&text[end].isLowSurrogate())end--;return text.substring(0,end)}
    return buildJsonObject {
        put("totalCount",value.totalCount);put("limit",minOf(value.limit,20));put("truncated",value.truncated||value.items.size>items.size)
        put("scopeDescription",value.scopeDescription.take(500));put("requestBoundSelection",listed!=null);put("recordingIdCount",listed?.size?:0)
        put("contentUrisExposed",false);put("transcriptionPerformed",false);put("untrustedSourceData",true)
        put("recordings",buildJsonArray {items.forEachIndexed {index,item->add(buildJsonObject {
            put("displayName",clip(item.displayName));put("durationMillis",item.durationMillis)
            listed?.get(index)?.let {put("recordingId",it.recordingId)}
        })}})
    }
}
