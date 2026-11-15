package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json

class ImageConsistencyRecipeTest {
    @Test fun characteristicsAndChangeRemainSeparateAcrossRecall() {
        val recipe=ImageConsistencyRecipe(preserve="red mug, white logo",scene="on a wooden desk",seed=73).validate()
        assertEquals(recipe,Json.decodeFromString<ImageConsistencyRecipe>(Json.encodeToString(recipe)))
        assertTrue(recipe.prompt().contains("red mug, white logo"))
        assertTrue(recipe.prompt().endsWith("on a wooden desk"))
        assertEquals("red mug, white logo",recipe.copy(scene="in a garden").preserve)
    }
    @Test fun unknownModelAndNonfiniteStrengthAreRejected() {
        assertThrows(IllegalArgumentException::class.java){ImageConsistencyRecipe(pcModelId="arbitrary-path").validate()}
        assertThrows(IllegalArgumentException::class.java){ImageConsistencyRecipe(strength=Float.NaN).validate()}
    }
    @Test fun referenceCannotEscapeOwnedDirectoryOrMismatchHash() {
        val sha="a".repeat(64)
        ImageConsistencyRecipe(referenceName="$sha.image",referenceSha256=sha).validate()
        assertThrows(IllegalArgumentException::class.java){ImageConsistencyRecipe(referenceName="../photo.image",referenceSha256=sha).validate()}
        assertThrows(IllegalArgumentException::class.java){ImageConsistencyRecipe(referenceName="$sha.image",referenceSha256="b".repeat(64)).validate()}
        assertThrows(IllegalArgumentException::class.java){ImageConsistencyRecipe(referenceSha256=sha).validate()}
    }
    @Test fun promptAndStepBoundsStayInsideGeneratorContract() {
        assertThrows(IllegalArgumentException::class.java){ImageConsistencyRecipe(pcSteps=51).validate()}
        assertThrows(IllegalArgumentException::class.java){ImageConsistencyRecipe(seed=-1).validate()}
        assertThrows(IllegalArgumentException::class.java){ImageConsistencyRecipe(scene="x".repeat(1201)).validate()}
        assertEquals("landscape",ImageConsistencyRecipe(scene=" landscape ").validate().prompt())
        val fast=ImageConsistencyRecipe(pcModelId=PcImageModelCatalog.FAST_ID,pcSteps=4).validate()
        assertEquals(fast,Json.decodeFromString<ImageConsistencyRecipe>(Json.encodeToString(fast)).validate())
    }
}
