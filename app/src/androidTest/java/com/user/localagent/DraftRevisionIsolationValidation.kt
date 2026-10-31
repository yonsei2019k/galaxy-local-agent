package com.user.localagent

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.data.DraftStore
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DraftRevisionIsolationValidation {
    @Test fun equalSyntheticScopeInSeparateRootsKeepsBothEncryptedWrites() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(target.cacheDir,"draft-revision-isolation-${UUID.randomUUID()}").canonicalFile
        assertEquals(target.cacheDir.canonicalFile,root.parentFile);assertTrue(root.mkdir())
        val directories=listOf(File(root,"a"),File(root,"b"))
        try{
            val contexts=directories.map{directory->assertTrue(directory.mkdir());object:ContextWrapper(target){
                override fun getFilesDir()=directory
                override fun getApplicationContext():Context=this
            }}
            val scope="public-revision-test-${UUID.randomUUID()}"
            val first=DraftStore(contexts[0],scope);val second=DraftStore(contexts[1],scope)
            val revisionA=first.reserveWrite();val revisionB=second.reserveWrite()
            second.write("PUBLIC_SYNTHETIC_B",revisionB);first.write("PUBLIC_SYNTHETIC_A",revisionA)
            assertEquals("PUBLIC_SYNTHETIC_A",first.readStrict());assertEquals("PUBLIC_SYNTHETIC_B",second.readStrict())
        }finally{
            directories.forEach{directory->
                assertEquals(root,directory.canonicalFile.parentFile)
                directory.listFiles().orEmpty().forEach{file->
                    assertEquals(directory.canonicalFile,file.canonicalFile.parentFile)
                    assertTrue(file.isFile&&file.name.startsWith("chat-draft-"));assertTrue(file.delete())
                }
                if(directory.exists())assertTrue(directory.delete())
            }
            assertTrue(root.delete())
        }
    }
}
