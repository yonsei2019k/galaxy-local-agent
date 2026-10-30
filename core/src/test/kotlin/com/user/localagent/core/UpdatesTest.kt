package com.user.localagent.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class UpdatesTest {
    private val signingKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val pinnedCertificate = "a".repeat(64)
    private val assetPrefix = "https://github.com/example/app/releases/download/"
    private val verifier = UpdateVerifier(
        mapOf("release" to signingKey.public.encoded), pinnedCertificate, "com.example.app", assetPrefix
    )

    @Test fun acceptsValidSignedManifest() {
        val result = verifier.verify(signedEnvelope(manifest()), 10, setOf("basic"))
        assertEquals(11L, result.manifest.versionCode)
        assertFalse(result.requiresCapabilityApproval)
    }

    @Test fun rejectsTamperedPayload() {
        val original = signedEnvelope(manifest())
        val decoded = Json.decodeFromString<SignedEnvelope>(original)
        val payload = Base64.getDecoder().decode(decoded.payload).toString(Charsets.UTF_8)
        val changed = payload.replace("\"versionCode\":11", "\"versionCode\":12")
        val tampered = Json.encodeToString(decoded.copy(payload = Base64.getEncoder().encodeToString(changed.toByteArray(Charsets.UTF_8))))
        assertRejected { verifier.verify(tampered, 10, setOf("basic")) }
    }

    @Test fun rejectsTamperedSignature() {
        val decoded = Json.decodeFromString<SignedEnvelope>(signedEnvelope(manifest()))
        val signature = Base64.getDecoder().decode(decoded.signature)
        signature[0] = (signature[0].toInt() xor 1).toByte()
        val tampered = Json.encodeToString(decoded.copy(signature = Base64.getEncoder().encodeToString(signature)))
        assertRejected { verifier.verify(tampered, 10, setOf("basic")) }
    }

    @Test fun rejectsWrongSigningKey() {
        val otherKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        assertRejected { verifier.verify(signedEnvelope(manifest(), otherKey), 10, setOf("basic")) }
    }

    @Test fun rejectsUnknownKeyId() {
        val decoded = Json.decodeFromString<SignedEnvelope>(signedEnvelope(manifest()))
        assertRejected { verifier.verify(Json.encodeToString(decoded.copy(keyId = "unknown")), 10, setOf("basic")) }
    }

    @Test fun rejectsDowngradeOrSameVersion() {
        assertRejected { verifier.verify(signedEnvelope(manifest(versionCode = 10)), 10, setOf("basic")) }
    }

    @Test fun rejectsApkCertificateChange() {
        assertRejected { verifier.verify(signedEnvelope(manifest(certificate = "b".repeat(64))), 10, setOf("basic")) }
    }

    @Test fun flagsUnapprovedCapability() {
        val result = verifier.verify(signedEnvelope(manifest(capabilities = listOf("basic", "extra"))), 10, setOf("basic"))
        assertTrue(result.requiresCapabilityApproval)
    }

    @Test fun rejectsUnsupportedDatabaseSchema() {
        assertRejected { verifier.verify(signedEnvelope(manifest(databaseSchemaVersion = 2)), 10, setOf("basic")) }
    }

    @Test fun rejectsNonDefaultHttpsPort() {
        assertRejected { verifier.verify(signedEnvelope(manifest(apkUrl = assetPrefix.replace("github.com", "github.com:8443") + "v1/app.apk")), 10, setOf("basic")) }
    }

    private fun manifest(
        versionCode: Long = 11,
        certificate: String = pinnedCertificate,
        capabilities: List<String> = listOf("basic"),
        databaseSchemaVersion: Int = 1,
        apkUrl: String = assetPrefix + "v1/app.apk"
    ) = UpdateManifest(
        packageName = "com.example.app",
        versionCode = versionCode,
        versionName = "1.1",
        commitSha = "c".repeat(40),
        apkUrl = apkUrl,
        apkSha256 = "d".repeat(64),
        apkCertificateSha256 = certificate,
        minSdk = 31,
        capabilityApiVersion = 1,
        databaseSchemaVersion = databaseSchemaVersion,
        capabilities = capabilities
    )

    private fun signedEnvelope(manifest: UpdateManifest, key: KeyPair = signingKey): String {
        val payload = Json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("Ed25519").run {
            initSign(key.private)
            update(payload)
            sign()
        }
        return Json.encodeToString(
            SignedEnvelope("release", Base64.getEncoder().encodeToString(payload), Base64.getEncoder().encodeToString(signature))
        )
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        } catch (_: IllegalStateException) {
            return
        }
        throw AssertionError("Expected update verification to reject input")
    }
}
