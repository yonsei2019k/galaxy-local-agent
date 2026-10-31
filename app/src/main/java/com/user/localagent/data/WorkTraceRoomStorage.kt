package com.user.localagent.data

import androidx.room.withTransaction

// Reuses the existing encrypted schema3 rows and indexes.
internal class WorkTraceRoomStorage(private val database:LocalDatabase):WorkTraceStorage {
    override suspend fun <T> transaction(block:suspend()->T):T=database.withTransaction{block()}
    override suspend fun run(id:String)=database.chatDao().run(id)
    override suspend fun thread(id:String)=database.chatDao().thread(id)
    override suspend fun project(id:String)=database.chatDao().project(id)
    override suspend fun row(id:String)=database.dao().toolRunById(id)
    override suspend fun rows(threadId:String,runId:String,limit:Int)=database.chatDao().workTraceRows(threadId,runId,limit)
    override suspend fun page(threadId:String,runId:String,before:WorkTraceCursor?,limit:Int)=database.chatDao().workTracePage(threadId,runId,before?.atEpochMillis,before?.id,limit)
    override suspend fun count(threadId:String,runId:String)=database.chatDao().workTraceCount(threadId,runId)
    override suspend fun insert(record:ToolRunRecord){database.dao().toolRun(record)}
    override fun observe(threadId:String,runId:String,limit:Int)=database.chatDao().observeWorkTraceRows(threadId,runId,limit)
    override fun observeCount(threadId:String,runId:String)=database.chatDao().observeWorkTraceCount(threadId,runId)
    override fun observeRuns(threadId:String,runIds:List<String>)=database.chatDao().observeRunRows(threadId,runIds)
}
