package com.user.localagent

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/** Explicit paired-developer export. No server, logcat payload, keys, drafts or other-app data. */
@RunWith(AndroidJUnit4::class)
class DeveloperConversationExport {
    @Test fun exportRecentAppConversation()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue("Explicit conversation export invocation is required",InstrumentationRegistry.getArguments().getString("developerConversationExportApproved")=="true")
        val context=instrumentation.targetContext
        val app=(context.applicationContext as LocalAgentApplication).container
        val dir=File(context.getExternalFilesDir(null)?:context.filesDir,"diagnostics")
        check(dir.isDirectory||dir.mkdirs())
        val output=File(dir,"conversation-export.json")
        val temporary=File(dir,"conversation-export.tmp")
        val metadata=try {java.io.FileOutputStream(temporary).use {stream->
            com.user.localagent.diagnostics.ConversationDiagnostics(app.repository).exportSnapshot(stream,limit=10_000,includeText=true,includeToolMetadata=true).also {stream.fd.sync()}
        }}catch(error:Throwable){temporary.delete();throw error}
        check(temporary.renameTo(output))
        instrumentation.sendStatus(0,Bundle().apply {putString("stream","Explicit local conversation export saved; count=${metadata.messageCount}; truncated=${metadata.truncated}; no contents printed.\n")})
    }
    @Test fun prepareApprovedDeveloperUpdate()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Explicit developer install invocation is required",args.getString("developerInstallApproved")=="true")
        val expected=args.getString("nextApkSha256")?:error("Next APK hash is required")
        require(expected.matches(Regex("[a-f0-9]{64}")))
        val context=instrumentation.targetContext
        val apk=File(context.getExternalFilesDir(null),"diagnostics/developer-next.apk")
        try {
            val digest=java.security.MessageDigest.getInstance("SHA-256")
            apk.inputStream().use {input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
            require(digest.digest().joinToString(""){"%02x".format(it)}==expected)
            val flags=android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES or android.content.pm.PackageManager.GET_PERMISSIONS
            val installed=context.packageManager.getPackageInfo(context.packageName,flags)
            val archive=context.packageManager.getPackageArchiveInfo(apk.path,flags)?:error("APK unavailable")
            require(archive.packageName==context.packageName&&archive.longVersionCode>=installed.longVersionCode)
            val oldSigners=installed.signingInfo!!.apkContentsSigners.map {it.toCharsString()}
            require(archive.signingInfo!!.apkContentsSigners.map {it.toCharsString()}==oldSigners)
            val additions=archive.requestedPermissions.orEmpty().toSet()-installed.requestedPermissions.orEmpty().toSet()
            val explicitlyApproved=if(args.getString("approvedCalendarWriteAddition")=="true")setOf(android.Manifest.permission.WRITE_CALENDAR)else emptySet()
            require((additions-explicitlyApproved).isEmpty()){"The developer update requests an unreviewed Android permission"}
            val app=(context.applicationContext as LocalAgentApplication).container
            val copied=app.repository.carryBuiltinGrants(app.hostCodeHash,expected)
            instrumentation.sendStatus(0,Bundle().apply {putString("stream","Verified developer APK; preserved $copied existing scoped grants.\n")})
        }finally{apk.delete()}
    }
}
