package com.user.localagent.connections

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Public OAuth registration metadata only. Android SDK authorization uses package + signing identity. */
class GoogleClientConfiguration(private val context:Context) {
    private val configuration by lazy {
        val raw=context.assets.open("connections/google-registration.json").bufferedReader().use {it.readText()}
        require(raw.length<=8192)
        Json.parseToJsonElement(raw).jsonObject.also {data->
            require(data.getValue("clientType").jsonPrimitive.content=="android")
            require(data.getValue("clientId").jsonPrimitive.content.matches(Regex("[0-9]+-[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com")))
            require(data.keys.none {it.contains("secret",true)||it.contains("token",true)})
        }
    }
    fun verifyInstalledIdentity() {
        require(context.packageName==configuration.getValue("applicationId").jsonPrimitive.content){"구글 등록 패키지와 앱이 일치하지 않습니다."}
        @Suppress("DEPRECATION") val info=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        val expected=configuration.getValue("certificateSha1").jsonPrimitive.content.replace(":","").lowercase()
        val matched=info.signingInfo?.apkContentsSigners.orEmpty().any {signature->
            MessageDigest.getInstance("SHA-1").digest(signature.toByteArray()).joinToString(""){"%02x".format(it)}==expected
        }
        require(matched){"구글에 등록한 앱 서명과 현재 설치된 앱이 일치하지 않습니다."}
    }
    fun publicRegistrationInfo()=buildJsonObject {
        put("projectId",configuration.getValue("projectId"));put("clientId",configuration.getValue("clientId"));put("clientType","android")
        put("usesPackageAndSigningCertificate",true);put("accountAuthorizationStillRequired",true)
    }.toString()
}
