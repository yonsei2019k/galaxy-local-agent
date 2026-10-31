package com.user.localagent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConversationComposerActionsTest {
    @Test fun plainChatHasAttachmentWithoutRequiringAnyWorkflowSelection(){
        assertEquals(listOf(ConversationComposerAction.ATTACH),conversationComposerActions(false,false,null))
    }

    @Test fun aGoalEditorCallbackAloneDoesNotAdvertiseAnActiveGoal(){
        for(label in listOf(null,""," \n\t"))assertFalse(conversationComposerActions(true,true,label).contains(ConversationComposerAction.VIEW_GOAL))
    }

    @Test fun goalMetadataWithoutAnAvailableHostCallbackHasNoAction(){
        assertEquals(listOf(ConversationComposerAction.ATTACH),conversationComposerActions(false,false,"기존 방의 목표"))
    }

    @Test fun existingGoalAndSkillsAreIndependentOptionalReferences(){
        assertEquals(listOf(ConversationComposerAction.ATTACH,ConversationComposerAction.BROWSE_SKILLS,ConversationComposerAction.VIEW_GOAL),
            conversationComposerActions(true,true,"출처를 확인하고 문서 만들기"))
        assertEquals(listOf(ConversationComposerAction.ATTACH,ConversationComposerAction.VIEW_GOAL),
            conversationComposerActions(false,true,"출처를 확인하고 문서 만들기"))
    }
}
