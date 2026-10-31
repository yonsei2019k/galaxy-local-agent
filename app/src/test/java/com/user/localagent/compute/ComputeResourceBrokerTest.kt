package com.user.localagent.compute

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ComputeResourceBrokerTest {
    @Test fun pcAndPhoneCanProceedWhileAnotherPhoneWaits() = runBlocking {
        val phone = OwnedComputeGate(); val pc = OwnedComputeGate()
        val broker = ComputeResourceBroker(phone, pc)
        val pcLease = broker.acquire("remote-run", ComputeResource.PC)
        val phoneLease = broker.acquire("phone-run", ComputeResource.PHONE)
        val waiting = async { broker.acquire("next-phone", ComputeResource.PHONE) }
        yield(); delay(100)
        assertFalse(waiting.isCompleted); assertTrue(phone.get()); assertTrue(pc.get())
        phoneLease.close()
        val next = withTimeout(1000) { waiting.await() }
        assertEquals("next-phone", broker.owner(ComputeResource.PHONE).value)
        next.close(); pcLease.close()
        assertFalse(phone.get()); assertFalse(pc.get())
    }
    @Test fun legacyFinallyCannotUnlockANewRequestOrAStaleToken() = runBlocking {
        val phone = OwnedComputeGate(); val broker = ComputeResourceBroker(phone, OwnedComputeGate())
        val first = broker.acquire("first", ComputeResource.PHONE)
        phone.set(false); assertFalse(phone.compareAndSet(true, false)); assertTrue(phone.get())
        first.close()
        val second = broker.acquire("second", ComputeResource.PHONE)
        first.close(); phone.set(false)
        assertEquals("second", broker.owner(ComputeResource.PHONE).value)
        second.close(); assertFalse(phone.get())
    }
    @Test fun cancellationWaitingForPhoneDoesNotLoadOrReleaseItsCurrentOwner() = runBlocking {
        val phone = OwnedComputeGate(); val broker = ComputeResourceBroker(phone, OwnedComputeGate())
        val active = broker.acquire("active", ComputeResource.PHONE)
        var loaded = false
        val wait = launch { broker.acquire("fallback", ComputeResource.PHONE).use { loaded = true } }
        delay(100); wait.cancelAndJoin()
        assertFalse(loaded); assertEquals("active", phone.owner.value)
        active.close()
        withTimeout(1000) { broker.acquire("after", ComputeResource.PHONE).close() }
    }
    @Test fun existingLegacyNativeWorkStillBlocksNewPhoneWork() = runBlocking {
        val phone = OwnedComputeGate(); assertTrue(phone.compareAndSet(false, true))
        val broker = ComputeResourceBroker(phone, OwnedComputeGate())
        val next = async { broker.acquire("new", ComputeResource.PHONE) }
        delay(100); assertFalse(next.isCompleted)
        phone.set(false)
        withTimeout(1000) { next.await().close() }
        assertFalse(phone.get())
    }
    @Test fun benchmarkReservationPreventsPhoneAcquireButDoesNotBlockPc() = runBlocking {
        var benchmark = true
        val broker = ComputeResourceBroker(OwnedComputeGate(), OwnedComputeGate()) { benchmark }
        val next = async { broker.acquire("phone", ComputeResource.PHONE) }
        val remote = broker.acquire("remote", ComputeResource.PC)
        delay(100); assertFalse(next.isCompleted)
        benchmark = false
        withTimeout(1000) { next.await().close() }; remote.close()
    }
    @Test fun failedOrCancelledWaitingDoesNotLeakTheFifoPermit() = runBlocking {
        val broker = ComputeResourceBroker(OwnedComputeGate(), OwnedComputeGate())
        val first = broker.acquire("first", ComputeResource.PHONE)
        val canceled = launch { broker.acquire("cancelled", ComputeResource.PHONE).close() }
        yield(); canceled.cancelAndJoin(); first.close()
        withTimeout(1000) { broker.acquire("next", ComputeResource.PHONE).close() }
    }
    @Test fun anOldBackgroundCallerCannotJumpAheadOfAnAlreadyWaitingOwner() = runBlocking {
        val phone = OwnedComputeGate(); val broker = ComputeResourceBroker(phone, OwnedComputeGate())
        val first = broker.acquire("first", ComputeResource.PHONE)
        val next = async(start = CoroutineStart.UNDISPATCHED) { broker.acquire("next", ComputeResource.PHONE) }
        first.close()
        assertFalse(phone.compareAndSet(false, true))
        withTimeout(1000) { next.await().close() }
        assertTrue(phone.compareAndSet(false, true)); phone.set(false)
    }
}
