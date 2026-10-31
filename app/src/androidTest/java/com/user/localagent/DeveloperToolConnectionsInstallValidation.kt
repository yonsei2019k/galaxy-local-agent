package com.user.localagent

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

class DeveloperToolConnectionsInstallValidation {
    @Test fun preserveExistingGrantsForApprovedToolUpdate()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("toolConnectionUpdateApproved")=="true")
        val expected=requireNotNull(args.getString("nextApkSha256"))
        require(expected.matches(Regex("[a-f0-9]{64}")))
        val context=instrumentation.targetContext
        val file=File(context.getExternalFilesDir(null),"diagnostics/developer-next.apk")
        try {
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use {input->val block=ByteArray(65536);while(true){val count=input.read(block);if(count<0)break;digest.update(block,0,count)}}
            check(digest.digest().joinToString(""){"%02x".format(it.toInt()and 255)}==expected)
            val flags=PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PERMISSIONS
            val current=context.packageManager.getPackageInfo(context.packageName,flags)
            val next=requireNotNull(context.packageManager.getPackageArchiveInfo(file.path,flags))
            require(next.packageName==context.packageName&&next.longVersionCode>=current.longVersionCode)
            require(next.signingInfo!!.apkContentsSigners.map {it.toCharsString()}==current.signingInfo!!.apkContentsSigners.map {it.toCharsString()})
            val additions=next.requestedPermissions.orEmpty().toSet()-current.requestedPermissions.orEmpty().toSet()
            val reviewed=setOf("android.permission.ACCESS_LOCAL_NETWORK","android.permission.READ_EXTERNAL_STORAGE")
            require((additions-reviewed).isEmpty())
            val app=(context.applicationContext as LocalAgentApplication).container
            val copied=app.repository.carryBuiltinGrants(app.hostCodeHash,expected)
            instrumentation.sendStatus(0,Bundle().apply {putString("stream","Verified matching package/signature/APK hash and preserved existing scoped grants: $copied. No new runtime permission was granted.\n")})
        }finally{file.delete()}
    }
}
