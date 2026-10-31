package com.user.localagent.images

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class ImageSessionRegistryTest {
    @Test fun stopTargetsOnlyTheReviewedSessionAndLastCloseKeepsOtherImageAlive() {
        val a="image:${UUID.randomUUID()}";val b="image:${UUID.randomUUID()}";var aCancelled=0;var bCancelled=0;var serviceStops=0
        val first=ImageSessionRegistry.register(a,null,MutableStateFlow(ImageStudioState()),{aCancelled++},onLastClosed={serviceStops++})
        val second=ImageSessionRegistry.register(b,null,MutableStateFlow(ImageStudioState()),{bCancelled++},onLastClosed={serviceStops++})
        try{
            val token=checkNotNull(ImageSessionRegistry.sessions.value[a]).token
            ImageSessionRegistry.cancel(a,"wrong-token");assertEquals(0,aCancelled)
            ImageSessionRegistry.cancel(a,token);assertEquals(1,aCancelled);assertEquals(0,bCancelled)
            first.close();first.close();assertEquals(0,serviceStops);assertTrue(b in ImageSessionRegistry.sessions.value)
        }finally{first.close();second.close()}
        assertEquals(1,serviceStops)
    }
    @Test fun anOldNotificationCannotCancelANewImageWithTheSameRunOwner() {
        val owner="image:${UUID.randomUUID()}";var oldCancelled=0;var nextCancelled=0
        val old=ImageSessionRegistry.register(owner,null,MutableStateFlow(ImageStudioState()),{oldCancelled++})
        val token=checkNotNull(ImageSessionRegistry.sessions.value[owner]).token;old.close()
        val next=ImageSessionRegistry.register(owner,null,MutableStateFlow(ImageStudioState()),{nextCancelled++})
        try{
            ImageSessionRegistry.cancel(owner,token);assertEquals(0,oldCancelled);assertEquals(0,nextCancelled)
            old.close();assertTrue(owner in ImageSessionRegistry.sessions.value)
        }finally{next.close()}
    }
}
