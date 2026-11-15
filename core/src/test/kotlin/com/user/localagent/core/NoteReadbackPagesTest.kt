package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NoteReadbackPagesTest {
    private val n=NoteSource("note-demo","scope-demo","team","제목","head😀\r\n"+"x".repeat(1600)+"needle tail",9)
    private fun page(note:NoteSource=n,offset:Int=0,limit:Int=900):Pair<ToolCall,JsonObject> {
        val c=ToolCall("read-demo","read_note",buildJsonObject{put("id",note.id);put("scope",note.collection);put("expectedUpdatedAt",note.updatedAt.toString());put("offset",offset);put("limit",limit)})
        return c to NoteReadPatchSession(NoteReadBinding("run-demo","thread-demo","branch-demo","scope-demo")).read(note,NoteReadPatchToolCatalog.readRequest(c)).json()
    }
    @Test fun partialPageNeverDischargesWholeRead(){val(c,p)=page();assertNull(NoteReadbackPages().observe(c,p).snapshot)}
    @Test fun exactAllPagesProveWholeWithoutChangingPartialFlags(){
        val a=NoteReadbackPages();val(c,p)=page();assertTrue(p.getValue("partial").jsonPrimitive.boolean);assertNull(a.observe(c,p).snapshot)
        val(c2,p2)=page(offset=p.getValue("nextOffset").jsonPrimitive.int)
        val actual=a.observe(c2,p2).snapshot
        assertEquals(NoteReadbackPages.snapshot(n.id,n.collection,n.updatedAt.toString(),p.getValue("bodySha256").jsonPrimitive.content),actual)
        assertTrue(p2.getValue("partial").jsonPrimitive.boolean)
    }
    @Test fun holesAndVersionMixNeverProveWhole(){
        val a=NoteReadbackPages();val(c,p)=page(limit=20);a.observe(c,p)
        val(c2,p2)=page(offset=900);assertNull(a.observe(c2,p2).snapshot)
        val(c3,p3)=page(n.copy(updatedAt=10),offset=20);assertNull(a.observe(c3,p3).snapshot)
    }
    @Test fun malformedLaterReadInvalidatesPreviousPages(){
        val a=NoteReadbackPages();val(c,p)=page();a.observe(c,p);a.observe(c,null)
        val(c2,p2)=page(offset=900);assertNull(a.observe(c2,p2).snapshot)
    }
    @Test fun mutationResetPreventsBeforeReadFromJoiningAfterRead(){
        val a=NoteReadbackPages();val(c,p)=page();a.observe(c,p);a.invalidate(n.id)
        val(c2,p2)=page(offset=900);assertNull(a.observe(c2,p2).snapshot)
    }
    @Test fun hostHashMismatchAndWrongArgumentIdentityFailClosed(){
        val a=NoteReadbackPages();val(c,p)=page(n.copy(body="needle"))
        assertNull(a.observe(c,JsonObject(p+ ("text" to JsonPrimitive("x".repeat(6))))).snapshot)
        assertNull(a.observe(c,JsonObject(p+ ("id" to JsonPrimitive("other-demo")))) .snapshot)
    }
}
