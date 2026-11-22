package com.user.localagent.updates

import com.user.localagent.core.UpdateManifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionSummaryTest {
    private val release = UpdateManifest(packageName = "com.user.localagent", versionCode = 2058,
        versionName = "0.1.58", commitSha = "", apkUrl = "", apkSha256 = "", apkCertificateSha256 = "",
        minSdk = 31, capabilityApiVersion = 1, databaseSchemaVersion = 3, capabilities = emptyList())

    @Test fun usesInstalledAndSignedReleaseVersions() {
        val newer = updateVersionSummary("0.1.57", 2057, release, 37)
        assertTrue(newer.contains("0.1.57 (2057)"))
        assertTrue(newer.contains("0.1.58 (2058) 게시를 확인했습니다"))
        assertFalse(newer.contains("설치 완료"))
        assertTrue(updateVersionSummary("0.1.58", 2058, release, 37).contains("최신 게시 버전"))
        assertTrue(updateVersionSummary("0.1.59", 2059, release, 37).contains("현재 게시 버전 0.1.58 (2058)보다 높습니다"))
    }

    @Test fun explainsWhenPublishedReleaseIsIncompatible() {
        val result = updateVersionSummary("0.1.57", 2057, release.copy(minSdk = 37), 36)
        assertTrue(result.contains("이 기기의 Android 버전에서는 설치할 수 없습니다"))
    }
}
