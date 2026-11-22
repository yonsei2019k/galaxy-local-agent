package com.user.localagent.office

import android.content.Context
import com.user.localagent.connections.ConnectionStore
import com.user.localagent.core.office.OfficePrivateProfile
import java.time.LocalDate
import java.time.ZoneId

/**
 * Owner-entered Office staff details (birth dates, school grades). Encrypted on the phone with the existing
 * AndroidKeyStore AES-GCM store; never in source, cloud payloads or logs.
 */
class OfficeProfileStore(context:Context) {
    private val store=ConnectionStore(context.applicationContext)
    private fun today()=LocalDate.now(ZoneId.of("Asia/Seoul"))

    fun read():OfficePrivateProfile? = store.read(KEY)?.let{OfficePrivateProfile.fromJson(it,today())}

    fun write(profile:OfficePrivateProfile) {
        val json=profile.validated(today()).toJson()
        require(json.length<=OfficePrivateProfile.MAX_JSON_CHARS){"사무실 직원 정보가 너무 깁니다."}
        store.write(KEY,json)
        check(read()==profile){"사무실 직원 정보 저장을 확인하지 못했습니다."}
    }

    fun clear()=store.write(KEY,null)

    private companion object { const val KEY="office.private-profile.v1" }
}
