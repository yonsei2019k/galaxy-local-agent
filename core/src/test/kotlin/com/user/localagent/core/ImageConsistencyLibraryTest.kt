package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class ImageConsistencyLibraryTest {
    @Test fun fullLibraryNeverEvictsAndExistingTitleCanStillBeUpdated(){
        val full=(1..12).fold(ImageConsistencyLibrary()){library,i->library.save(ImageConsistencyRecipe(title="Series $i"))}
        assertThrows(IllegalArgumentException::class.java){full.save(ImageConsistencyRecipe(title="New series"))}
        assertEquals(12,full.recipes.size)
        val updated=full.save(ImageConsistencyRecipe(title=" Series 1 ",seed=91))
        assertEquals(12,updated.recipes.size);assertEquals(91,updated.recipes.first().seed.toInt())
        assertTrue(updated.recipes.any{it.title=="Series 12"})
    }
    @Test fun removalOnlyChangesTheNamedRecipe(){
        val a=ImageConsistencyRecipe(title="A",preserve="red mug")
        val b=ImageConsistencyRecipe(title="B",preserve="blue bottle")
        assertEquals(listOf(b),ImageConsistencyLibrary().save(a).save(b).remove(a).recipes)
    }
    @Test fun staleDeletionCannotRemoveNewerOwnerChanges(){
        val old=ImageConsistencyRecipe(title="A",seed=42)
        val current=ImageConsistencyLibrary().save(old).save(old.copy(seed=81))
        assertThrows(IllegalStateException::class.java){current.remove(old)}
        assertEquals(81,current.recipes.single().seed.toInt())
    }
    @Test fun unknownSchemaAndAmbiguousNamesFailClosed(){
        assertThrows(IllegalArgumentException::class.java){ImageConsistencyLibrary(schemaVersion=3).validate()}
        assertThrows(IllegalArgumentException::class.java){ImageConsistencyLibrary(recipes=listOf(ImageConsistencyRecipe(title="A"),ImageConsistencyRecipe(title=" A "))).validate()}
    }
}
