package com.user.localagent.ui

import org.junit.Assert.*
import org.junit.Test

class CharacterPickerEntryTest {
    private fun wire(action:String,payload:String)="""{"version":1,"threadId":"thread","branchId":"branch","action":"$action","payload":$payload}"""

    @Test fun assistantRowRunsTheCharacterPickerAction(){
        var opened=0
        val actions=AssistantShortcutActions { opened++ }
        actions.run(AssistantShortcut.CHANGE_CHARACTER)
        assertEquals(1,opened)
        assertEquals("집순이 모습 바꾸기",AssistantShortcut.CHANGE_CHARACTER.label)
    }

    @Test fun flagIsBoundedAndClearsOnlyForItsOwnEpoch(){
        assertEquals(1L,CharacterPickerFlag.next(0))
        assertEquals(8L,CharacterPickerFlag.next(7))
        assertEquals(1L,CharacterPickerFlag.next(CharacterPickerFlag.MAX_EPOCH))
        assertNull(CharacterPickerFlag.cleared(3,3))
        assertEquals(4L,CharacterPickerFlag.cleared(4,3))
        assertNull(CharacterPickerFlag.cleared(null,3))
    }

    @Test fun bridgeAcceptsOnlyANumericAcknowledgement(){
        val ack=ReactBridgePolicy.parse(wire("characterPickerShown","""{"epoch":3}"""))
        assertNotNull(ack);assertEquals("characterPickerShown",ack!!.action)
        assertNull(ReactBridgePolicy.parse(wire("characterPickerShown","""{"epoch":"3"}""")))
        assertNull(ReactBridgePolicy.parse(wire("characterPickerShown","""{"epoch":-1}""")))
        assertNull(ReactBridgePolicy.parse(wire("characterPickerShown","""{"epoch":3,"variant":"cat"}""")))
        assertNull(ReactBridgePolicy.parse(wire("openCharacterPicker","""{"epoch":3}""")))
    }
}
