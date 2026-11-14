package com.user.localagent.core

import java.net.URI

/** A paired PC is a local compute endpoint, never an arbitrary public upload URL. */
object PcComputeContract {
    fun endpoint(raw:String):URI {
        require(raw.length<=160)
        val uri=URI(raw)
        require(uri.scheme=="https"&&uri.userInfo==null&&uri.rawQuery==null&&uri.rawFragment==null&&uri.rawPath in listOf("", "/")&&uri.port in 1..65535)
        require(privateIpv4(uri.host)) {"같은 로컬 네트워크의 PC 주소가 필요합니다."}
        return uri
    }
    fun privateIpv4(host:String?):Boolean {
        if(host==null||!Regex("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}").matches(host))return false
        val parts=host.split('.').map {it.toInt()}
        if(parts.any {it !in 0..255}||parts.last() in listOf(0,255)||parts.joinToString(".")!=host)return false
        // 100.64.0.0/10 is the shared range Tailscale assigns on the owner's own private overlay network.
        return parts[0]==10 || parts[0]==100&&parts[1] in 64..127 || parts[0]==172&&parts[1] in 16..31 || parts[0]==192&&parts[1]==168 || host=="127.0.0.1"
    }
    fun steps(pc:Boolean,requested:Int?,phoneQuality:Boolean=false):Int = (requested?:if(pc)50 else if(phoneQuality)PhoneImageQuality.STEPS else 8).also {
        require(it in if(pc)12..50 else if(phoneQuality)20..50 else 1..8){if(pc)"PC 정밀 모델은 12–50단계를 사용합니다."else if(phoneQuality)"휴대폰 SDXL 모델은 20–50단계를 사용합니다."else"기존 LCM 모델은 1–8단계를 사용합니다."}
    }
}
