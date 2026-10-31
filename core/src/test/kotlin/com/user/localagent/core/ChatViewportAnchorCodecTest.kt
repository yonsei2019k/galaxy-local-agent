package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class ChatViewportAnchorCodecTest {
    @Test fun legacyIdsAndLongMessageBlocksRoundTripWithoutChangingIdentity(){
        val id="71b556ab-85ea-4c9c-b25a-f66c35014f76"
        assertEquals(ChatViewportAnchor(id,0),ChatViewportAnchorCodec.decode(id))
        for(block in listOf(0,1,19,1000000))assertEquals(ChatViewportAnchor(id,block),ChatViewportAnchorCodec.decode(ChatViewportAnchorCodec.encode(id,block)))
    }
    @Test fun malformedOrExtendedAnchorsNeverBecomeMessageIds(){
        for(value in listOf(""," ","../other", "https://example.com", "id\n", "x".repeat(257),
            "{\"v\":2,\"messageId\":\"id\",\"blockIndex\":0}",
            "{\"v\":1,\"messageId\":\"id\",\"blockIndex\":-1}",
            "{\"v\":1,\"messageId\":\"id\",\"blockIndex\":1.5}",
            "{\"v\":1,\"messageId\":\"id\",\"blockIndex\":\"2\"}",
            "{\"v\":\"1\",\"messageId\":\"id\",\"blockIndex\":0}",
            "{\"v\":1,\"messageId\":\"id\",\"blockIndex\":0,\"tool\":\"write\"}",
            "{\"v\":1,\"messageId\":\"id\"}",
            "{\"v\":1,\"messageId\":\"id\\u0000\",\"blockIndex\":0}")){
            assertTrue(value,runCatching{ChatViewportAnchorCodec.decode(value)}.isFailure)
        }
        assertTrue(runCatching{ChatViewportAnchorCodec.encode("id",-1)}.isFailure)
        assertTrue(runCatching{ChatViewportAnchorCodec.encode("id",1000001)}.isFailure)
    }
}
