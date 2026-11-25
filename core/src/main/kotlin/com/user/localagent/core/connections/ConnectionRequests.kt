package com.user.localagent.core.connections

import com.user.localagent.core.ToolCall
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlinx.serialization.json.*

data class ApiRequest(val url:String,val method:String="GET",val body:String?=null,val contentType:String="application/json; charset=utf-8",val extraHeaders:Map<String,String> = emptyMap()) {
    init {
        val uri=URI(url)
        require(uri.scheme=="https" && uri.userInfo==null && uri.port in listOf(-1,443) && uri.fragment==null)
        require(uri.host in hosts && uri.rawPath.none {it=='\\'})
        require(method in setOf("GET","POST","PUT","PATCH"))
        require(body==null || body.toByteArray().size<=100_000)
        require(extraHeaders.keys.all {it in setOf("Notion-Version","If-None-Match")})
        require(extraHeaders.values.none {it.any {c->c=='\r'||c=='\n'}})
    }
    companion object {val hosts=setOf("www.googleapis.com","docs.googleapis.com","sheets.googleapis.com","slides.googleapis.com","gmail.googleapis.com","forms.googleapis.com","tasks.googleapis.com","people.googleapis.com","photospicker.googleapis.com","api.telegram.org","api.notion.com","api.todoist.com","api.github.com","slack.com","graph.microsoft.com")}
}

data class ResponseProof(val verified:Boolean,val summary:String)
data class ApiPlan(val service:ConnectedService,val writes:Boolean,val first:ApiRequest,
    val createdIdField:String?=null,val knownId:String?=null,
    val populate:((String)->ApiRequest)?=null,val readback:((String)->ApiRequest)?=null,
    val verify:((String)->ResponseProof)?=null)

object ConnectionRequests {
    fun encode(text:String)=URLEncoder.encode(text,StandardCharsets.UTF_8).replace("+","%20")
    fun key(text:String):String {require(text.matches(Regex("[A-Za-z0-9@][A-Za-z0-9_.:@+~=-]{0,249}")) && text !in setOf(".",".."));return encode(text)}
    private fun ToolCall.s(key:String,default:String="")=arguments[key]?.jsonPrimitive?.content?:default
    private fun obj(vararg fields:Pair<String,JsonElement>)=JsonObject(mapOf(*fields)).toString()
    private fun str(value:String)=JsonPrimitive(value)
    private fun titleProof(body:String,title:String,idField:String)=ResponseProof(
        runCatching {val data=Json.parseToJsonElement(body).jsonObject;data[idField]?.jsonPrimitive?.content?.isNotBlank()==true && (data["title"]?.jsonPrimitive?.content?:data["properties"]?.jsonObject?.get("title")?.jsonPrimitive?.content?:data["info"]?.jsonObject?.get("title")?.jsonPrimitive?.content)==title}.getOrDefault(false),"제목과 원격 자료 ID 재조회")
    fun strings(element:JsonElement,key:String):List<String> = when(element) {
        is JsonObject->element.flatMap {(k,v)->if(k==key&&v is JsonPrimitive&&v.isString)listOf(v.content)else strings(v,key)}
        is JsonArray->element.flatMap {strings(it,key)}
        else->emptyList()
    }
    private fun textProof(body:String,expected:String)=ResponseProof(runCatching {strings(Json.parseToJsonElement(body),"content").joinToString("").contains(expected)}.getOrDefault(false),"원격 문서 본문 재조회·내용 대조")
    private fun rows(raw:String):JsonArray {
        val values=requireNotNull(Json.parseToJsonElement(raw) as? JsonArray){"rowsJson 배열 필요"}
        require(values.size in 1..100)
        values.forEach {row->require(row is JsonArray && row.size in 1..30 && row.all {it is JsonPrimitive && it.isString && it.content.length<=1000})}
        return values
    }
    private fun pageToken(token:String)=if(token.isEmpty())"" else "&pageToken="+encode(token)
    private fun normalizeRows(values:JsonArray):List<List<String>> = values.map {row->row.jsonArray.map {it.jsonPrimitive.content}.dropLastWhile {it.isEmpty()}}.dropLastWhile {it.isEmpty()}
    fun build(call:ToolCall):ApiPlan {
        val spec=ConnectionCatalog.tools[call.name]?:error("등록되지 않은 연결 도구")
        require(call.arguments.keys.all {it in spec.definition.arguments})
        spec.definition.arguments.forEach {(name,arg)->val value=call.arguments[name];require(!arg.required || value!=null);if(value!=null)require(value is JsonPrimitive&&value.isString&&value.content.isNotBlank()&&value.content.length<=arg.maxLength&&'\u0000' !in value.content)}
        val service=spec.service
        fun read(url:String,method:String="GET",body:String?=null,headers:Map<String,String> = emptyMap())=ApiPlan(service,false,ApiRequest(url,method,body,extraHeaders=headers))
        val title=call.s("title");val content=call.s("content");val id=call.s("id").takeIf {it.isNotBlank()}?.let(::key)
        return when(call.name) {
            "send_gmail_message","reply_gmail_message"->GmailSendContract.plan(call)
            "search_drive_files"->{val q=call.s("query").replace("\\","\\\\").replace("'","\\'");read("https://www.googleapis.com/drive/v3/files?pageSize=20&fields=files(id,name,mimeType,modifiedTime,size),nextPageToken&q="+encode("trashed=false and name contains '$q'"))}
            "read_drive_text_file"->read("https://www.googleapis.com/drive/v3/files/$id?alt=media")
            "create_drive_text_file"->{val boundary="ziphap_"+java.util.UUID.randomUUID().toString().replace("-","")
                val metadata=obj("name" to str(title),"mimeType" to str("text/plain"))
                val multipart="--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n--$boundary\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n$content\r\n--$boundary--\r\n"
                ApiPlan(service,true,ApiRequest("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id","POST",multipart,"multipart/related; boundary=$boundary"),"id",readback={ApiRequest("https://www.googleapis.com/drive/v3/files/${key(it)}?alt=media")},verify={ResponseProof(it==content,"UTF-8 파일 원문 재조회·정확 일치")})}
            "read_google_document"->read("https://docs.googleapis.com/v1/documents/$id?includeTabsContent=true")
            "create_google_document"->ApiPlan(service,true,ApiRequest("https://docs.googleapis.com/v1/documents","POST",obj("title" to str(title))),"documentId",
                populate={ApiRequest("https://docs.googleapis.com/v1/documents/${key(it)}:batchUpdate","POST",buildJsonObject {put("requests",buildJsonArray {add(buildJsonObject {put("insertText",buildJsonObject {put("location",buildJsonObject {put("index",1)});put("text",content)})})})}.toString())},
                readback={ApiRequest("https://docs.googleapis.com/v1/documents/${key(it)}?includeTabsContent=true")},verify={textProof(it,content)})
            "read_google_sheet"->{val range=call.s("range","Sheet1!A1:AD100");require(range.length<=150 && range.none {it in "\r\n"});read("https://sheets.googleapis.com/v4/spreadsheets/$id/values/${encode(range)}")}
            "create_google_sheet"->{val values=rows(call.s("rowsJson"));ApiPlan(service,true,ApiRequest("https://sheets.googleapis.com/v4/spreadsheets","POST",buildJsonObject {put("properties",buildJsonObject {put("title",title)});put("sheets",buildJsonArray {add(buildJsonObject {put("properties",buildJsonObject {put("title","Sheet1")})})})}.toString()),"spreadsheetId",
                populate={ApiRequest("https://sheets.googleapis.com/v4/spreadsheets/${key(it)}/values/Sheet1!A1?valueInputOption=RAW","PUT",obj("values" to values))},
                readback={ApiRequest("https://sheets.googleapis.com/v4/spreadsheets/${key(it)}/values/Sheet1!A1:AD100")},verify={body->ResponseProof(runCatching {normalizeRows(Json.parseToJsonElement(body).jsonObject["values"]?.jsonArray?:JsonArray(emptyList()))==normalizeRows(values)}.getOrDefault(false),"원격 셀 값 재조회·정확 일치")})}
            "read_google_presentation"->read("https://slides.googleapis.com/v1/presentations/$id")
            "create_google_presentation"->{val text=title+"\n"+content;ApiPlan(service,true,ApiRequest("https://slides.googleapis.com/v1/presentations","POST",obj("title" to str(title))),"presentationId",
                populate={ApiRequest("https://slides.googleapis.com/v1/presentations/${key(it)}:batchUpdate","POST",buildJsonObject {put("requests",buildJsonArray {
                    add(buildJsonObject {put("createSlide",buildJsonObject {put("objectId","ziphap_page");put("slideLayoutReference",buildJsonObject {put("predefinedLayout","BLANK")})})})
                    add(buildJsonObject {put("createShape",buildJsonObject {put("objectId","ziphap_body");put("shapeType","TEXT_BOX");put("elementProperties",buildJsonObject {put("pageObjectId","ziphap_page");put("size",buildJsonObject {put("width",buildJsonObject {put("magnitude",600);put("unit","PT")});put("height",buildJsonObject {put("magnitude",300);put("unit","PT")})});put("transform",buildJsonObject {put("scaleX",1);put("scaleY",1);put("translateX",40);put("translateY",40);put("unit","PT")})})})})
                    add(buildJsonObject {put("insertText",buildJsonObject {put("objectId","ziphap_body");put("text",text)})})
                })}.toString())},readback={ApiRequest("https://slides.googleapis.com/v1/presentations/${key(it)}")},verify={textProof(it,text)})}
            "search_gmail_messages"->read("https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=10&q="+encode(call.s("query")))
            "read_gmail_message"->read("https://gmail.googleapis.com/gmail/v1/users/me/messages/$id?format=full")
            "create_gmail_draft"->{val to=call.s("to");val subject=call.s("subject");require(to.matches(Regex("[^\\s@,;<>]+@[^\\s@,;<>]+\\.[^\\s@,;<>]+")) && listOf(to,subject).none {it.any {c->c=='\r'||c=='\n'}})
                val encodedSubject=Base64.getEncoder().encodeToString(subject.toByteArray(StandardCharsets.UTF_8))
                val mime="To: $to\r\nSubject: =?UTF-8?B?$encodedSubject?=\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n"+Base64.getMimeEncoder().encodeToString(call.s("body").toByteArray(StandardCharsets.UTF_8))
                val raw=Base64.getUrlEncoder().withoutPadding().encodeToString(mime.toByteArray(StandardCharsets.UTF_8))
                ApiPlan(service,true,ApiRequest("https://gmail.googleapis.com/gmail/v1/users/me/drafts","POST",obj("message" to buildJsonObject {put("raw",raw)})),"id",readback={ApiRequest("https://gmail.googleapis.com/gmail/v1/users/me/drafts/${key(it)}?format=raw")},verify={body->ResponseProof(runCatching {Base64.getUrlDecoder().decode(Json.parseToJsonElement(body).jsonObject.getValue("message").jsonObject.getValue("raw").jsonPrimitive.content).contentEquals(mime.toByteArray(StandardCharsets.UTF_8))}.getOrDefault(false),"메일 초안 원문 재조회·일치, 미발송")})}
            "list_google_calendar_events","create_google_calendar_event"->{val start=OffsetDateTime.parse(call.s("start"));val end=OffsetDateTime.parse(call.s("end"));require(end.isAfter(start));val calendar=key(call.s("calendarId","primary"));val base="https://www.googleapis.com/calendar/v3/calendars/$calendar/events"
                // Google Calendar requires seconds even for exact-minute bounds.
                val startTimestamp=start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                val endTimestamp=end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                if(!spec.writes)read("$base?singleEvents=true&maxResults=20&timeMin=${encode(startTimestamp)}&timeMax=${encode(endTimestamp)}")else {
                    val eventId=java.security.MessageDigest.getInstance("SHA-256").digest(call.id.toByteArray()).joinToString(""){"%02x".format(it)}
                    ApiPlan(service,true,ApiRequest(base,"POST",buildJsonObject {put("id",eventId);put("summary",title);put("start",buildJsonObject {put("dateTime",startTimestamp)});put("end",buildJsonObject {put("dateTime",endTimestamp)})}.toString()),createdIdField="id",knownId=eventId,readback={ApiRequest("$base/${key(it)}")},verify={body->CalendarTargetIntegrity.readback(eventId,title,start,end,body)})}}
            "list_google_tasks"->read("https://tasks.googleapis.com/tasks/v1/lists/@default/tasks?maxResults=20&showCompleted=false")
            "create_google_task"->{val body=buildJsonObject {put("title",title);if(call.s("notes").isNotEmpty())put("notes",call.s("notes"));if(call.s("dueDate").isNotEmpty())put("due",LocalDate.parse(call.s("dueDate")).toString()+"T00:00:00.000Z")}.toString()
                ApiPlan(service,true,ApiRequest("https://tasks.googleapis.com/tasks/v1/lists/@default/tasks","POST",body),"id",readback={ApiRequest("https://tasks.googleapis.com/tasks/v1/lists/@default/tasks/${key(it)}")},verify={titleProof(it,title,"id")})}
            "complete_google_task"->ApiPlan(service,true,ApiRequest("https://tasks.googleapis.com/tasks/v1/lists/@default/tasks/$id","PATCH","{\"status\":\"completed\"}"),knownId=call.s("id"),readback={ApiRequest("https://tasks.googleapis.com/tasks/v1/lists/@default/tasks/${key(it)}")},verify={body->ResponseProof(runCatching {Json.parseToJsonElement(body).jsonObject["status"]?.jsonPrimitive?.content=="completed"}.getOrDefault(false),"할 일 완료 상태 재조회")})
            "search_google_contacts"->read("https://people.googleapis.com/v1/people:searchContacts?readMask=names,emailAddresses,phoneNumbers&pageSize=20&query="+encode(call.s("query")))
            "read_google_form"->read("https://forms.googleapis.com/v1/forms/$id")
            "list_google_form_responses"->read("https://forms.googleapis.com/v1/forms/$id/responses?pageSize=20")
            "create_google_form"->{val questions=Json.parseToJsonElement(call.s("questionsJson")) as? JsonArray?:error("질문 배열 필요");require(questions.size in 1..10&&questions.all {it is JsonPrimitive&&it.isString&&it.content.isNotBlank()&&it.content.length<=300})
                ApiPlan(service,true,ApiRequest("https://forms.googleapis.com/v1/forms","POST",obj("info" to buildJsonObject {put("title",title)})),"formId",populate={ApiRequest("https://forms.googleapis.com/v1/forms/${key(it)}:batchUpdate","POST",buildJsonObject {put("requests",buildJsonArray {questions.forEachIndexed {index,q->add(buildJsonObject {put("createItem",buildJsonObject {put("location",buildJsonObject {put("index",index)});put("item",buildJsonObject {put("title",q);put("questionItem",buildJsonObject {put("question",buildJsonObject {put("required",false);put("textQuestion",buildJsonObject {})})})})})})}})}.toString())},readback={ApiRequest("https://forms.googleapis.com/v1/forms/${key(it)}")},verify={body->ResponseProof(runCatching {val data=Json.parseToJsonElement(body).jsonObject;data.getValue("info").jsonObject["title"]?.jsonPrimitive?.content==title&&data.getValue("items").jsonArray.map {it.jsonObject.getValue("title").jsonPrimitive.content}==questions.map {it.jsonPrimitive.content}}.getOrDefault(false),"설문 제목·질문 순서 재조회")})}
            "search_youtube_videos"->read("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=10&q="+encode(call.s("query")))
            "list_youtube_playlists"->{
                val playlist=call.s("playlistId");val token=pageToken(call.s("pageToken"))
                if(playlist.isEmpty())read("https://www.googleapis.com/youtube/v3/playlists?part=snippet&mine=true&maxResults=20"+token)
                else {
                    require(playlist.matches(Regex("[A-Za-z0-9_-]{1,128}")))
                    val fields=encode("nextPageToken,items(id,snippet(title,position,resourceId/videoId))")
                    read("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&playlistId=$playlist&maxResults=20&fields=$fields"+token)
                }
            }
            "list_telegram_bot_updates"->read("https://api.telegram.org/getUpdates") // token path is bound only by the host, never model arguments
            "list_outlook_inbox"->read("https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages?\$select=id,subject,from,receivedDateTime&\$orderby=receivedDateTime%20desc&\$top=20")
            "list_outlook_calendar"->{
                val start=OffsetDateTime.parse(call.s("start")); val end=OffsetDateTime.parse(call.s("end"))
                require(end.isAfter(start)){"조회 종료 시각은 시작 시각 뒤여야 해요."}
                val range="startDateTime="+encode(start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))+
                    "&endDateTime="+encode(end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                read("https://graph.microsoft.com/v1.0/me/calendar/calendarView?$range&\$select=id,subject,start,end&\$top=20")
            }
            "list_github_repository_issues"->{
                val repository=call.s("repository")
                require(repository.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}")) &&
                    repository.substringAfter('/') !in setOf(".","..")){"저장소를 소유자/이름으로 입력해주세요."}
                read("https://api.github.com/repos/$repository/issues?state=open&sort=updated&direction=desc&per_page=20")
            }
            "read_slack_channel_history"->{
                val channel=call.s("channel")
                require(channel.matches(Regex("[CG][A-Z0-9]{1,31}"))){"Slack 채널 ID를 확인해주세요."}
                read("https://slack.com/api/conversations.history?channel=$channel&limit=15")
            }
            "search_notion_pages"->{
                val query=call.s("query");val blockId=call.s("blockId");val offset=call.s("offset");val cursor=call.s("cursor")
                require(query.isNotEmpty() xor blockId.isNotEmpty()){"검색어 또는 하나의 페이지·블록 ID가 필요해요."}
                if(query.isNotEmpty()){
                    require(offset.isEmpty())
                    read("https://api.notion.com/v1/search","POST",buildJsonObject {
                        put("query",query);put("page_size",20)
                        if(cursor.isNotEmpty())put("start_cursor",cursor)
                    }.toString(),NotionBlocks.headers)
                }else{
                    val block=NotionBlocks.blockId(blockId)
                    if(offset.isNotEmpty()){
                        require(cursor.isEmpty());NotionBlocks.offset(offset)
                        read("https://api.notion.com/v1/blocks/$block",headers=NotionBlocks.headers)
                    }else{
                        val start=if(cursor.isEmpty())"" else "&start_cursor="+encode(cursor)
                        read("https://api.notion.com/v1/blocks/$block/children?page_size=8$start",headers=NotionBlocks.headers)
                    }
                }
            }
            "list_todoist_tasks"->read("https://api.todoist.com/api/v1/tasks?limit=20")
            else->error("연결 구현이 없습니다.")
        }
    }
}
