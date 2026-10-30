package com.user.localagent.images

import android.content.Context
import android.content.ContextWrapper
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic inert weights only. Never reads, downloads or deletes a user's installed models. */
@RunWith(AndroidJUnit4::class)
class ImageModelValidation {
    private val target=InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun trustedImportColdVerificationCorruptionAndDeletion()=runBlocking {
        fixtures {context,source->
            source.writeBytes(safetensors(VALID_HEADER))
            val manager=ImageModelManager(context)
            val model=manager.importModel(uri(source),sha(source))
            assertTrue(manager.state.value.available)
            assertEquals(File(context.filesDir,"image-models").canonicalFile,model.canonicalFile.parentFile)
            assertEquals(model.canonicalFile,manager.verifiedModelFile()?.canonicalFile)
            val restarted=ImageModelManager(context)
            restarted.refresh()
            assertTrue(restarted.state.value.available)
            val stamp=model.lastModified()
            val damaged=model.readBytes().also {it[it.lastIndex]=(it.last().toInt() xor 1).toByte()}
            model.writeBytes(damaged)
            check(model.setLastModified(stamp+2000))
            restarted.refresh()
            assertFalse("Stored index flags cannot bypass a changed model hash",restarted.state.value.available)
            assertNull(restarted.verifiedModelFile())
            assertTrue("Corrupted weights are retained until explicit deletion",model.exists())
            restarted.deleteModel()
            assertFalse(model.exists())
            assertFalse(restarted.state.value.available)
        }
    }

    @Test fun missingOrWrongPublisherHashNeverReplacesActiveModel()=runBlocking {
        fixtures {context,source->
            source.writeBytes(safetensors(VALID_HEADER))
            val manager=ImageModelManager(context)
            val original=manager.importModel(uri(source),sha(source))
            expectFailure {manager.importModel(uri(source))}
            expectFailure {manager.importModel(uri(source),"0".repeat(64))}
            assertEquals(original.canonicalFile,manager.verifiedModelFile()?.canonicalFile)
            assertTrue(manager.state.value.available)
            assertFalse(File(context.filesDir,"image-models").listFiles().orEmpty().any {it.extension=="part"})
        }
    }

    @Test fun duplicatedKeysInvalidOffsetsAndExecutableContainerAreRejected()=runBlocking {
        fixtures {context,source->
            val manager=ImageModelManager(context)
            val malformed=listOf(
                safetensors("{\"weight\":$TENSOR,\"weight\":$TENSOR}"),
                safetensors("{\"weight\":{\"dtype\":\"F16\",\"shape\":[3],\"data_offsets\":[0,6]}}"),
                ByteArray(40).apply {this[0]=0x50;this[1]=0x4b;this[2]=0x03;this[3]=0x04},
                ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).put(byteArrayOf(0x47,0x47,0x55,0x46)).putInt(4).putLong(1).putLong(0).array(),
            )
            for(bytes in malformed){
                source.writeBytes(bytes)
                expectFailure {manager.importModel(uri(source),sha(source))}
                assertFalse(manager.state.value.available)
                assertNull(manager.verifiedModelFile())
                assertFalse(File(context.filesDir,"image-models").listFiles().orEmpty().any {it.extension=="part"})
            }
        }
    }

    private suspend fun expectFailure(block:suspend()->Unit){
        var rejected=false
        try {block()}catch(_:IllegalArgumentException){rejected=true}catch(_:IllegalStateException){rejected=true}
        assertTrue("Unverified or invalid weights must fail closed",rejected)
    }
    private fun uri(file:File)=FileProvider.getUriForFile(target,"${target.packageName}.files",file)
    private fun sha(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun safetensors(json:String):ByteArray {
        val raw=json.toByteArray(Charsets.UTF_8)
        val padded=raw.copyOf(((raw.size+7)/8)*8).also {for(i in raw.size until it.size)it[i]=0x20}
        return ByteBuffer.allocate(8+padded.size+4).order(ByteOrder.LITTLE_ENDIAN).putLong(padded.size.toLong()).put(padded).put(byteArrayOf(0,0x3c,0,0x3c)).array()
    }
    private suspend fun fixtures(block:suspend(Context,File)->Unit){
        val root=File(target.cacheDir,"qa-image-model-${UUID.randomUUID()}").canonicalFile
        check(root.parentFile==target.cacheDir.canonicalFile&&root.mkdir())
        val exports=File(target.filesDir,"exports").apply {check(isDirectory||mkdirs())}.canonicalFile
        val source=File(exports,"qa-image-model-${UUID.randomUUID()}.safetensors").canonicalFile
        check(source.parentFile==exports&&source.createNewFile())
        val context=object:ContextWrapper(target){
            override fun getFilesDir():File=root
            override fun getApplicationContext():Context=this
        }
        try {block(context,source)}finally{
            // Exactly this private fixture tree; the app's real image-models directory is never targeted.
            val models=File(root,"image-models")
            check(root.parentFile==target.cacheDir.canonicalFile&&root.name.startsWith("qa-image-model-"))
            models.listFiles().orEmpty().forEach {file->
                check(file.canonicalFile.parentFile==models.canonicalFile&&file.isFile)
                check(file.delete())
            }
            if(models.exists())check(models.delete())
            check(root.delete())
            check(source.canonicalFile.parentFile==exports&&source.delete())
        }
    }
    companion object {
        private const val TENSOR="{\"dtype\":\"F16\",\"shape\":[2],\"data_offsets\":[0,4]}"
        private const val VALID_HEADER="{\"weight\":$TENSOR}"
    }
}
