package com.user.localagent.integrations

import org.junit.Assert.assertEquals
import org.junit.Test

class OwnerLoginCardActionTest {
    @Test fun aPendingPairedLoginRechecksWithoutResubmittingCredentials() {
        assertEquals(OwnerLoginCardAction.CHECK_RESULT, OwnerLoginCardAction.forCard(true, true, false))
        assertEquals(OwnerLoginCardAction.SUBMIT_CREDENTIALS, OwnerLoginCardAction.forCard(true, true, true))
    }

    @Test fun initialAndPhoneFormsStillSubmitTheUserInput() {
        assertEquals(OwnerLoginCardAction.SUBMIT_CREDENTIALS, OwnerLoginCardAction.forCard(true, false, false))
        assertEquals(OwnerLoginCardAction.SUBMIT_CREDENTIALS, OwnerLoginCardAction.forCard(false, true, false))
    }
}
