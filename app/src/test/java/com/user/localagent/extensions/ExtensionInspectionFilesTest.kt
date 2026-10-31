package com.user.localagent.extensions

import com.user.localagent.core.DeclarativeExtensionManifest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtensionInspectionFilesTest {
    private val commit="a".repeat(40)
    private fun bundle():ByteArray=ByteArrayOutputStream().also{bytes->ZipOutputStream(bytes).use{zip->zip.putNextEntry(ZipEntry("SKILL.md"));zip.write("# Synthetic declarative data".toByteArray());zip.closeEntry()}}.toByteArray()
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun manifest(bytes:ByteArray)=Json.encodeToString(DeclarativeExtensionManifest(1,"synthetic.test","1.0.0","Synthetic inspector fixture","https://github.com/example/skills",commit,hash(bytes),listOf("draft_document"))).toByteArray()
    @Test fun independentTrustedAnchorsAreRequiredBeforeEitherFileIsOpened()=runBlocking {
        var opens=0
        try{ExtensionInspectionFiles.inspect({opens++;error("Must not open")},{opens++;error("Must not open")},"",commit);fail()}catch(_:IllegalArgumentException){}
        try{ExtensionInspectionFiles.inspect({opens++;error("Must not open")},{opens++;error("Must not open")},"a".repeat(64),"main");fail()}catch(_:IllegalArgumentException){}
        assertEquals(0,opens)
    }
    @Test fun boundedSelectedFilesUseRealCoreInspectionWithoutInstallingInstructions()=runBlocking {
        val bytes=bundle();var closed=0
        fun stream(data:ByteArray)=object:ByteArrayInputStream(data){override fun close(){closed++;super.close()}}
        val result=ExtensionInspectionFiles.inspect({stream(bytes)},{stream(manifest(bytes))},hash(bytes),commit)
        assertEquals(2,closed);assertEquals("synthetic.test",result.manifest.id);assertEquals(setOf("SKILL.md"),result.declarativeFiles.keys)
        assertTrue(result.approvalRequired);assertFalse(result.executableInstructionsTrusted)
    }
    @Test fun oversizedBundleOrManifestNeverReturnsAPartialInspection()=runBlocking {
        val bytes=bundle();var closed=false
        val huge=object:ByteArrayInputStream(ByteArray(5*1024*1024+1)){override fun close(){closed=true;super.close()}}
        try{ExtensionInspectionFiles.inspect({huge},{error("Manifest must not open")},"a".repeat(64),commit);fail()}catch(_:IllegalArgumentException){}
        assertTrue(closed)
        try{ExtensionInspectionFiles.inspect({bytes.inputStream()},{ByteArray(16*1024+1).inputStream()},hash(bytes),commit);fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun interruptedReadClosesTheStreamAndCannotProduceASuccessReport()=runBlocking {
        var closed=false
        val input=object:InputStream(){override fun read():Int=throw IOException("Synthetic read failure");override fun close(){closed=true}}
        try{ExtensionInspectionFiles.inspect({input},{error("Must not open")},"a".repeat(64),commit);fail()}catch(_:IOException){}
        assertTrue(closed)
    }
    @Test fun malformedUtf8IsRejectedInsteadOfReplacingManifestBytes()=runBlocking {
        val bytes=bundle()
        try{ExtensionInspectionFiles.inspect({bytes.inputStream()},{byteArrayOf(0xc3.toByte(),0x28).inputStream()},hash(bytes),commit);fail()}catch(_:java.nio.charset.CharacterCodingException){}
    }
}
