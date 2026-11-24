package com.user.localagent.integrations

import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import uniffi.ziphap_android.NativeScreenRole
import uniffi.ziphap_android.NativeScreenTarget
import uniffi.ziphap_android.nativeScreenSnapshotId

class ScreenControlSnapshotTest {
    @Test
    fun checkedStateChangesEvidenceWhileDisabledControlsAreNeverPresentedAsActionable() {
        val unchecked=ScreenControlState(enabled=true,selected=false,checked=ScreenChecked.UNCHECKED)
        val checked=unchecked.copy(checked=ScreenChecked.CHECKED)
        val disabled=checked.copy(enabled=false,selected=true)
        fun snapshot(state:ScreenControlState):String {
            val option=NativeScreenTarget(NativeScreenRole.TAP,digest("Option"),digest(state.identity),
                0,0,120,40,false,state.enabled)
            return checkNotNull(nativeScreenSnapshotId(ByteArray(32){1},digest("com.example"),5,
                false,emptyList(),listOf(option)))
        }

        assertThrows(ScreenControl.SnapshotChanged::class.java) {
            ScreenControl.requireCurrentSnapshot(snapshot(unchecked),snapshot(checked))
        }
        assertEquals(JsonPrimitive(false),buildJsonObject {unchecked.appendTo(this,false)}["checked"])
        val output=buildJsonObject {disabled.appendTo(this,false)}
        assertEquals(JsonPrimitive(false),output["enabled"])
        assertEquals(JsonPrimitive(true),output["selected"])
        assertEquals(JsonPrimitive(true),output["checked"])
        assertEquals(JsonPrimitive(true),output["blocked"])
        val partial=buildJsonObject {checked.copy(checked=ScreenChecked.PARTIAL).appendTo(this,false)}
        assertEquals(JsonPrimitive(true),partial["partiallyChecked"])
        assertEquals(null,partial["checked"])
        assertEquals("""{"collectionItem":{"rowIndex":2,"columnIndex":1,"label":"Third lesson"}}""",
            buildJsonObject {ScreenCollectionItem(2,1,"Third lesson").appendTo(this)}.toString())
    }

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
