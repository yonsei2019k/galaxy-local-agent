package com.user.localagent.ui

import org.junit.Assert.*
import org.junit.Test

class ChatViewportTest {
    private val blocks=listOf(ViewportMessage("owner-a",1),ViewportMessage("long-answer",8),ViewportMessage("owner-b",1))
    @Test fun longAnswerMiddleBlockAndPixelOffsetRoundTrip(){
        val saved=captureViewport("thread-a","branch-a",blocks,6,73,false)
        assertEquals(UiChatViewport("thread-a","branch-a","long-answer",73,5),saved)
        assertEquals(ViewportTarget(6,73,false),restoreViewport(blocks,saved,false))
    }
    @Test fun aNewStreamBlockDoesNotMoveAnExistingReadingAnchor(){
        val saved=UiChatViewport("a","branch","long-answer",24,3)
        assertEquals(restoreViewport(blocks,saved,false),restoreViewport(listOf(blocks[0],blocks[1].copy(blocks=12),blocks[2]),saved,true))
    }
    @Test fun restoredMiddleNeverRequestsFollowBottomAndScopeMustMatch(){
        val saved=UiChatViewport("a","original","long-answer",10,4)
        assertFalse(restoreViewport(blocks,saved,false).followsBottom)
        assertTrue(viewportMatches(saved,"a","original"))
        assertFalse(viewportMatches(saved,"b","original"))
        assertFalse(viewportMatches(saved,"a","edited"))
        assertFalse(viewportMatches(null,"a","original"))
    }
    @Test fun explicitBottomAndRemovedAnchorGoToOnlyTheCurrentListEnd(){
        assertEquals(ViewportTarget(11,0,true),restoreViewport(blocks,UiChatViewport("a","b"),true))
        assertEquals(ViewportTarget(10,0,true),restoreViewport(blocks,UiChatViewport("a","b","missing",99,999),false))
        assertEquals(UiChatViewport("a","b"),captureViewport("a","b",blocks,6,73,true))
    }
    @Test fun pagingButtonDoesNotShiftMessageBlockOrPixelPersistence(){
        val saved=captureViewport("a","b",blocks,7,73,false,leadingItems=1)
        assertEquals(UiChatViewport("a","b","long-answer",73,5),saved)
        assertEquals(ViewportTarget(7,73,false),restoreViewport(blocks,saved,false,leadingItems=1))
        assertEquals(UiChatViewport("a","b","owner-a"),captureViewport("a","b",blocks,0,0,false,leadingItems=1))
    }
    @Test fun prependingOlderMessagesKeepsTheSameStableReadingAnchor(){
        val saved=UiChatViewport("a","b","long-answer",46,4)
        val prepended=listOf(ViewportMessage("older",6))+blocks
        assertEquals(ViewportTarget(12,46,false),restoreViewport(prepended,saved,false,leadingItems=1))
    }
}
