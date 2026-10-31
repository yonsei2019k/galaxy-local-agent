package com.user.localagent.connections

import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.serialization.json.*

data class VerifiedGoogleAccount(val principal:String,val email:String) {
    override fun toString()="VerifiedGoogleAccount(identity withheld)"
}

/** AuthorizationResult contains an access token, not a reliable account profile. */
object GoogleAccountIdentity {
    private data class Cached(val account:VerifiedGoogleAccount,val at:Long)
    private val cache=linkedMapOf<String,Cached>()
    private fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it.toInt()and 255)}
    fun read(token:String):VerifiedGoogleAccount {
        require(token.isNotBlank()&&token.none {it=='\r'||it=='\n'})
        val key=digest(token);val now=System.currentTimeMillis()
        synchronized(cache){cache[key]?.takeIf {now-it.at<60_000}?.let {return it.account}}
        val connection=URL("https://www.googleapis.com/oauth2/v3/userinfo").openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects=false;connection.connectTimeout=10_000;connection.readTimeout=10_000
            connection.setRequestProperty("Authorization","Bearer $token");connection.setRequestProperty("Cache-Control","no-store")
            check(connection.responseCode==200){"구글 계정 식별 정보를 확인하지 못했습니다."}
            val output=java.io.ByteArrayOutputStream()
            connection.inputStream.use {input->val buffer=ByteArray(2048);while(true){val count=input.read(buffer);if(count<0)break;require(output.size()+count<=16_384);output.write(buffer,0,count)}}
            val data=Json.parseToJsonElement(output.toString(Charsets.UTF_8.name())).jsonObject
            val subject=data.getValue("sub").jsonPrimitive.content;require(subject.length in 1..256)
            val email=data.getValue("email").jsonPrimitive.content;require(email.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")))
            require(data["email_verified"]?.jsonPrimitive?.booleanOrNull==true){"검증된 구글 계정이 필요합니다."}
            val account=VerifiedGoogleAccount(digest(subject),email)
            synchronized(cache){cache[key]=Cached(account,now);while(cache.size>8)cache.remove(cache.keys.first())}
            return account
        }finally{connection.disconnect()}
    }
}
