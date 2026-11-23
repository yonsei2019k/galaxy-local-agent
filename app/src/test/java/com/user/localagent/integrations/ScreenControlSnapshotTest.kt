package com.user.localagent.integrations

import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import uniffi.ziphap_android.NativeScreenRole
import uniffi.ziphap_android.NativeScreenTarget
import uniffi.ziphap_android.nativeScreenSnapshotId

class ScreenControlSnapshotTest {
    @Test
    fun changedVisibleTotalRejectsPreviousSnapshotEvenWithSamePayButton() {
        val payButton=NativeScreenTarget(NativeScreenRole.TAP,digest("Pay"),digest("checkout_button"),
            0,100,120,140,false,true)
        fun capture(total:String,window:Int=5)=checkNotNull(nativeScreenSnapshotId(
            ByteArray(32){1},digest("com.example.checkout"),window,false,listOf(digest(total)),listOf(payButton)
        ))

        val approved=capture("Total 32,000")
        ScreenControl.requireCurrentSnapshot(approved,capture("Total 32,000"))
        assertThrows(ScreenControl.SnapshotChanged::class.java) {
            ScreenControl.requireCurrentSnapshot(approved,capture("Total 52,000"))
        }
        assertThrows(ScreenControl.SnapshotChanged::class.java) {
            ScreenControl.requireCurrentSnapshot(approved,capture("Total 32,000",window=6))
        }
    }

    @Test
    fun transientEmptyWindowIsObservedAgainWithoutReplayingAnything()=runBlocking {
        var reads=0
        var waits=0
        val state=ScreenControl.retryScreenObservation(wait={waits++}){
            if(++reads<3)throw ScreenControl.ScreenObservationUnavailable("transition")
            "next screen"
        }
        assertEquals("next screen",state)
        assertEquals(3,reads)
        assertEquals(2,waits)
    }

    @Test
    fun protectedScreenDoesNotRetryAndMissingWindowIsBounded() {
        var protectedReads=0
        assertThrows(ScreenControl.ScreenRefused::class.java){
            runBlocking {
                ScreenControl.retryScreenObservation(wait={error("protected read must not wait")}){
                    protectedReads++
                    throw ScreenControl.ScreenRefused("locked")
                }
            }
        }
        assertEquals(1,protectedReads)
        var emptyReads=0
        assertThrows(ScreenControl.ScreenObservationUnavailable::class.java){
            runBlocking {
                ScreenControl.retryScreenObservation(wait={}){
                    emptyReads++
                    throw ScreenControl.ScreenObservationUnavailable("transition")
                }
            }
        }
        assertEquals(4,emptyReads)
    }

    private fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}
