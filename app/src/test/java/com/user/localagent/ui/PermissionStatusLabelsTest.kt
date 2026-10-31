package com.user.localagent.ui

import org.junit.Assert.*
import org.junit.Test

class PermissionStatusLabelsTest {
    @Test fun partialDenialNamesGrantedAndMissingPermissionsSeparately(){
        val text=permissionResultMessage(mapOf("android.permission.READ_CONTACTS" to true,"android.permission.WRITE_CALENDAR" to false))
        assertTrue(text.contains("허용 확인: 연락처 읽기"));assertTrue(text.contains("허용되지 않음: 일정 작성"))
    }
    @Test fun canceledAndDeniedResultsCannotClaimPermissionWasGranted(){
        assertTrue(permissionResultMessage(emptyMap()).contains("결과가 없습니다"))
        assertFalse(permissionResultMessage(mapOf("android.permission.READ_CALENDAR" to false)).contains("허용 확인:"))
    }
}
