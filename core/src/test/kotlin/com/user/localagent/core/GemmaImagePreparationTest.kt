package com.user.localagent.core
import org.junit.Assert.*
import org.junit.Test
class GemmaImagePreparationTest {
    @Test fun bothPinnedModelsAreSelectable(){GemmaImagePreparation.hashes.forEach {(id,hash)->assertEquals(id,GemmaImagePreparation.modelId("$id-${hash.take(12)}.gguf"))}}
    @Test fun unknownAndMismatchedArtifactsAreRejected(){
        assertThrows(IllegalArgumentException::class.java){GemmaImagePreparation.modelId("gemma-e4b-aaaaaaaaaaaa.gguf")}
        assertThrows(IllegalStateException::class.java){GemmaImagePreparation.modelId("qwen3.5-9b.gguf")}
    }
}
