package com.user.localagent.core.connections

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.Base64
import kotlinx.serialization.json.*

/** One read-only IMAP session. Only bounded header literals are requested, never bodies. */
class NaverMailInbox(private val input: InputStream, private val output: OutputStream) {
    companion object {
        fun secure(socket:javax.net.ssl.SSLSocket) {
            socket.soTimeout=12000
            socket.sslParameters=socket.sslParameters.apply { endpointIdentificationAlgorithm="HTTPS" }
        }
    }
    private var consumed = 0
    private fun byte(): Int {
        check(++consumed <= 256 * 1024)
        return input.read().also { check(it >= 0) }
    }
    private fun line(): String = buildString {
        while (true) {
            val c = byte()
            if (c == 13) { check(byte() == 10); break }
            check(length < 8192 && c != 10)
            append(c.toChar())
        }
    }
    private fun command(tag: String, command: String, observe: (String) -> Unit = {}) {
        output.write(("$tag $command\r\n").toByteArray(Charsets.US_ASCII))
        output.flush()
        repeat(200) {
            val value = line()
            if (value.startsWith("$tag ", true)) {
                check(value.substringAfter(' ').substringBefore(' ').equals("OK", true))
                return
            }
            check(!value.startsWith("* BYE", true))
            observe(value)
        }
        error("IMAP response limit")
    }
    fun inbox(login: String, password: String, probe: Boolean): JsonArray {
        require(login.matches(Regex("[A-Za-z0-9._-]{1,64}(?:@naver\\.com)?")))
        require(password.length in 8..128 && password.all {it.code in 33..126 && it !in "\"\\"})
        check(line().startsWith("* OK ", true))
        command("Z1","LOGIN \"$login\" \"$password\"")
        var count: Int? = null
        command("Z2","EXAMINE INBOX") {
            Regex("\\* ([0-9]{1,9}) EXISTS", RegexOption.IGNORE_CASE).matchEntire(it)?.let { m -> count=m.groupValues[1].toInt() }
        }
        val total=checkNotNull(count)
        return buildJsonArray {
            if (probe || total == 0) return@buildJsonArray
            val first=maxOf(1,total-9)
            val rows=sortedMapOf<Int,JsonObject>(reverseOrder())
            command("Z3","FETCH $first:$total (UID BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE)])") { value ->
                val literal=Regex("\\{([0-9]{1,5})\\}$").find(value) ?: return@command
                val length=literal.groupValues[1].toInt()
                val sequence=Regex("\\* ([0-9]{1,9}) FETCH .*",RegexOption.IGNORE_CASE).matchEntire(value)?.groupValues?.get(1)?.toInt()
                check(length<=16384 && sequence!=null && sequence in first..total && sequence !in rows)
                val raw=ByteArray(length){byte().toByte()}.toString(Charsets.UTF_8)
                val fields=raw.replace(Regex("\\r?\\n[ \\t]+")," ").lineSequence().filter { ':' in it }
                    .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim().take(4096) }
                rows[sequence]=buildJsonObject {
                    for(k in listOf("from","subject","date"))fields[k]?.let { put(k,decode(it).take(512)) }
                }
            }
            rows.values.forEach {add(it)}
        }
    }
    private fun decode(value:String):String = Regex("=\\?([^? ]{1,40})\\?([bBqQ])\\?([^?]{0,4096})\\?=").replace(value){ m ->
        runCatching {
            val encoded=m.groupValues[3]
            val data=if(m.groupValues[2].equals("B",true))Base64.getDecoder().decode(encoded)
                else java.net.URLDecoder.decode(encoded.replace("+","%2B").replace('_',' ').replace('=','%'), "ISO-8859-1").toByteArray(Charsets.ISO_8859_1)
            data.toString(Charset.forName(m.groupValues[1]))
        }.getOrDefault(m.value)
    }
}
