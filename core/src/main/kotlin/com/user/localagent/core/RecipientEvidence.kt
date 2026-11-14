package com.user.localagent.core

import kotlinx.serialization.json.*

data class ContactCandidate(val name:String,val phoneNumber:String)

/** Request-owned data provenance, not natural-language intent routing. Model calls cannot approve a choice. */
class RecipientEvidence(private val ownerRequest:String) {
    private val contacts=linkedMapOf<String,ContactCandidate>()
    private val explicit=Regex("\\+?[0-9][0-9() .-]{1,38}[0-9]").findAll(ownerRequest).mapNotNull{normalize(it.value)}.toSet()
    private val approvals=mutableSetOf<String>()
    private var incomplete=false
    fun observe(candidates:List<ContactCandidate>,truncated:Boolean=false){
        require(candidates.size<=101)
        candidates.forEach{candidate->normalize(candidate.phoneNumber)?.let{contacts.putIfAbsent(it,candidate)}}
        incomplete=incomplete||truncated
        approvals.clear()
    }
    fun choices(call:ToolCall):List<ContactCandidate> {
        if(call.name!="prepare_sms")return emptyList()
        ToolRegistry.validate(call)
        val number=normalize(call.arguments.getValue("phoneNumber").jsonPrimitive.content)
            ?:error("문자 수신 번호 형식을 확인하지 못했습니다.")
        val explicitCandidates=explicit.intersect(contacts.keys)
        if(number in explicit&&(explicitCandidates.size<=1))return emptyList()
        require(number in contacts){"문자 수신 번호가 현재 사용자 원문이나 연락처 조회 결과에서 확인되지 않았습니다."}
        if(contacts.size>1||incomplete)return contacts.values.toList()
        return emptyList()
    }
    fun approve(call:ToolCall,selected:String):ToolCall {
        val candidates=choices(call)
        require(candidates.isNotEmpty()&&!incomplete){"현재 조회가 불완전합니다. 연락처를 더 좁혀 조회한 새 요청이 필요합니다."}
        val candidate=candidates.singleOrNull{normalize(it.phoneNumber)==normalize(selected)}?:error("실제로 조회한 연락처만 선택할 수 있습니다.")
        val canonical=ToolRegistry.validate(call.copy(arguments=JsonObject(call.arguments+mapOf("phoneNumber" to JsonPrimitive(candidate.phoneNumber)))))
        approvals+=fingerprint(canonical)
        return canonical
    }
    fun consume(call:ToolCall){
        if(call.name!="prepare_sms")return
        val candidates=choices(call)
        if(candidates.isNotEmpty())require(approvals.remove(fingerprint(call))){"여러 연락처 중 수신자를 직접 선택한 확인이 필요합니다. 임의 번호로 초안을 열지 않았습니다."}
    }
    private fun fingerprint(call:ToolCall)=call.name+":"+JsonObject(call.arguments.toSortedMap())
    companion object {
        fun normalize(value:String):String? {
            if(value.any{!it.isDigit()&&it !in "+- ()."})return null
            val normalized=value.filter{it.isDigit()||it=='+'}
            return normalized.takeIf{it.matches(Regex("\\+?[0-9]{3,20}"))}
        }
    }
}
