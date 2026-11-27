package com.user.localagent.tools

import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

internal object CertificateReadback {
    private val title=Regex("""(?:과\s*정\s*명|교\s*육\s*명|강\s*의\s*명)""")
    private val dateLabel=Regex("""(?:이\s*수|수\s*료)\s*일(?:\s*자)?""")
    private val date=Regex("""(\d{4})\s*[.년/-]\s*(\d{1,2})\s*[.월/-]\s*(\d{1,2})\s*[.일]?""")

    fun courseAndYear(text:String):Pair<String,Int>? {
        val lines=text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if(lines.none {it.replace(" ","") in setOf("이수증","수료증","교육이수증","교육수료증")})return null
        fun field(label:Regex):String? {
            val pattern=Regex("^("+label.pattern+")(?:\\s*[:：]\\s*|\\s+)(.*)$")
            return lines.mapIndexedNotNull {index,line->
                val inline=pattern.matchEntire(line)?.groupValues?.get(2)?.trim()
                if(!inline.isNullOrEmpty())inline
                else if(inline!=null || label.matches(line))lines.getOrNull(index+1) else null
            }.singleOrNull()
        }
        val course=field(title)?.takeIf {it.length in 1..240}?:return null
        val parts=date.matchEntire(field(dateLabel)?:return null)?.groupValues?:return null
        val day=runCatching {LocalDate.of(parts[1].toInt(),parts[2].toInt(),parts[3].toInt())}.getOrNull()?:return null
        return course to day.year
    }

    fun inspect(directory:File,receipt:String):String {
        if(Build.VERSION.SDK_INT<35)return receipt
        val data=Json.parseToJsonElement(receipt).jsonObject
        val name=data["fileName"]?.jsonPrimitive?.content?:return receipt
        if(!name.matches(Regex("artifact-[a-f0-9-]{36}\\.pdf")))return receipt
        val file=File(directory,name)
        return runCatching {
            require(file.length() in 5..(2L*1024*1024))
            val sha=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
            require(data["sha256"]==JsonPrimitive(sha))
            val text=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use {fd->
                PdfRenderer(fd).use {renderer->
                    require(renderer.pageCount==1)
                    renderer.openPage(0).use {page->buildString {
                        for(part in page.textContents){
                            require(length+part.text.length<24000)
                            appendLine(part.text)
                        }
                    }}
                }
            }
            val (course,year)=courseAndYear(text)?:return receipt
            JsonObject(data+mapOf("certificateCourse" to JsonPrimitive(course),"certificateCompletionYear" to JsonPrimitive(year))).toString()
        }.getOrDefault(receipt)
    }
}
