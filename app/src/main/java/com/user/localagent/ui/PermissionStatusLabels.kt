package com.user.localagent.ui

/** Describe actual permission readback; a denied or canceled request is never called granted. */
internal fun permissionResultMessage(actual:Map<String,Boolean>):String {
    if(actual.isEmpty())return "권한 변경 결과가 없습니다. 시스템 설정에서 현재 권한을 확인해주세요."
    val labels=mapOf("android.permission.READ_CALENDAR" to "일정 읽기","android.permission.WRITE_CALENDAR" to "일정 작성",
        "android.permission.READ_CONTACTS" to "연락처 읽기","android.permission.POST_NOTIFICATIONS" to "알림 표시")
    fun names(granted:Boolean)=actual.filterValues{it==granted}.keys.map{labels[it]?:"추가 권한"}.distinct().joinToString(" · ")
    val approved=names(true);val denied=names(false)
    return listOfNotNull(approved.takeIf{it.isNotEmpty()}?.let{"허용 확인: $it"},denied.takeIf{it.isNotEmpty()}?.let{"허용되지 않음: $it. 필요한 경우 시스템 설정에서 변경해주세요."}).joinToString("\n")
}
