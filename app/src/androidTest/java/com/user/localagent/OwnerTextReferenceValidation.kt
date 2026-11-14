package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.*
import com.user.localagent.tools.ToolExecutor
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real encrypted note write/readback; only this test's uniquely titled fixture is removed. */
@RunWith(AndroidJUnit4::class)
class OwnerTextReferenceValidation {
    @Test fun exactCopyAndForeignReferenceRejection()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ownerTextValidationApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        val title="qa-owner-text-"+UUID.randomUUID()
        val payload="B2 기둥 17번\n두  칸 😀"
        val owner="메모에 '$payload' 그대로 저장"
        val quote=OwnerTextSources.quotes(owner).single()
        fun executor(text:String?)=ToolExecutor(context,app.repository,app.scheduler,app.conversationImports,app.phoneRecords,ownerRequest=text)
        val call=ToolCall("fixture","save_note",buildJsonObject{put("title",title);put("scope","__qa_owner_text__");put("contentQuoteId",quote.id)})
        try {
            assertFalse(executor(null).execute(call).success)
            assertFalse(executor("다른 요청 '변경'").execute(call).success)
            assertTrue(app.repository.searchNotes(title).none{it.title==title})
            val result=executor(owner).execute(call)
            assertTrue(result.content,result.success)
            val note=app.repository.searchNotes(title).single{it.title==title}
            assertEquals(payload,note.body)
            assertEquals(note,app.repository.getNote(note.id))
        }finally {
            app.repository.searchNotes(title).filter{it.title==title&&it.collection=="__qa_owner_text__"}.forEach{app.repository.deleteNote(it.id)}
            assertTrue(app.repository.searchNotes(title).none{it.title==title})
        }
    }
}
