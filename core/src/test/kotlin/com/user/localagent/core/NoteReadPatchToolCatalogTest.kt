package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NoteReadPatchToolCatalogTest {
    private fun call(tool:String="read_note",extra:JsonObject=buildJsonObject{})=ToolCall("demo",tool,buildJsonObject {
        put("id","note-demo");put("scope","team");put("expectedUpdatedAt","7");extra.forEach{(k,v)->put(k,v)}
    })
    private fun rejected(block:()->Unit){try{block();fail("Must reject invalid call")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}
    @Test fun readDefaultsAndExactVersionAreTyped(){
        val read=NoteReadPatchToolCatalog.readRequest(call());assertEquals(0,read.offset);assertEquals(900,read.limit);assertEquals(7L,read.expectedUpdatedAt)
    }
    @Test fun emptyReplacementAndLiteralWhitespaceAreSupported(){
        val patch=NoteReadPatchToolCatalog.patchRequest(call("patch_note",buildJsonObject{put("oldText"," ");put("newText","")}))
        assertEquals(" ",patch.oldText);assertEquals("",patch.newText)
    }
    @Test fun unknownMissingMalformedAndOversizedInputsAreRejected(){
        rejected{NoteReadPatchToolCatalog.validate(call("other"))}
        rejected{NoteReadPatchToolCatalog.validate(call(extra=buildJsonObject{put("path","/outside")}))}
        rejected{NoteReadPatchToolCatalog.validate(call(extra=buildJsonObject{put("offset","0")}))}
        rejected{NoteReadPatchToolCatalog.validate(call(extra=buildJsonObject{put("limit",901)}))}
        rejected{NoteReadPatchToolCatalog.validate(call(extra=buildJsonObject{put("expectedUpdatedAt","9223372036854775808")}))}
        rejected{NoteReadPatchToolCatalog.validate(call("patch_note",buildJsonObject{put("oldText","");put("newText","x")}))}
        rejected{NoteReadPatchToolCatalog.validate(call("patch_note",buildJsonObject{put("oldText","x");put("newText","x".repeat(2001))}))}
        rejected{NoteReadPatchToolCatalog.validate(call(extra=buildJsonObject{put("scope",InternalNoteCollections.protected.first())}))}
        rejected{NoteReadPatchToolCatalog.validate(ToolCall("demo","read_note",buildJsonObject{put("id","note-demo")}))}
    }
}
