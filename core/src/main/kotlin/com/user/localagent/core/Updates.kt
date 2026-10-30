package com.user.localagent.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.SignatureException
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

@Serializable data class SignedEnvelope(val keyId:String,val payload:String,val signature:String)
@Serializable data class UpdateManifest(val schemaVersion:Int=1,val packageName:String,val versionCode:Long,val versionName:String,val commitSha:String,val apkUrl:String,val apkSha256:String,val apkCertificateSha256:String,val minSdk:Int,val capabilityApiVersion:Int,val databaseSchemaVersion:Int,val capabilities:List<String>,val channel:String="stable")
data class VerifiedUpdate(val manifest:UpdateManifest,val requiresCapabilityApproval:Boolean)
class UpdateVerifier(private val pinnedKeys:Map<String,ByteArray>,private val pinnedApkCertificateSha256:String,private val expectedPackageName:String,private val allowedAssetPrefix:String) {
    fun verify(raw:String,currentVersionCode:Long,approvedCapabilities:Set<String>):VerifiedUpdate {
        require(raw.length<=128*1024)
        rejectDuplicateKeys(raw)
        val envelope=Json.decodeFromString<SignedEnvelope>(raw)
        val encodedKey=pinnedKeys[envelope.keyId]?:error("알 수 없는 업데이트 서명 키입니다.")
        val payload=Base64.getDecoder().decode(envelope.payload)
        require(payload.size<=64*1024)
        val signature=Signature.getInstance("Ed25519")
        signature.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(encodedKey)))
        signature.update(payload)
        val signatureBytes=Base64.getDecoder().decode(envelope.signature)
        val validSignature=try {signature.verify(signatureBytes)} catch(error:SignatureException) {
            throw IllegalArgumentException("업데이트 서명이 유효하지 않습니다.",error)
        }
        require(validSignature){"업데이트 서명이 유효하지 않습니다."}
        val text=payload.toString(Charsets.UTF_8);rejectDuplicateKeys(text)
        val manifest=Json.decodeFromString<UpdateManifest>(text)
        require(manifest.schemaVersion==1 && manifest.packageName==expectedPackageName && manifest.channel=="stable")
        require(manifest.versionCode>currentVersionCode){"최신 버전이거나 이전 버전입니다."}
        require(manifest.apkSha256.matches(Regex("[a-fA-F0-9]{64}")))
        require(manifest.commitSha.matches(Regex("[a-fA-F0-9]{40}")))
        require(manifest.apkCertificateSha256.equals(pinnedApkCertificateSha256,true)){"APK 서명 인증서 변경은 승인되지 않았습니다."}
        require(manifest.minSdk>=31 && manifest.capabilityApiVersion==1 && manifest.databaseSchemaVersion==1){"지원하지 않는 호환 버전입니다."}
        val uri=URI(manifest.apkUrl)
        require(uri.isAbsolute && uri.scheme=="https" && uri.host=="github.com" && uri.rawUserInfo==null && uri.port==-1 && uri.rawQuery==null && uri.rawFragment==null)
        val rawPath=uri.rawPath ?: error("업데이트 경로가 없습니다.")
        val normalizedPrefix=URI(allowedAssetPrefix)
        require(normalizedPrefix.scheme=="https" && normalizedPrefix.host=="github.com" && normalizedPrefix.port==-1 && normalizedPrefix.rawUserInfo==null && normalizedPrefix.rawQuery==null && normalizedPrefix.rawFragment==null)
        require(uri.normalize()==uri && manifest.apkUrl.startsWith(allowedAssetPrefix) && !rawPath.contains('\\') &&
            !rawPath.lowercase().contains("%2e") && !rawPath.lowercase().contains("%2f") && !rawPath.lowercase().contains("%5c"))
        require(manifest.capabilities.size<=100 && manifest.capabilities.distinct().size==manifest.capabilities.size)
        return VerifiedUpdate(manifest,manifest.capabilities.any {it !in approvedCapabilities})
    }
}
