package com.user.localagent.core

import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest

/**
 * Rules shared by every browser the agent drives (in-app browser on the phone, headless browser on the PC).
 * They are deterministic host checks, independent of the model: public pages only, never typing into
 * login/card/OTP/identity fields, owner approval before irreversible controls, bounded typed text.
 * Mirrors pc-worker/deployed/browser_agent.py.
 */
object WebAgentPolicy {
    data class Element(val ref:String,val tag:String,val role:String="",val type:String="",val label:String="",
        val name:String="",val id:String="",val placeholder:String="",val autocomplete:String="",val editable:Boolean=false)

    private val localSuffixes=listOf(".localhost",".local",".internal",".lan",".home.arpa")
    fun urlAllowed(url:String?):Boolean {
        if(url==null||url.length>2048)return false
        val uri=runCatching{URI(url)}.getOrNull()?:return false
        if(uri.scheme?.lowercase() !in setOf("http","https")||uri.rawUserInfo!=null)return false
        if(uri.port !in setOf(-1,80,443))return false
        val host=(uri.host?:return false).lowercase().trimEnd('.').removePrefix("[").removeSuffix("]")
        if(host=="localhost"||localSuffixes.any{host.endsWith(it)})return false
        val literal=host.all{it.isDigit()||it=='.'}||host.contains(':')
        if(literal){
            val address=runCatching{InetAddress.getByName(host)}.getOrNull()?:return false
            return !(address.isLoopbackAddress||address.isSiteLocalAddress||address.isLinkLocalAddress||address.isAnyLocalAddress||
                address.isMulticastAddress||cgnat(address)||uniqueLocal(address))
        }
        return '.' in host
    }
    private fun cgnat(a:InetAddress)=a.address.size==4&&(a.address[0].toInt() and 255)==100&&(a.address[1].toInt() and 192)==64
    private fun uniqueLocal(a:InetAddress)=a.address.size==16&&(a.address[0].toInt() and 0xfe)==0xfc

    private val sensitive=Regex("pass(word|wd)?|pwd|card|cvc|cvv|otp|one.?time|verif|auth.?code|pin\\b|ssn|resident|"+
        "비밀번호|암호|카드|유효기간|보안\\s*코드|인증\\s*번호|인증코드|주민|계좌",RegexOption.IGNORE_CASE)
    private val sensitiveAutocomplete=Regex("cc-|current-password|new-password|one-time-code",RegexOption.IGNORE_CASE)
    fun sensitiveField(e:Element):Boolean =
        e.type.equals("password",true)||sensitiveAutocomplete.containsMatchIn(e.autocomplete)||
            sensitive.containsMatchIn(listOf(e.name,e.id,e.label,e.placeholder).joinToString(" "))

    private val risky=Regex("결제|구매|주문|예매|예약\\s*(하기|확정|완료|신청)|송금|이체|보내기|전송|삭제|탈퇴|해지|제출|가입\\s*완료|동의하고|"+
        "\\bpay\\b|payment|\\bbuy\\b|purchase|check\\s*out|checkout|place\\s*order|order\\s*now|book\\s*now|"+
        "\\breserve\\b|\\bconfirm\\b|\\bsubmit\\b|\\bsend\\b|\\btransfer\\b|\\bdelete\\b|remove\\s*account|unsubscribe",RegexOption.IGNORE_CASE)
    fun riskyControl(e:Element):Boolean {
        if(e.tag !in setOf("button","a","input","div","span","li")&&e.role.isBlank())return false
        if(e.tag=="input"&&e.type.lowercase() !in setOf("submit","button","image"))return false
        return risky.containsMatchIn(e.label)
    }

    private val longDigits=Regex("\\d[\\d -]{11,}\\d")
    fun safeText(text:String?):Boolean = text!=null&&text.length in 1..500&&'\u0000' !in text&&!longDigits.containsMatchIn(text)
    fun looksLikeSecretNumber(text:String)=longDigits.containsMatchIn(text)

    fun signature(url:String,elements:List<Element>):String {
        val body=url+"\n"+elements.joinToString("\n"){"${it.ref}|${it.tag}|${it.type}|${it.label}"}
        return MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString(""){"%02x".format(it)}.take(32)
    }
    fun isSearchField(e:Element)=e.type.equals("search",true)||Regex("검색|search|찾기",RegexOption.IGNORE_CASE).containsMatchIn(e.label+" "+e.placeholder)
}
