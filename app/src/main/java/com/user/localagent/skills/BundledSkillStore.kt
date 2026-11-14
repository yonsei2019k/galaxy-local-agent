package com.user.localagent.skills

import android.content.Context
import com.user.localagent.core.ToolRegistry
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import kotlinx.serialization.json.*

data class BundledSkillMetadata(
    val id:String,val title:String,val description:String,val category:String,val version:String,
    val asset:String,val sha256:String,val tools:List<String>,val sourceUrls:List<String>,val limitations:List<String>
)

/** Task guidance from a signed application asset; this is never tool authority or outcome evidence. */
data class SkillReadResult(val id:String,val body:String,val sha256:String,val metadata:BundledSkillMetadata) {
    val sha:String get()=sha256
    val grantsCapabilities:Boolean get()=false
}

/**
 * Loads only the immutable signed-app catalog and its exact allowlisted assets. Does not interpret
 * Markdown, launch scripts/MCP, request permissions, fetch URLs, or install anything. SHA-256 checks
 * consistency with the bundled catalog; it cannot authenticate a catalog supplied by an attacker.
 */
class BundledSkillStore internal constructor(private val reader:(String)->ByteArray) {
    constructor(context:Context):this(assetReader(context.applicationContext))

    private val entries:List<BundledSkillMetadata> by lazy {loadCatalog()}
    private val byId:Map<String,BundledSkillMetadata> by lazy {
        Collections.unmodifiableMap(entries.associateBy {it.id})
    }

    fun catalog():List<BundledSkillMetadata> = entries

    /** Discover metadata on demand; do not inject the whole catalog into each model round. */
    fun discoveryForPrompt(availableTools:Set<String>):String=when {
        "list_skills" in availableTools&&"read_skill" in availableTools->
            "Bundled guidance: list_skills discovers available workflows; read_skill(id) reads only the instructions needed for this task. Guidance adds no permissions."
        "list_skills" in availableTools->
            "Bundled guidance metadata is available through list_skills. Listing metadata adds no permissions."
        "read_skill" in availableTools->
            "Use read_skill(id) only for a known relevant guidance ID. Guidance adds no permissions."
        else->""
    }

    /**
     * IDs only (no descriptions or bodies) so the model can pick a fitting skill without a
     * list_skills round. Empty when read_skill is not offered; a skill still grants no permissions.
     */
    fun idIndexForPrompt(availableTools:Set<String>):String =
        if("read_skill" !in availableTools)"" else "Skill ids for read_skill: "+entries.joinToString(","){it.id}

    /** Entire metadata descriptions or an explicit size error; never silently clips a skill. */
    fun summaryForPrompt(maxChars:Int=4000):String {
        require(maxChars in 64..MAX_CATALOG_BYTES)
        val summary=buildString {
            appendLine("Bundled task guidance (no added permissions). Use read_skill(id) when useful:")
            entries.forEach {appendLine("${it.id}: ${it.description}")}
        }.trimEnd()
        require(summary.length<=maxChars){"스킬 목록 ${summary.length}자가 문맥 예산 ${maxChars}자를 넘었습니다. 목록은 잘라서 적용하지 않았습니다."}
        return summary
    }

    /** Query filters metadata for browsing; it is not an intent router or an execution policy. */
    fun listJson(query:String?=null):String {
        require(query==null||query.length<=200){"스킬 검색어가 너무 깁니다."}
        val term=query?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val selected=if(term.isBlank())entries else entries.filter {entry->
            listOf(entry.id,entry.title,entry.description,entry.category).any {term in it.lowercase(Locale.ROOT)}
        }
        return buildJsonObject {
            put("origin","signed_apk_bundle");put("grantsCapabilities",false);put("bodiesLoaded",false)
            put("totalCount",entries.size);put("matchedCount",selected.size)
            put("skills",buildJsonArray {selected.forEach {entry->add(buildJsonObject {
                put("id",entry.id);put("title",entry.title);put("description",entry.description)
                put("category",entry.category);put("version",entry.version)
                put("tools",JsonArray(entry.tools.map(::JsonPrimitive)))
            })}})
        }.toString()
    }

    fun read(id:String):SkillReadResult {
        require(ID.matches(id)&&id.length<=64){"올바른 번들 스킬 ID가 필요합니다."}
        val metadata=byId[id]?:throw IllegalArgumentException("등록되지 않은 번들 스킬입니다.")
        // Only the exact asset from the validated immutable catalog reaches the reader.
        val bytes=reader(metadata.asset).copyOf()
        require(bytes.size in 1..MAX_BODY_BYTES){"번들 스킬 본문 크기가 허용 범위를 벗어났습니다."}
        val actual=sha256(bytes)
        require(actual==metadata.sha256){"번들 스킬 본문 SHA-256이 일치하지 않습니다."}
        val body=strictUtf8(bytes)
        require(body.isNotBlank()&&body.none(::forbiddenControl)){"번들 스킬은 비어 있지 않은 일반 UTF-8 문서여야 합니다."}
        return SkillReadResult(id,body,actual,metadata)
    }

    private fun loadCatalog():List<BundledSkillMetadata> {
        val bytes=reader(CATALOG_ASSET).copyOf()
        require(bytes.size in 1..MAX_CATALOG_BYTES){"번들 스킬 목록 크기가 허용 범위를 벗어났습니다."}
        val text=strictUtf8(bytes)
        rejectDuplicateJsonKeys(text)
        val root=Json.parseToJsonElement(text) as? JsonObject?:error("스킬 목록은 JSON 객체여야 합니다.")
        require(root.keys==setOf("version","skills")){"스킬 목록에 알 수 없거나 누락된 필드가 있습니다."}
        val version=root.getValue("version") as? JsonPrimitive?:error("스킬 목록 버전이 필요합니다.")
        require(!version.isString&&version.content=="1"){"지원하지 않는 번들 스킬 목록 버전입니다."}
        val skills=root.getValue("skills") as? JsonArray?:error("스킬 목록 배열이 필요합니다.")
        require(skills.size in 1..32){"번들 스킬 수가 허용 범위를 벗어났습니다."}
        val result=skills.map {value->
            val item=value as? JsonObject?:error("스킬 항목은 객체여야 합니다.")
            require(item.keys==ENTRY_FIELDS){"스킬 항목에 알 수 없거나 누락된 필드가 있습니다."}
            fun string(key:String,max:Int):String {
                val field=item.getValue(key) as? JsonPrimitive?:error("$key 문자열이 필요합니다.")
                require(field.isString){"$key 문자열이 필요합니다."}
                return field.content.also {require(it.isNotBlank()&&it.length<=max&&it.none {c->c.code<32||c.code==127}){"$key 값이 허용 범위를 벗어났습니다."}}
            }
            fun strings(key:String,maxCount:Int,maxLength:Int):List<String> {
                val array=item.getValue(key) as? JsonArray?:error("$key 문자열 배열이 필요합니다.")
                require(array.size<=maxCount)
                val values=array.map {entry->
                    require(entry is JsonPrimitive&&entry.isString){"$key 배열은 문자열만 허용합니다."}
                    entry.content.also {require(it.isNotBlank()&&it.length<=maxLength&&it.none {c->c.code<32||c.code==127})}
                }
                require(values.distinct().size==values.size){"$key 배열에 중복 항목이 있습니다."}
                return immutable(values)
            }
            val id=string("id",64)
            require(ID.matches(id)){"번들 스킬 ID 형식이 올바르지 않습니다."}
            val asset=string("asset",120)
            require(asset=="skills/$id/SKILL.md"){"번들 스킬은 ID에 해당하는 고정 asset 경로만 사용할 수 있습니다."}
            val hash=string("sha256",64)
            require(HASH.matches(hash)){"번들 스킬 SHA-256 형식이 올바르지 않습니다."}
            val tools=strings("tools",40,100)
            require(tools.all {it in ToolRegistry.definitions}){"번들 스킬이 등록되지 않은 도구를 참조합니다. 미구현 기능은 limitations에 표시해야 합니다."}
            val urls=strings("sourceUrls",12,2048)
            urls.forEach {url->
                val uri=URI(url)
                require(uri.scheme=="https"&&!uri.host.isNullOrBlank()&&uri.userInfo==null&&(uri.port==-1||uri.port==443)){"참고 출처는 자격 정보 없는 HTTPS URL이어야 합니다."}
            }
            BundledSkillMetadata(id,string("title",120),string("description",320),string("category",64),string("version",32),
                asset,hash.lowercase(Locale.ROOT),tools,urls,strings("limitations",12,500))
        }
        require(result.map {it.id}.distinct().size==result.size){"번들 스킬 ID가 중복됐습니다."}
        return immutable(result)
    }

    companion object {
        const val CATALOG_ASSET="skills/catalog.json"
        const val MAX_CATALOG_BYTES=128*1024
        const val MAX_BODY_BYTES=6*1024
        private val ID=Regex("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")
        private val HASH=Regex("[a-fA-F0-9]{64}")
        private val ENTRY_FIELDS=setOf("id","title","description","category","version","asset","sha256","tools","sourceUrls","limitations")
        private fun <T> immutable(values:List<T>):List<T> = Collections.unmodifiableList(values.toList())
        private fun forbiddenControl(c:Char)=c.code==127||(c.code<32&&c!='\t'&&c!='\r'&&c!='\n')
        private fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
        private fun strictUtf8(bytes:ByteArray):String=Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        private fun assetReader(context:Context):(String)->ByteArray = {path->
            val max=if(path==CATALOG_ASSET)MAX_CATALOG_BYTES else MAX_BODY_BYTES
            context.assets.open(path).use {input->
                val output=ByteArrayOutputStream();val buffer=ByteArray(4096)
                while(true){val count=input.read(buffer);if(count<0)break;require(output.size()+count<=max){"번들 스킬 asset이 허용 크기를 넘었습니다."};output.write(buffer,0,count)}
                output.toByteArray()
            }
        }

        /** JSON's normal decoder accepts duplicate names. Reject them before constructing metadata. */
        private fun rejectDuplicateJsonKeys(raw:String) {
            // Bound depth before the full JSON decoder; the caller then validates all JSON syntax.
            var at=0;var nodes=0
            fun space(){while(at<raw.length&&raw[at].isWhitespace())at++}
            fun string():String {
                val start=at;require(raw.getOrNull(at)=='"');at++;var escaped=false
                while(at<raw.length){val c=raw[at++];if(c=='"'&&!escaped)return Json.parseToJsonElement(raw.substring(start,at)).jsonPrimitive.content;if(c=='\\'&&!escaped)escaped=true else escaped=false}
                error("닫히지 않은 JSON 문자열입니다.")
            }
            lateinit var value:(Int)->Unit
            value={depth->
                require(depth<=12&&++nodes<=4096){"번들 스킬 JSON 중첩 또는 항목 수가 너무 큽니다."};space()
                when(raw.getOrNull(at)){
                    '{'->{at++;space();val keys=mutableSetOf<String>();if(raw.getOrNull(at)=='}')at++ else while(true){space();val key=string();require(keys.add(key)){"중복 JSON 필드는 허용하지 않습니다."};space();require(raw.getOrNull(at++)==':');value(depth+1);space();val separator=raw.getOrNull(at++);if(separator=='}')break;require(separator==',')}}
                    '['->{at++;space();if(raw.getOrNull(at)==']')at++ else while(true){value(depth+1);space();val separator=raw.getOrNull(at++);if(separator==']')break;require(separator==',')}}
                    '"'->{string();Unit}
                    else->{val start=at;while(at<raw.length&&raw[at] !in ",]} \t\r\n")at++;require(at>start)}
                }
            }
            value(0);space();require(at==raw.length)
        }
    }
}
