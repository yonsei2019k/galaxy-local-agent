package com.user.localagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.graphics.Color
import com.user.localagent.core.ToolCall
import com.user.localagent.workspace.*
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual binary files in unique test-owned private projects; no gallery or owner directory access. */
@RunWith(AndroidJUnit4::class)
class ProjectStorageAuditValidation {
    @Test fun exactBinaryDuplicatesAndAuditStayInsideTheSelectedProjectWithoutEditAuthority()=runBlocking(Dispatchers.IO){
        assumeTrue(InstrumentationRegistry.getArguments().getString("projectStorageValidationApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val namespace="qa-storage-${UUID.randomUUID()}";val otherNamespace="qa-storage-${UUID.randomUUID()}"
        val workspace=ProjectWorkspace(context,namespace);val other=ProjectWorkspace(context,otherNamespace)
        val root=File(context.filesDir,"projects/$namespace").canonicalFile
        val otherRoot=File(context.filesDir,"projects/$otherNamespace").canonicalFile
        try {
            val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
            val binary=try {bitmap.eraseColor(Color.CYAN);ByteArrayOutputStream().use {output->check(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));output.toByteArray()}}finally {bitmap.recycle()}
            File(root,"a.png").writeBytes(binary);File(root,"b.png").writeBytes(binary);File(root,"c.bin").writeBytes(byteArrayOf(1,2,3,4,0,-1))
            File(otherRoot,"outside.png").writeBytes(binary)
            val note="Synthetic note. Hashing this must not grant edit authority."
            workspace.write("note.txt",note)
            val audit=ProjectStorageAudit(workspace::list,workspace::hashForAudit)
            val metadata=Json.parseToJsonElement(audit.execute(ToolCall("qa-audit","inspect_project_storage",buildJsonObject {}))).jsonObject
            assertEquals(4,metadata.getValue("observedFiles").jsonPrimitive.int);assertFalse(metadata.getValue("partial").jsonPrimitive.boolean)
            val duplicate=Json.parseToJsonElement(audit.execute(ToolCall("qa-duplicates","find_project_duplicates",buildJsonObject {}))).jsonObject
            val groups=duplicate.getValue("duplicateGroups").jsonArray
            assertEquals(1,groups.size);assertEquals(listOf("a.png","b.png"),groups.single().jsonObject.getValue("paths").jsonArray.map {it.jsonPrimitive.content})
            assertTrue(duplicate.getValue("doesNotAuthorizeDeletion").jsonPrimitive.boolean)
            assertFalse(duplicate.toString().contains(otherNamespace));assertFalse(duplicate.toString().contains(root.absolutePath))
            val digest=workspace.hashForAudit("note.txt",1024)
            assertTrue(runCatching {workspace.writeForTool("note.txt","unobserved replacement",digest.sha256,"qa-audit-reader")}.isFailure)
            assertEquals(note,workspace.read("note.txt"))
            assertTrue(runCatching {workspace.hashForAudit("../$otherNamespace/outside.png",1024)}.isFailure)
            assertTrue(runCatching {workspace.hashForAudit("a.png",2)}.isFailure)
            assertArrayEquals(binary,File(root,"a.png").readBytes());assertArrayEquals(binary,File(root,"b.png").readBytes());assertArrayEquals(binary,File(otherRoot,"outside.png").readBytes())
        }finally {
            val parent=File(context.filesDir,"projects").canonicalFile
            check(root.parentFile==parent&&root.name==namespace&&otherRoot.parentFile==parent&&otherRoot.name==otherNamespace)
            workspace.discard();other.discard()
        }
    }
}
