package com.user.localagent.updates

import org.junit.Assert.*
import org.junit.Test

class RuntimeBusyOwnersTest {
    @Test fun finishingOneRunOrLegacyFlagCannotUnblockAnotherRun() {
        val owners=RuntimeBusyOwners();val pc=owners.acquire("pc-run");val phone=owners.acquire("phone-run")
        owners.setLegacyBusy(false);pc.close();assertTrue(owners.busy())
        phone.close();assertFalse(owners.busy())
    }
    @Test fun sameOwnerIdUsesIndependentIdempotentTokens() {
        val owners=RuntimeBusyOwners();val first=owners.acquire("run");val second=owners.acquire("run")
        first.close();first.close();assertTrue(owners.busy());second.close();assertFalse(owners.busy())
    }
    @Test fun installerCannotPassAnActiveRunAndPendingInstallRejectsNewOwners() {
        val owners=RuntimeBusyOwners();val run=owners.acquire("run");var commits=0
        assertFalse(owners.submitInstallIfIdle{commits++});assertEquals(0,commits)
        run.close();assertTrue(owners.submitInstallIfIdle{commits++});assertEquals(1,commits)
        assertThrows(IllegalStateException::class.java){owners.acquire("late-run")}
        assertThrows(IllegalStateException::class.java){owners.setLegacyBusy(true)}
        owners.installationFinished();val next=owners.acquire("next-run");next.close();assertFalse(owners.busy())
    }
    @Test fun lostInstallerCallbackStopsBlockingNewWorkAfterTheHold() {
        var now=1_000L;val owners=RuntimeBusyOwners{now}
        assertTrue(owners.submitInstallIfIdle{})
        now+=RuntimeBusyOwners.INSTALL_HOLD_MILLIS-1
        assertTrue(owners.busy());assertThrows(IllegalStateException::class.java){owners.acquire("too-early")}
        now+=1
        assertFalse(owners.busy())
        owners.setLegacyBusy(true);owners.setLegacyBusy(false)
        val run=owners.acquire("next");assertFalse(owners.submitInstallIfIdle{});run.close()
        assertTrue(owners.submitInstallIfIdle{})
    }
    @Test fun rejectedCommitAndFailedBusyPublicationLeaveNoPhantomOwner() {
        val owners=RuntimeBusyOwners()
        assertThrows(IllegalStateException::class.java){owners.submitInstallIfIdle{error("synthetic installer rejection")}}
        assertFalse(owners.busy())
        assertThrows(IllegalStateException::class.java){owners.acquire("run"){error("synthetic persistence rejection")}}
        assertFalse(owners.busy())
        val run=owners.acquire("next");run.close();assertFalse(owners.busy())
    }
}
