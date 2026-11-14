package com.user.localagent.workspace

import com.user.localagent.core.ProjectPaths
import com.user.localagent.core.ProjectStorageToolCatalog
import com.user.localagent.core.ToolCall
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** A bounded full-byte observation, not a text read token or permission to modify the file. */
data class ProjectFileDigest(val path:String,val bytes:Long,val sha256:String)

/**
 * Uses only the already bound workspace callbacks. It knows no root URI, absolute path, account or shell.
 * Separate observations can race owner/provider changes; duplicate results never authorize deletion.
 */
class ProjectStorageAudit(
    private val list:suspend(String)->List<ProjectFile>,
    private val hash:suspend(String,Long)->ProjectFileDigest,
) {
    private data class Snapshot(val entries:List<ProjectFile>,val issues:Set<String>,val visitedDirectories:Int)

    suspend fun execute(call:ToolCall):String {
        val path=ProjectStorageToolCatalog.path(call)
        val snapshot=scan(path)
        return if(call.name=="find_project_duplicates")duplicates(path,snapshot).toString()else metadata(path,snapshot).toString()
    }

    private suspend fun scan(root:String):Snapshot {
        val pending=ArrayDeque<Pair<String,Int>>();pending.add(root to 0)
        val visited=linkedSetOf<String>();val entries=linkedMapOf<String,ProjectFile>();val issues=linkedSetOf<String>()
        while(pending.isNotEmpty()&&entries.size<MAX_ENTRIES){
            currentCoroutineContext().ensureActive()
            val (directory,depth)=pending.removeFirst()
            if(!visited.add(directory)){issues+="repeated_directory";continue}
            val children=try {list(directory)}catch(cancel:CancellationException){throw cancel}catch(error:Exception){
                if(directory==root)throw error
                issues+="directory_read_failed";continue
            }
            if(children.size>=DIRECTORY_LIMIT)issues+="directory_entry_limit"
            val parent=if(directory.isBlank())emptyList()else ProjectPaths.segments(directory)
            for(entry in children.take(DIRECTORY_LIMIT)){
                currentCoroutineContext().ensureActive()
                if(entries.size>=MAX_ENTRIES){issues+="total_entry_limit";break}
                val parts=runCatching {ProjectPaths.segments(entry.path)}.getOrNull()
                if(parts==null||parts.size!=parent.size+1||parts.dropLast(1)!=parent){issues+="invalid_child_path";continue}
                if(entry.path in entries){issues+="repeated_entry";continue}
                val safe=if(entry.bytes!=null&&entry.bytes<0){issues+="unknown_file_size";entry.copy(bytes=null)}else entry
                entries[entry.path]=safe
                if(entry.directory){
                    if(depth+1<MAX_DEPTH)pending.add(entry.path to depth+1)else issues+="depth_limit"
                }else if(safe.bytes==null)issues+="unknown_file_size"
            }
        }
        if(pending.isNotEmpty()||entries.size>=MAX_ENTRIES)issues+="total_entry_limit"
        return Snapshot(entries.values.toList(),issues,visited.size)
    }

    private fun base(path:String,snapshot:Snapshot,issues:Set<String>):JsonObject=buildJsonObject {
        put("status","ok");put("scope","selected_project_only");put("path",path);put("readOnly",true)
        put("untrustedSourceData",true);put("partial",issues.isNotEmpty());put("enumerationComplete",snapshot.issues.isEmpty())
        put("issues",JsonArray(issues.map(::JsonPrimitive)))
        put("observedFiles",snapshot.entries.count {!it.directory});put("observedDirectories",snapshot.entries.count {it.directory})
        put("visitedDirectories",snapshot.visitedDirectories)
        put("limits",buildJsonObject {put("depth",MAX_DEPTH);put("entries",MAX_ENTRIES);put("entriesPerDirectory",DIRECTORY_LIMIT);put("hashBytesPerFile",MAX_FILE_BYTES);put("hashBytesTotal",MAX_HASH_BYTES)})
        put("filesChanged",false);put("deletedFiles",0);put("movedFiles",0)
    }

    private fun metadata(path:String,snapshot:Snapshot):JsonObject {
        val files=snapshot.entries.filter {!it.directory}
        var knownBytes=0L;var totalCapped=false
        for(file in files){val bytes=file.bytes?:continue;if(Long.MAX_VALUE-knownBytes<bytes){knownBytes=Long.MAX_VALUE;totalCapped=true}else knownBytes+=bytes}
        val groups=files.groupBy {entry->
            val name=entry.path.substringAfterLast('/')
            if('.' !in name||name.indexOf('.')<=0)"(none)"else name.substringAfterLast('.').lowercase(Locale.ROOT).ifBlank {"(none)"}
        }.entries.sortedWith(compareByDescending<Map.Entry<String,List<ProjectFile>>>{it.value.size}.thenBy {it.key})
        val issues=if(totalCapped)snapshot.issues+"total_size_overflow"else snapshot.issues
        return JsonObject(base(path,snapshot,issues)+buildJsonObject {
            put("observedKnownBytes",knownBytes);put("sizeTotalCapped",totalCapped);put("unknownSizeFiles",files.count {it.bytes==null})
            put("largestFiles",buildJsonArray {files.filter {it.bytes!=null}.sortedWith(compareByDescending<ProjectFile>{it.bytes}.thenBy {it.path}).take(20).forEach {file->add(buildJsonObject{put("path",file.path);put("bytes",file.bytes)})}})
            put("extensionGroups",buildJsonArray {groups.take(20).forEach {group->add(buildJsonObject {put("extension",group.key);put("files",group.value.size)})}})
            put("extensionGroupsOmitted",(groups.size-20).coerceAtLeast(0))
            put("photoContentsAnalyzed",false);put("wholePhoneInspected",false)
        })
    }

    private suspend fun duplicates(path:String,snapshot:Snapshot):JsonObject {
        val issues=snapshot.issues.toMutableSet()
        val candidateGroups=snapshot.entries.filter {!it.directory&&it.bytes!=null}.groupBy {it.bytes!!}.filterValues {it.size>1}
        val observations=mutableListOf<ProjectFileDigest>();var chargedBytes=0L;var verifiedBytes=0L;var skipped=0
        for((size,files) in candidateGroups.toSortedMap())for(file in files.sortedBy {it.path}){
            currentCoroutineContext().ensureActive()
            if(size>MAX_FILE_BYTES){issues+="hash_file_size_limit";skipped++;continue}
            val remaining=MAX_HASH_BYTES-chargedBytes
            // Reserve a possible one-byte oversized-file probe even if the callback fails without a digest.
            val cap=maxOf(1,size)
            if(cap+1>remaining){issues+="hash_total_bytes_limit";skipped++;continue}
            chargedBytes+=cap+1
            val digest=try {hash(file.path,cap)}catch(cancel:CancellationException){throw cancel}catch(_:Exception){issues+="file_hash_failed";skipped++;continue}
            if(digest.path!=file.path||digest.bytes!=size||digest.bytes<0||!SHA.matches(digest.sha256)){
                issues+="file_changed_or_invalid_digest";skipped++;continue
            }
            observations+=digest;verifiedBytes+=digest.bytes
        }
        val groups=observations.groupBy {it.bytes to it.sha256}.values.filter {it.size>1}.sortedBy {it.minOf {file->file.path}}
        var reportedPaths=0;var reportedGroups=0;var omittedPaths=0;var omittedGroups=0
        val shown=buildJsonArray {
            for(group in groups){
                val allowance=minOf(10,MAX_REPORTED_PATHS-reportedPaths)
                if(reportedGroups>=10||allowance<2){omittedGroups++;omittedPaths+=group.size;continue}
                val paths=group.map {it.path}.sorted().take(allowance)
                reportedGroups++;reportedPaths+=paths.size;omittedPaths+=group.size-paths.size
                add(buildJsonObject {put("sha256",group.first().sha256);put("bytesEach",group.first().bytes);put("observedCopies",group.size);put("paths",JsonArray(paths.map(::JsonPrimitive)));put("pathsOmitted",group.size-paths.size)})
            }
        }
        return JsonObject(base(path,snapshot,issues)+buildJsonObject {
            put("comparison","exact_bytes_at_observation");put("hashedFiles",observations.size);put("verifiedHashBytes",verifiedBytes)
            put("hashBudgetChargedBytes",chargedBytes);put("skippedHashCandidates",skipped)
            put("duplicateGroups",shown);put("observedDuplicateGroups",groups.size);put("duplicateGroupsOmitted",omittedGroups);put("duplicatePathsOmitted",omittedPaths)
            put("potentialDuplicateBytes",groups.sumOf {it.first().bytes*(it.size-1)})
            put("visualSimilarityAnalyzed",false);put("doesNotAuthorizeDeletion",true);put("concurrentChangesNotExcluded",true)
        })
    }

    companion object {
        const val MAX_DEPTH=4
        const val MAX_ENTRIES=500
        const val DIRECTORY_LIMIT=100
        const val MAX_FILE_BYTES=16L*1024*1024
        const val MAX_HASH_BYTES=64L*1024*1024
        const val MAX_REPORTED_PATHS=40
        private val SHA=Regex("[a-f0-9]{64}")
    }
}
