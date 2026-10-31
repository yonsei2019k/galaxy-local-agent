package com.user.localagent.compute

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Android 17 requires user consent before contacting even an owner-paired LAN PC. */
object PcLocalNetwork {
    const val PERMISSION="android.permission.ACCESS_LOCAL_NETWORK"
    fun allowed(context:Context):Boolean=Build.VERSION.SDK_INT<37||
        ContextCompat.checkSelfPermission(context,PERMISSION)==PackageManager.PERMISSION_GRANTED
}
