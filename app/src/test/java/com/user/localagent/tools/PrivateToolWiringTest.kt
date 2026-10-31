package com.user.localagent.tools

import com.user.localagent.audio.ListedRecording
import com.user.localagent.core.*
import com.user.localagent.imports.PhoneAudioListing
import com.user.localagent.imports.PhoneAudioRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Compile-only API guard for existing AppContainer callers that use createImage as a trailing lambda. */
internal fun legacyTrailingImageExecutor(context:android.content.Context,repository:com.user.localagent.data.LocalRepository,
    scheduler:com.user.localagent.scheduling.ReminderScheduler,imports:com.user.localagent.imports.ConversationImport,
    phone:com.user.localagent.imports.PhoneRecords,workspace:com.user.localagent.workspace.ProjectWorkspace)=
    ToolExecutor(context,repository,scheduler,imports,phone,projectWorkspaceProvider={workspace}){_:ToolCall->"synthetic image result"}

class PrivateToolWiringTest {
    private val id="00000000-0000-0000-0000-000000000001"
    private fun call(name:String)=ToolCall("synthetic-call",name,if(name=="transcribe_recording")buildJsonObject {put("recordingId",id)}else buildJsonObject {put("question","합성 원문 질문")})
    @Test fun realDispatcherPreservesPartialAndFailedHostEvidenceWithoutUpgradingIt()=runBlocking {
        val call=call("analyze_sms");val partial=ToolResult(call.id,true,"{\"partial\":true}",VerificationState.UNVERIFIED,"부분 처리")
        val observed=PrivateToolDispatch.execute(call,{received->assertSame(call,received);partial},{error("wrong host")})
        assertSame(partial,observed)
        val recording=call("transcribe_recording");val failed=ToolResult(recording.id,false,"{\"status\":\"asr_model_unavailable\"}",VerificationState.FAILED,"미실행")
        assertSame(failed,PrivateToolDispatch.execute(recording,{error("wrong host")},{failed}))
    }
    @Test fun missingHostsWrongReceiptIdAndCancellationFailWithoutAFixedSuccess()=runBlocking {
        assertTrue(runCatching {PrivateToolDispatch.execute(call("analyze_sms"),null,null)}.isFailure)
        assertTrue(runCatching {PrivateToolDispatch.execute(call("analyze_sms"),{ToolResult("other",true,"fake",VerificationState.VERIFIED)},null)}.isFailure)
        assertNull(PrivateToolDispatch.execute(ToolCall("x","get_current_datetime",buildJsonObject {}),null,null))
        try {PrivateToolDispatch.execute(call("transcribe_recording"),null,{throw CancellationException("synthetic stop")});fail("cancel swallowed")}
        catch(_:CancellationException){ }
    }
    @Test fun actualRecordingProjectionRegistersProviderRowsAndNeverReturnsTheirUris() {
        val uri="content://media/external/audio/media/123"
        val value=PhoneAudioListing(9,listOf(PhoneAudioRecord("synthetic.m4a",uri,15000)),20,true,"synthetic scope")
        var registrations=0
        val result=recordingListingJson(value){items->
            registrations++;assertEquals(uri,items.single().contentUri)
            listOf(ListedRecording(id,items.single().displayName,items.single().durationMillis))
        }
        assertEquals(1,registrations);assertFalse(result.toString().contains(uri))
        assertTrue(result.getValue("requestBoundSelection").jsonPrimitive.boolean)
        assertEquals(id,result.getValue("recordings").jsonArray.single().jsonObject.getValue("recordingId").jsonPrimitive.content)
        assertFalse(result.getValue("transcriptionPerformed").jsonPrimitive.boolean)
    }
    @Test fun recordingProjectionWithoutRequestRegistrarIsMetadataOnlyAndCannotInventIds() {
        val value=PhoneAudioListing(1,listOf(PhoneAudioRecord("synthetic.m4a","content://media/external/audio/media/123",null)),20,false,"synthetic scope")
        val result=recordingListingJson(value,null)
        assertFalse(result.getValue("requestBoundSelection").jsonPrimitive.boolean)
        assertEquals(0,result.getValue("recordingIdCount").jsonPrimitive.int)
        assertFalse(result.getValue("recordings").jsonArray.single().jsonObject.containsKey("recordingId"))
        assertFalse(result.toString().contains("content://"))
    }
    @Test fun mismatchedOrDuplicateRegisteredSelectionsCannotBePublished() {
        val value=PhoneAudioListing(2,listOf(PhoneAudioRecord("first","content://media/external/audio/media/1",1),PhoneAudioRecord("second","content://media/external/audio/media/2",2)),20,false,"synthetic scope")
        assertTrue(runCatching {recordingListingJson(value){listOf(ListedRecording(id,"first",1))}}.isFailure)
        assertTrue(runCatching {recordingListingJson(value){items->items.map {ListedRecording(id,it.displayName,it.durationMillis)}}}.isFailure)
        assertTrue(runCatching {recordingListingJson(value){items->items.mapIndexed {index,item->ListedRecording(if(index==0)id else "00000000-0000-0000-0000-000000000002","wrong",item.durationMillis)}}}.isFailure)
    }
}
