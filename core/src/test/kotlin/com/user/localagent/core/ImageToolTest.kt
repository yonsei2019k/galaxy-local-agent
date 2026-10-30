package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class ImageToolTest {
    @Test fun localImageCallAcceptsBoundedPromptAndLongSeed() {
        val call=ToolRegistry.parseAndValidate("""{"type":"tool_call","tool":"generate_image","arguments":{"prompt":"숲속의 고양이 사진","negativePrompt":"","steps":4,"seed":4294967296}}""")
        assertEquals(Risk.LOW,ToolRegistry.definitions.getValue(call.name).risk)
        assertFalse(CapabilityPolicy.requiresConfirmation(call,"builtin","hash",emptyList()))
        assertEquals(600_000,ToolRegistry.definitions.getValue(call.name).timeoutMillis)
    }
    @Test fun imageParametersCannotIntroduceExternalProgramsOrModelUrls() {
        listOf("\"url\":\"https://example.test/model\"","\"command\":\"sh run\"").forEach {extra->
            assertThrows(IllegalArgumentException::class.java){ToolRegistry.parseAndValidate("""{"name":"generate_image","arguments":{"prompt":"a cat",$extra}}""")}
        }
    }
    @Test fun invalidStepsAndSeedAreRejectedBeforeExecution() {
        listOf("\"steps\":0","\"steps\":9","\"seed\":-1","\"steps\":\"4\"").forEach {extra->
            assertThrows(IllegalArgumentException::class.java){ToolRegistry.parseAndValidate("""{"name":"generate_image","arguments":{"prompt":"a cat",$extra}}""")}
        }
    }
}
