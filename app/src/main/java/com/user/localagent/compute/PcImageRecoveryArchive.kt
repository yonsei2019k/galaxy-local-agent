package com.user.localagent.compute

import kotlinx.serialization.json.*

/** Caller holds the recovery store lock. Copy/readback precedes removal across two encrypted files. */
internal class PcImageRecoveryArchive(
    private val readActive:()->String,private val writeActive:(String)->Unit,
    private val readArchive:()->String,private val writeArchive:(String)->Unit,
    private val clock:()->Long
) {
    private fun active(raw:String)=raw.takeIf{it.isNotBlank()}?.let(PcImageRecoveryTicket::parse)
    private fun expired(ticket:PcImageRecoveryTicket,now:Long)=now>=ticket.createdAt&&now-ticket.createdAt>=24*60*60_000L
    private fun decode(raw:String):List<PcImageRecoveryTicket> {
        if(raw.isBlank())return emptyList()
        require(raw.length<=MAX_CHARS&&raw.toByteArray(Charsets.UTF_8).size<=MAX_BYTES){"보관한 PC 복구 정보 크기가 잘못되었습니다."}
        val array=Json.parseToJsonElement(raw) as? JsonArray?:error("보관한 PC 복구 정보 형식이 잘못되었습니다.")
        require(array.size<=MAX_TICKETS){"보관한 PC 복구 정보 개수가 잘못되었습니다."}
        val unique=linkedMapOf<String,PcImageRecoveryTicket>()
        array.forEach {item->
            val ticket=PcImageRecoveryTicket.parse(item.toString())
            require(unique[ticket.id]?.let{it==ticket}!=false){"같은 작업의 보관 정보가 서로 다릅니다."}
            unique[ticket.id]=ticket
        }
        return unique.values.toList()
    }
    private fun encode(tickets:List<PcImageRecoveryTicket>):String {
        require(tickets.size<=MAX_TICKETS){"PC 보관 작업이 4개입니다. 보관한 작업을 먼저 마쳐주세요."}
        return JsonArray(tickets.map{Json.parseToJsonElement(it.json())}).toString().also {
            require(it.length<=MAX_CHARS&&it.toByteArray(Charsets.UTF_8).size<=MAX_BYTES){"보관할 PC 복구 정보가 너무 큽니다."}
        }
    }
    fun available():List<PcImageRecoveryTicket> {
        val current=active(readActive());val now=clock()
        return decode(readArchive()).filter{it.id!=current?.id&&it.validAt(now)}.asReversed()
    }
    fun archiveActive():Boolean {
        val raw=readActive();val ticket=active(raw)?:return false;val now=clock()
        check(ticket.validAt(now)){"보관할 PC 작업의 유효 기간을 확인해주세요."}
        val saved=decode(readArchive()).filterNot{expired(it,now)}
        check(saved.firstOrNull{it.id==ticket.id}?.let{it==ticket}!=false){"같은 작업의 보관 정보가 서로 다릅니다."}
        val encoded=encode(saved.filterNot{it.id==ticket.id}+ticket)
        writeArchive(encoded)
        check(readArchive()==encoded){"PC 작업 보관을 다시 읽어 확인하지 못했습니다. 원래 작업을 유지합니다."}
        check(readActive()==raw){"보관하는 동안 현재 PC 작업이 변경되었습니다."}
        writeActive("")
        check(readActive()==""){"보관은 확인했지만 현재 작업 표시를 지우지 못했습니다."}
        return true
    }
    fun restoreMostRecent():PcImageRecoveryTicket? {
        val rawActive=readActive();val current=active(rawActive);val now=clock()
        check(current==null||expired(current,now)){"현재 PC 복구 작업을 먼저 마치거나 보관해주세요."}
        val rawArchive=readArchive();val saved=decode(rawArchive)
        val ticket=saved.asReversed().firstOrNull{it.validAt(now)}?:return null
        val encoded=ticket.json()
        check(readActive()==rawActive){"불러오는 동안 현재 PC 작업이 변경되었습니다."}
        writeActive(encoded)
        check(readActive()==encoded){"PC 작업 불러오기를 다시 읽어 확인하지 못했습니다. 보관 정보를 유지합니다."}
        check(readArchive()==rawArchive){"불러오는 동안 보관한 PC 작업이 변경되었습니다."}
        val remaining=encode(saved.filterNot{it.id==ticket.id})
        writeArchive(remaining)
        check(readArchive()==remaining){"작업은 불러왔지만 보관 표시 정리를 확인하지 못했습니다."}
        return ticket
    }
    companion object {const val MAX_TICKETS=4;const val MAX_BYTES=65_536;const val MAX_CHARS=65_536}
}
