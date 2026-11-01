package com.user.localagent

import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.core.ImageConsistencyRecipe
import com.user.localagent.images.ImageConsistencyStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Keystore persistence and reference-provider behavior in unique scopes; no inference claim. */
@RunWith(AndroidJUnit4::class)
class ImageConsistencyValidation {
    @Test fun legacySinglePresetMigratesWithoutLosingItsAnchor(){
        runFixture{context,key,store,source->
            val anchor=store.importReference(source)
            val old=ImageConsistencyRecipe(title="SYNTHETIC_LEGACY",referenceName=anchor.first,referenceSha256=anchor.second)
            com.user.localagent.data.DraftStore(context,key,16_000).write(kotlinx.serialization.json.Json.encodeToString(old))
            assertEquals(old,store.read())
            store.save(ImageConsistencyRecipe(title="SYNTHETIC_SECOND",seed=83))
            val reopened=ImageConsistencyStore(context,key)
            assertEquals(listOf("SYNTHETIC_SECOND","SYNTHETIC_LEGACY"),reopened.list().map{it.title})
            val uri=checkNotNull(reopened.reference(old))
            try{context.contentResolver.openInputStream(uri)!!.use{assertTrue(it.read()>-1)}}
            finally{context.contentResolver.delete(uri,null,null)}
        }
    }
    @Test fun deletingOneNamedPresetKeepsOtherRecipesAndReferencePhoto(){
        runFixture{context,key,store,source->
            val anchor=store.importReference(source)
            val original=ImageConsistencyRecipe(title="SYNTHETIC_FIRST",referenceName=anchor.first,referenceSha256=anchor.second)
            val remaining=original.copy(title="SYNTHETIC_SECOND",seed=84)
            store.save(original);store.save(remaining)
            store.save(original.copy(seed=92))
            assertThrows(IllegalStateException::class.java){store.remove(original)}
            store.remove(original.copy(seed=92))
            val reopened=ImageConsistencyStore(context,key)
            assertEquals(listOf(remaining),reopened.list())
            val uri=checkNotNull(reopened.reference(remaining))
            try{context.contentResolver.openInputStream(uri)!!.use{assertTrue(it.read()>-1)}}
            finally{context.contentResolver.delete(uri,null,null)}
        }
    }
    @Test fun presetSurvivesReopenAndOriginalPhotoRemovalWithoutCrossScopeRecall() {
        runFixture {context,key,store,source->
            val owned=store.importReference(source)
            val recipe=ImageConsistencyRecipe(preserve="red ceramic mug",scene="on a wooden desk",seed=73,
                referenceName=owned.first,referenceSha256=owned.second)
            store.save(recipe)
            assertEquals(1,context.contentResolver.delete(source,null,null))
            val reopened=ImageConsistencyStore(context,key)
            assertEquals(recipe,reopened.read())
            assertNull(ImageConsistencyStore(context,"qa-other-${UUID.randomUUID()}").read())
            val uri=checkNotNull(reopened.reference(recipe))
            try {
                context.contentResolver.openInputStream(uri)!!.use {assertTrue(it.read()>-1)}
                reopened.save(recipe.copy(scene="in a garden"))
                assertEquals(recipe.preserve,reopened.read()!!.preserve)
                assertEquals(73L,reopened.read()!!.seed)
            }finally{context.contentResolver.delete(uri,null,null)}
        }
    }
    @Test fun changedReferenceFailsClosedInsteadOfDroppingAnchor() {
        runFixture {context,_,store,source->
            val owned=store.importReference(source)
            val recipe=ImageConsistencyRecipe(referenceName=owned.first,referenceSha256=owned.second)
            store.save(recipe)
            val uri=checkNotNull(store.reference(recipe))
            try {
                context.contentResolver.openOutputStream(uri,"w")!!.use {it.write(byteArrayOf(1,2,3))}
                assertThrows(IllegalArgumentException::class.java){store.read()}
            }finally{context.contentResolver.delete(uri,null,null)}
        }
    }
    private fun runFixture(block:(android.content.Context,String,ImageConsistencyStore,android.net.Uri)->Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("imageConsistencyApproved")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val id=UUID.randomUUID().toString();val key="qa-image-$id"
        val store=ImageConsistencyStore(context,key)
        val file=File(context.filesDir,"shared/qa-image-$id.png").apply {parentFile!!.mkdirs()}
        val bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.RED)
            file.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
            val source=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
            block(context,key,store,source)
        }finally{bitmap.recycle();file.delete();store.clearPreset()}
    }
}
