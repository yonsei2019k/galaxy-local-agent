package com.user.localagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.core.content.FileProvider
import com.user.localagent.data.ProjectSettings
import com.user.localagent.data.MessageRecord
import com.user.localagent.workspace.AttachmentHistoryCopyResult
import com.user.localagent.ui.UiChatExportFormat
import com.user.localagent.ui.UiProject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

private val chatManagementMutations=Mutex()

/** Local owner navigation search; does not send text to models, connectors or public search. */
suspend fun ChatRunCoordinator.searchThreads(query:String):Set<String> = withContext(Dispatchers.IO) {
    val chat=app.repository.chat
    val live=chat.threads.first().filter{it.deletedAt==null}
    val found=searchChatCorpus(live,query,{chat.branches(it)}){thread,branch,before->chat.messagePage(thread,branch,before,200)}
    // A room deleted during a long local scan must not reappear in the navigator.
    val stillLive=chat.threads.first().mapTo(hashSetOf()){it.id}
    found.intersect(stillLive)
}

private fun ChatRunCoordinator.manage(action: suspend ChatRunCoordinator.() -> Unit) {
    scope.launch {
        chatManagementMutations.withLock {
        try { action() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { status.value = "변경을 확인하지 못했습니다. 대화 목록과 설정을 다시 확인해주세요." }
        }
    }
}

/** Cancel the target scope, including durable queued rows, before changing its revision. */
private suspend fun ChatRunCoordinator.quiesceThread(id: String) {
    val running = runningJobForThread(id)
    cancelThread(id)
    app.repository.chat.cancelRuns(id, "owner_scope_change")
    running?.join()
}

fun ChatRunCoordinator.selectProject(id: String) = manage {
    val projectId = id.takeIf(String::isNotBlank)
    if (projectId != null) check(app.repository.chat.project(projectId)?.archived == false)
    val selected = app.repository.chat.threads.first()
        .firstOrNull { it.projectId == projectId && !it.archived }
        ?: app.repository.chat.createThread(projectId)
    selectThread(selected.id)
}

fun ChatRunCoordinator.saveProject(project: UiProject) = manage {
    val settings = ProjectSettings(
        modelId = project.modelId, thinking = project.thinking, contextSize = project.contextSize,
        instructions = project.instructions, memoryMode = project.memoryMode,
        useOwnerMemory = project.useOwnerMemory, learnOwnerFacts = project.learnOwnerFacts,
        disabledTools = project.disabledTools,
    )
    val saved = if (project.id.isBlank()) app.repository.chat.createProject(project.name, settings) else {
        val previous = app.repository.chat.project(project.id) ?: error("프로젝트 없음")
        check(previous.revision == project.revision) { "설정이 변경됨" }
        if(previous.settings!=settings)app.repository.chat.threads.first().filter { it.projectId == project.id }.forEach { quiesceThread(it.id) }
        app.repository.chat.updateProject(project.id, project.name, settings, expectedRevision = project.revision)
    }
    val readback = app.repository.chat.project(saved.id)
    check(readback?.title == project.name.trim() && readback.settings == settings)
    status.value = "프로젝트 설정을 저장하고 다시 확인했습니다."
    if (project.id.isBlank()) selectProject(saved.id)
    else if (selectedProjectId.value == saved.id) selectThread(selectedThreadId.value)
}

fun ChatRunCoordinator.deleteProject(id: String, deleteDerivedMemory: Boolean) = manage {
    val project = app.repository.chat.project(id) ?: error("프로젝트 없음")
    // The legacy project shares the general "default" workspace; only a project's own folder is discarded.
    val ownWorkspace = project.workspaceKey !in setOf("default", "personal") &&
        app.repository.chat.projects.first().none { it.id != id && it.workspaceKey == project.workspaceKey }
    val workspace = if (ownWorkspace) app.workspaceFor(id) else null
    val rooms = app.repository.chat.threads.first().filter { it.projectId == id }
    val selectedHere = rooms.any { it.id == selectedThreadId.value } || selectedProjectId.value == id
    rooms.forEach { quiesceThread(it.id); goals.delete(it.id) }
    val result = app.scheduler.withMutation {
        val reminders=app.repository.reminderIdsForProjectRemoval(id)
        reminders.forEach {app.scheduler.cancel(it)}
        app.repository.chat.deleteProject(id, deleteDerivedMemory).also {reminders.forEach {reminderId->app.scheduler.cancel(reminderId)}}
    }
    rooms.forEach { app.chatAttachments.clearThread(it.id) }
    check(app.repository.chat.project(id) == null)
    check(app.repository.chat.threads.first().none { it.projectId == id })
    workspace?.discard()
    if (selectedHere) {
        val next = app.repository.chat.threads.first().firstOrNull { !it.archived } ?: app.repository.chat.createThread(null)
        selectThread(next.id)
    }
    status.value = "프로젝트와 대화 ${result.threadsDeleted}개를 영구 삭제하고 확인했습니다. 연결한 휴대전화 폴더의 파일은 유지됩니다."
}

fun ChatRunCoordinator.renameThread(id: String, title: String) = manage {
    app.repository.chat.renameThread(id, title)
    check(app.repository.chat.thread(id)?.title == title.trim())
    status.value = "대화 이름을 저장하고 확인했습니다."
}

fun ChatRunCoordinator.openFixedOfficeRooms() = manage {
    val room=app.repository.chat.openFixedOfficeRooms()
    selectThread(room.id)
}

fun ChatRunCoordinator.pinThread(id: String, pinned: Boolean) = manage {
    app.repository.chat.pinThread(id, pinned)
    check(app.repository.chat.thread(id)?.pinned == pinned)
    status.value = if (pinned) "대화를 고정했습니다." else "대화 고정을 해제했습니다."
}

fun ChatRunCoordinator.archiveThread(id: String, archived: Boolean) = manage {
    app.repository.chat.archiveThread(id, archived)
    check(app.repository.chat.thread(id)?.archived == archived)
    status.value = if (archived) "대화를 보관함으로 옮겼습니다." else "대화를 보관함에서 꺼냈습니다."
}

fun ChatRunCoordinator.clearThread(id: String, deleteDerivedMemory: Boolean) = manage {
    quiesceThread(id)
    goals.delete(id)
    val result = app.repository.chat.clearThread(id, deleteDerivedMemory)
    app.chatAttachments.clearThread(id)
    val readback = app.repository.chat.thread(id) ?: error("대화 없음")
    check(app.repository.chat.messages(id, readback.activeBranchId).first().isEmpty())
    check(app.repository.chat.readDraft(id) == null)
    if (selectedThreadId.value == id) selectThread(id)
    status.value = "메시지 ${result.messagesDeleted}개를 삭제하고 빈 대화를 확인했습니다."
}

fun ChatRunCoordinator.deleteThread(id: String, deleteDerivedMemory: Boolean) = manage {
    val prior = app.repository.chat.thread(id) ?: error("대화 없음")
    quiesceThread(id)
    goals.delete(id)
    app.scheduler.withMutation {
        val reminders=app.repository.reminderIdsForThreadRemoval(id)
        reminders.forEach {app.scheduler.cancel(it)}
        app.repository.chat.deleteThread(id, deleteDerivedMemory)
        reminders.forEach {app.scheduler.cancel(it)}
    }
    app.chatAttachments.clearThread(id)
    check(app.repository.chat.thread(id) == null)
    if (selectedThreadId.value == id) {
        val next = app.repository.chat.threads.first().firstOrNull { !it.archived && it.projectId == prior.projectId }
            ?: app.repository.chat.createThread(prior.projectId)
        selectThread(next.id)
    }
    status.value = "대화 영구 삭제를 확인했습니다. 외부 프로젝트 파일은 유지됩니다."
}

fun ChatRunCoordinator.duplicateThread(id: String) = manage {
    val source=app.repository.chat.thread(id)?:error("대화 없음")
    val originals=app.repository.chat.messages(id,source.activeBranchId).first()
    val duplicate = app.repository.chat.duplicateThread(id)
    retainCopiedAttachments(id,duplicate.id,originals)
    // A whole-room copy retains even older unassociated files; a prefix fork never guesses.
    app.chatAttachments.duplicateThread(id,duplicate.id)
    check(app.repository.chat.thread(duplicate.id)?.id == duplicate.id)
    selectThread(duplicate.id)
    status.value = "대화 원문을 복제했습니다. 기존 작업을 다시 실행하지 않습니다."
}

fun ChatRunCoordinator.moveThread(id: String, projectId: String) = manage {
    quiesceThread(id)
    val target = projectId.takeIf(String::isNotBlank)
    goals.moveThread(id, target)
    check(app.repository.chat.thread(id)?.projectId == target)
    if (selectedThreadId.value == id) selectThread(id)
    status.value = "대화를 이동했습니다. 목표 내용은 유지하고 새 프로젝트에서 완료 기준을 다시 확인합니다. 기존 프로젝트의 공유 기억은 유지됩니다."
}

fun ChatRunCoordinator.selectBranch(threadId:String,branchId:String)=manage {
    val thread=app.repository.chat.thread(threadId)?:error("대화 없음")
    check(app.repository.chat.branches(threadId).any{it.id==branchId}){"다른 대화의 버전입니다."}
    if(thread.activeBranchId==branchId)return@manage
    quiesceThread(threadId)
    app.repository.chat.selectBranch(threadId,branchId)
    check(app.repository.chat.thread(threadId)?.activeBranchId==branchId)
    if(selectedThreadId.value==threadId)selectThread(threadId)
    status.value="대화 버전 선택을 확인했습니다. 기존 기록은 유지됩니다."
}

fun ChatRunCoordinator.forkMessage(messageId: String) = manage {
    val source = app.repository.getMessage(messageId) ?: error("메시지 없음")
    val thread=app.repository.chat.thread(source.threadId)?:error("대화 없음")
    val originals=app.repository.chat.messages(thread.id,thread.activeBranchId).first()
    val position=originals.indexOfFirst{it.id==messageId};check(position>=0)
    val fork = app.repository.chat.forkThread(source.threadId, messageId)
    val copied = app.repository.chat.messages(fork.id, fork.activeBranchId).first()
    check(copied.lastOrNull()?.originMessageId == (source.originMessageId ?: source.id))
    val retained=retainCopiedAttachments(source.threadId,fork.id,originals.take(position+1))
    selectThread(fork.id)
    status.value = "이 메시지까지 새 대화로 분기했습니다. 원래 대화는 유지됩니다."+attachmentCopyWarning(retained)
}

/** Immutable origin IDs keep file ownership attached to the copied prefix, including copied copies. */
private suspend fun ChatRunCoordinator.retainCopiedAttachments(sourceThreadId:String,targetThreadId:String,originals:List<MessageRecord>):AttachmentHistoryCopyResult {
    val target=app.repository.chat.thread(targetThreadId)?:error("복제한 대화가 없습니다.")
    val copied=app.repository.chat.messages(target.id,target.activeBranchId).first()
    check(originals.size==copied.size){"복제 중 대화 분기가 변경되었습니다."}
    val identities=originals.zip(copied).associate{(source,copy)->
        check(copy.originMessageId==(source.originMessageId?:source.id)){"복제된 원문 연결을 확인하지 못했습니다."}
        source.id to copy.id
    }
    val legacy=app.repository.chat.runsForThread(sourceThreadId).mapNotNull{run->
        val user=run.userMessageId?:return@mapNotNull null
        val request=runCatching{QueuedChatRequest.fromJson(run.contextJson)}.getOrNull()?:return@mapNotNull null
        if(request.runId!=run.id||request.threadId!=sourceThreadId||request.userMessageId!=user)return@mapNotNull null
        user to request.attachments
    }.groupBy({it.first},{it.second}).mapValues{(_,lists)->lists.flatten().distinctBy{it.id}}
    return app.chatAttachments.forkHistory(sourceThreadId,targetThreadId,identities,legacy)
}

private fun attachmentCopyWarning(result:AttachmentHistoryCopyResult):String=if(result.unmappedSourceFiles==0)"" else
    " 이전 첨부 ${result.unmappedSourceFiles}개는 원메시지 연결 정보가 없어 이번 분기에 포함됐다고 확인하지 못했습니다."

fun ChatRunCoordinator.editMessage(messageId: String, text: String) = manage {
    require(text.isNotBlank() && text.length <= 12000)
    val source = app.repository.getMessage(messageId) ?: error("메시지 없음")
    check(source.role == "user")
    quiesceThread(source.threadId)
    val branch = app.repository.chat.forkBranch(source.threadId, source.id, title = "수정한 요청", includeMessage = false)
    val edited = app.repository.chat.addMessage(source.threadId, branch.id, "user", text)
    check(app.repository.chat.fullMessageText(edited.id) == text)
    selectThread(source.threadId)
    enqueueRevision(source.threadId, branch.id, edited.id, text)
    status.value = "고친 부탁의 새 답 준비 중 · 전에 한 결과는 그대로"
}

fun ChatRunCoordinator.regenerate(messageId: String) = manage {
    val source = app.repository.getMessage(messageId) ?: error("메시지 없음")
    val thread = app.repository.chat.thread(source.threadId) ?: error("대화 없음")
    val messages = app.repository.chat.messages(thread.id, thread.activeBranchId).first()
    val index = messages.indexOfFirst { it.id == messageId }
    check(index >= 0)
    val user = messages.take(index + 1).lastOrNull { it.role == "user" } ?: error("요청 없음")
    quiesceThread(thread.id)
    val branch = app.repository.chat.forkBranch(thread.id, user.id, title = "답변 다시 생성")
    selectThread(thread.id)
    enqueueRevision(thread.id, branch.id, user.id, app.repository.chat.fullMessageText(user.id))
    status.value = "새 분기에서 답변을 다시 생성합니다. 기존 도구 작업을 재실행하지 않습니다."
}

fun ChatRunCoordinator.copyMessage(messageId: String) = manage {
    val text = com.user.localagent.externalagent.CloudAgentFinals.visibleAnswer(app.repository.chat.fullMessageText(messageId))
    val clip = ClipData.newPlainText("집순이 답변", text).apply {
        description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    status.value = "답변 전체를 복사했습니다."
}

fun ChatRunCoordinator.exportMessage(messageId: String) = manage {
    val text = com.user.localagent.externalagent.CloudAgentFinals.visibleAnswer(app.repository.chat.fullMessageText(messageId))
    val file = verifiedExport("answer", "txt") { output -> output.write(text.toByteArray(Charsets.UTF_8)) }
    shareExport(file, "text/plain")
}

fun ChatRunCoordinator.exportThread(id: String, format: UiChatExportFormat) = manage {
    val thread = app.repository.chat.thread(id) ?: error("대화 없음")
    val corpus = withContext(Dispatchers.IO) {
        collectChatCorpus(thread,app.repository.chat.branches(id)){branch,before->
            app.repository.chat.messagePage(id,branch,before,200)
        }
    }
    val extension = when (format) { UiChatExportFormat.MARKDOWN -> "md"; UiChatExportFormat.JSON -> "json"; UiChatExportFormat.TEXT -> "txt" }
    val file = verifiedExport("conversation", extension) { output ->
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writeChatCorpus(corpus,format,Instant.now().toString(),writer){messageId->app.repository.chat.fullMessageText(messageId)}
            val live=app.repository.chat.thread(id)?:error("내보내기 중 대화가 삭제되었습니다.")
            check(live.revision==thread.revision&&live.projectId==thread.projectId&&live.activeBranchId==thread.activeBranchId){"내보내기 중 대화 버전·프로젝트가 변경되었습니다. 다시 내보내주세요."}
        }
    }
    shareExport(file, if (format == UiChatExportFormat.JSON) "application/json" else "text/plain")
}

/** Creates only an app-private temporary export; read-back hash covers every output byte. */
private suspend fun ChatRunCoordinator.verifiedExport(prefix: String, extension: String, write: suspend (OutputStream) -> Unit): File = withContext(Dispatchers.IO) {
    val directory = File(context.cacheDir, "shared").apply { check(isDirectory || mkdirs()) }.canonicalFile
    val ownedExport = Regex("(?:answer|conversation)-[a-f0-9-]{36}\\.(?:txt|md|json)")
    val oldest = System.currentTimeMillis() - 24L * 60 * 60 * 1000
    directory.listFiles().orEmpty().filter { it.isFile && ownedExport.matches(it.name) && it.lastModified() < oldest }.forEach { it.delete() }
    val file = File(directory, "$prefix-${UUID.randomUUID()}.$extension").canonicalFile
    check(file.parentFile == directory)
    try {
        val expected = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(file.outputStream(), expected).use { output -> write(output) }
        val actual = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(32 * 1024); while (true) { val read = input.read(bytes); if (read < 0) break; actual.update(bytes, 0, read) } }
        check(expected.digest().contentEquals(actual.digest()))
        file
    } catch (error: Exception) { file.delete(); throw error }
}

private fun ChatRunCoordinator.shareExport(file: File, mimeType: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).setType(mimeType).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newUri(context.contentResolver, "집순이 내보내기", uri) }
    context.startActivity(Intent.createChooser(intent, "대화 파일 저장·공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    status.value = "전체 원문 파일을 확인했습니다. 공유 화면에서 저장할 앱을 선택하세요."
}
