package com.user.localagent

import com.user.localagent.data.ChatBranchRecord
import com.user.localagent.data.ChatMessagePage
import com.user.localagent.data.ChatThreadRecord
import com.user.localagent.ui.UiChatExportFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.io.Writer

internal data class CorpusMessage(val id:String,val role:String,val createdAt:Long)
internal data class CorpusBranch(val id:String,val title:String,val parentBranchId:String?,val createdAt:Long,val messageIds:List<String>)
internal data class ChatCorpusIndex(val threadId:String,val title:String,val activeBranchId:String,val branches:List<CorpusBranch>,val messages:List<CorpusMessage>)

/** Page bodies are transient; only unique IDs/metadata and ordered branch references are retained. */
internal suspend fun collectChatCorpus(
    thread:ChatThreadRecord,branches:List<ChatBranchRecord>,
    page:suspend(String,Long?)->ChatMessagePage,
):ChatCorpusIndex {
    val messages=linkedMapOf<String,CorpusMessage>()
    val versions=branches.filter{it.deletedAt==null}.sortedWith(compareBy<ChatBranchRecord>{it.createdAt}.thenBy{it.id}).map {branch->
        require(branch.threadId==thread.id)
        val newestFirst=mutableListOf<String>();val seen=hashSetOf<String>();val cursors=hashSetOf<Long>()
        var before:Long?=null
        do {
            currentCoroutineContext().ensureActive()
            val found=page(branch.id,before)
            found.messages.forEach {message->
                require(message.threadId==thread.id){"다른 대화의 메시지를 내보내지 않습니다."}
                require(seen.add(message.id)){"대화 버전의 페이지가 중복되었습니다."}
                newestFirst+=message.id
                messages.putIfAbsent(message.id,CorpusMessage(message.id,message.role,message.createdAt))
            }
            before=found.nextPosition
            if(before!=null)check(cursors.add(before!!)){"대화 페이지 커서가 진행하지 않았습니다."}
        }while(before!=null)
        CorpusBranch(branch.id,branch.title,branch.parentBranchId,branch.createdAt,newestFirst.asReversed().toList())
    }
    check(versions.any{it.id==thread.activeBranchId}){"현재 대화 버전을 찾지 못했습니다."}
    return ChatCorpusIndex(thread.id,thread.title,thread.activeBranchId,versions,messages.values.sortedWith(compareBy<CorpusMessage>{it.createdAt}.thenBy{it.id}))
}

/** Every live version participates; search text and results remain local to the owner's navigator. */
internal suspend fun searchChatCorpus(
    threads:List<ChatThreadRecord>,query:String,
    branches:suspend(String)->List<ChatBranchRecord>,page:suspend(String,String,Long?)->ChatMessagePage,
):Set<String> {
    val term=query.trim();require(term.length<=1000){"검색어는 1,000자 이내로 입력해주세요."}
    if(term.isEmpty())return threads.filter{it.deletedAt==null}.mapTo(linkedSetOf()){it.id}
    val result=linkedSetOf<String>()
    for(thread in threads.filter{it.deletedAt==null}){
        currentCoroutineContext().ensureActive()
        if(thread.title.contains(term,true)){result+=thread.id;continue}
        val seen=hashSetOf<String>();var matched=false
        for(branch in branches(thread.id).filter{it.deletedAt==null}){
            require(branch.threadId==thread.id)
            var before:Long?=null;val cursors=hashSetOf<Long>()
            do {
                currentCoroutineContext().ensureActive()
                val found=page(thread.id,branch.id,before)
                for(message in found.messages){
                    require(message.threadId==thread.id)
                    if(seen.add(message.id)&&message.text.contains(term,true)){matched=true;break}
                }
                if(matched)break
                before=found.nextPosition
                if(before!=null)check(cursors.add(before!!)){"검색 페이지 커서가 진행하지 않았습니다."}
            }while(before!=null)
            if(matched)break
        }
        if(matched)result+=thread.id
    }
    return result
}

/** Shared-prefix messages appear once; each version keeps its exact ordered ID references. */
internal suspend fun writeChatCorpus(
    corpus:ChatCorpusIndex,format:UiChatExportFormat,exportedAt:String,writer:Writer,text:suspend(String)->String,
) {
    fun json(value:String)=JsonPrimitive(value).toString()
    if(format==UiChatExportFormat.JSON){
        writer.append("{\"version\":2,\"exportedAt\":").append(json(exportedAt)).append(",\"threadId\":").append(json(corpus.threadId))
            .append(",\"title\":").append(json(corpus.title)).append(",\"activeBranchId\":").append(json(corpus.activeBranchId))
            .append(",\"scope\":\"all_live_branches\",\"branches\":[")
        corpus.branches.forEachIndexed {index,branch->
            if(index>0)writer.append(',')
            writer.append(buildJsonObject {
                put("id",branch.id);put("title",branch.title);put("parentBranchId",branch.parentBranchId)
                put("createdAt",branch.createdAt);put("messageIds",JsonArray(branch.messageIds.map(::JsonPrimitive)))
            }.toString())
        }
        writer.append("],\"messages\":[")
        corpus.messages.forEachIndexed {index,message->
            currentCoroutineContext().ensureActive();if(index>0)writer.append(',')
            writer.append("{\"id\":").append(json(message.id)).append(",\"role\":").append(json(message.role))
                .append(",\"createdAt\":").append(message.createdAt.toString()).append(",\"text\":").append(json(text(message.id))).append('}')
        }
        writer.append("]}");return
    }
    val markdown=format==UiChatExportFormat.MARKDOWN
    val numbers=corpus.messages.mapIndexed {index,message->message.id to index+1}.toMap()
    writer.append(if(markdown)"# "else"").append(corpus.title.replace('\n',' ')).append("\n\n")
    writer.append("전체 대화 버전 내보내기 · ").append(exportedAt).append("\n")
    writer.append("공유 원문은 한 번만 수록합니다. 아래 버전별 메시지 번호는 원문의 정확한 순서입니다. 첨부 원본은 포함하지 않습니다.\n\n")
    for((index,branch)in corpus.branches.withIndex()){
        writer.append(if(markdown)"## "else"").append("버전 ").append((index+1).toString()).append(" · ").append(branch.title.replace('\n',' '))
            .append(if(branch.id==corpus.activeBranchId)" [현재]"else"").append("\nID: ").append(branch.id).append('\n')
        if(branch.messageIds.isEmpty())writer.append("메시지 없음\n")
        else branch.messageIds.forEach {id->
            val number=checkNotNull(numbers[id]){"버전이 참조하는 원문이 없습니다."}
            writer.append(if(markdown)"- [메시지 $number](#message-$number)"else"- 메시지 $number").append(" · ").append(id).append('\n')
        }
        writer.append('\n')
    }
    writer.append(if(markdown)"## 전체 원문\n\n"else"전체 원문\n\n")
    for((index,message)in corpus.messages.withIndex()){
        currentCoroutineContext().ensureActive();val number=index+1
        if(markdown)writer.append("<a id=\"message-$number\"></a>\n### ")
        writer.append("메시지 $number · ").append(if(message.role=="user")"사용자"else"집순이")
            .append("\nID: ").append(message.id).append("\n\n").append(text(message.id)).append("\n\n")
    }
}
