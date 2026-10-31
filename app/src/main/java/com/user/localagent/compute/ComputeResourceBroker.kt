package com.user.localagent.compute

import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore

enum class ComputeResource { PHONE, PC }

/** Legacy callers cannot clear a resource acquired by a different, explicit request owner. */
class OwnedComputeGate {
    private data class Holder(val owner: String, val token: String)
    private var held: Holder? = null
    private var ownedWaiters = 0
    private val mutableOwner = MutableStateFlow<String?>(null)
    val owner: StateFlow<String?> = mutableOwner.asStateFlow()

    @Synchronized fun get(): Boolean = held != null

    /** Compatibility with old caller-owned native work; false only releases that legacy holder. */
    @Synchronized fun compareAndSet(expect: Boolean, update: Boolean): Boolean {
        if ((held != null) != expect) return false
        if (expect == update) return true
        if (update) {
            if (ownedWaiters > 0) return false
            held = Holder(LEGACY, UUID.randomUUID().toString()); mutableOwner.value = LEGACY; return true
        }
        if (held?.owner != LEGACY) return false
        held = null; mutableOwner.value = null; return true
    }
    @Synchronized fun set(update: Boolean) {
        if (update) {
            if (held == null && ownedWaiters == 0) { held = Holder(LEGACY, UUID.randomUUID().toString()); mutableOwner.value = LEGACY }
        } else if (held?.owner == LEGACY) { held = null; mutableOwner.value = null }
    }

    @Synchronized internal fun tryAcquire(owner: String): Closeable? {
        require(owner.isNotBlank() && owner.length <= 128 && owner != LEGACY)
        if (held != null) return null
        val value = Holder(owner, UUID.randomUUID().toString())
        held = value; mutableOwner.value = owner
        return Closeable { release(value) }
    }
    @Synchronized internal fun reserveWaiter(): Closeable {
        ownedWaiters++
        val closed = AtomicBoolean(false)
        return Closeable { if (closed.compareAndSet(false, true)) synchronized(this) { ownedWaiters-- } }
    }
    @Synchronized private fun release(value: Holder) {
        if (held != value) return
        held = null; mutableOwner.value = null
    }
    companion object { private const val LEGACY = "legacy-native-caller" }
}

/** One PC computation and one phone native computation. Waiting never loads a second local model. */
class ComputeResourceBroker(
    private val phone: OwnedComputeGate,
    private val pc: OwnedComputeGate,
    private val benchmarkActive: () -> Boolean = { false },
) {
    private val phoneOrder = Semaphore(1)
    private val pcOrder = Semaphore(1)
    fun owner(resource: ComputeResource): StateFlow<String?> = gate(resource).owner

    fun tryAcquire(owner: String, resource: ComputeResource): Lease? {
        require(owner.isNotBlank() && owner.length <= 128)
        if (resource == ComputeResource.PHONE && benchmarkActive()) return null
        val order = if (resource == ComputeResource.PHONE) phoneOrder else pcOrder
        val reservation = gate(resource).reserveWaiter()
        if (!order.tryAcquire()) { reservation.close(); return null }
        var acquired: Closeable? = null
        try {
            acquired = gate(resource).tryAcquire(owner)
            if (acquired == null || resource == ComputeResource.PHONE && benchmarkActive()) {
                acquired?.close(); order.release(); return null
            }
            return Lease(resource, owner, acquired, order)
        } catch (error: Throwable) { acquired?.close(); order.release(); throw error }
        finally { reservation.close() }
    }

    suspend fun acquire(owner: String, resource: ComputeResource): Lease {
        require(owner.isNotBlank() && owner.length <= 128)
        val order = if (resource == ComputeResource.PHONE) phoneOrder else pcOrder
        val reservation = gate(resource).reserveWaiter()
        var ordered = false
        var acquired: Closeable? = null
        try {
            order.acquire(); ordered = true
            while (true) {
                currentCoroutineContext().ensureActive()
                if (resource != ComputeResource.PHONE || !benchmarkActive()) {
                    acquired = gate(resource).tryAcquire(owner)
                    if (acquired != null) {
                        if (resource == ComputeResource.PHONE && benchmarkActive()) {
                            acquired.close(); acquired = null
                        } else {
                            currentCoroutineContext().ensureActive()
                            return Lease(resource, owner, acquired, order)
                        }
                    }
                }
                delay(50)
            }
        } catch (error: Throwable) {
            acquired?.close(); if (ordered) order.release(); throw error
        } finally { reservation.close() }
    }
    private fun gate(resource: ComputeResource) = if (resource == ComputeResource.PHONE) phone else pc

    class Lease internal constructor(val resource: ComputeResource, val owner: String,
        private val gateLease: Closeable, private val order: Semaphore) : Closeable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try { gateLease.close() } finally { order.release() }
        }
    }
}

/** Engine callers serialize transitions. Cleanup must finish before releasing the old compute resource. */
class RequestComputeLease(private val owner: String?, private val broker: ComputeResourceBroker) {
    private var lease: ComputeResourceBroker.Lease? = null
    private val identity = owner ?: "legacy-pc-${UUID.randomUUID()}"
    private val mutableLocation = MutableStateFlow<ComputeResource?>(null)
    private val mutableWaiting = MutableStateFlow<ComputeResource?>(null)
    val location: StateFlow<ComputeResource?> = mutableLocation.asStateFlow()
    val waiting: StateFlow<ComputeResource?> = mutableWaiting.asStateFlow()

    /** Legacy PHONE ownership remains with its original caller; explicit request owners use broker leases. */
    suspend fun moveTo(resource: ComputeResource, wait: Boolean = true, force: Boolean = false,
        cleanupPrevious: suspend () -> Unit): Boolean {
        if (!force && location.value == resource) return true
        cleanupPrevious() // Failure keeps the previous lease; never assume native memory is free.
        lease?.close(); lease = null; mutableLocation.value = null
        if (owner == null && resource == ComputeResource.PHONE) { mutableLocation.value = resource; return true }
        mutableWaiting.value = resource
        try {
            val next = if (wait) broker.acquire(identity, resource) else broker.tryAcquire(identity, resource)
            if (next == null) return false
            lease = next; mutableLocation.value = resource
            return true
        } finally { mutableWaiting.value = null }
    }
    suspend fun releaseAfter(cleanup: suspend () -> Unit) {
        cleanup()
        lease?.close(); lease = null; mutableLocation.value = null; mutableWaiting.value = null
    }
}
