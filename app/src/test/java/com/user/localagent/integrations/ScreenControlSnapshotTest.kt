package com.user.localagent.integrations

import java.security.MessageDigest
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

    private fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}
