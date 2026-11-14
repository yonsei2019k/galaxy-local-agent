package com.user.localagent.core

import java.time.OffsetDateTime

/** Confidence is necessary but never overrides partial evidence, missing dates, conflicts or changed invitations. */
object BriefingEventPolicy {
    fun automaticBlockReason(confirmed:Boolean,ownerRelevant:Boolean,changed:Boolean,partial:Boolean,
        title:String,start:String?,end:String?,quote:String,sourceText:String,nowMillis:Long):String? {
        if(quote.isBlank()||quote !in sourceText)return "원문 근거를 확인해야 합니다."
        if(partial)return "자료가 일부뿐이므로 전체 문맥 확인이 필요합니다."
        if(!confirmed||!ownerRelevant||changed)return "참석 여부·정정·취소 또는 일정 확정을 확인해주세요."
        if(title.isBlank()||title.length>300)return "일정 이름을 확인해주세요."
        val a=runCatching {OffsetDateTime.parse(start).toInstant().toEpochMilli()}.getOrNull()
        val b=runCatching {OffsetDateTime.parse(end).toInstant().toEpochMilli()}.getOrNull()
        if(a==null||b==null||b<=a)return "시작·종료 날짜와 시각을 확인해주세요."
        if(a<nowMillis)return "이미 지난 일정입니다. 등록 여부를 확인해주세요."
        return null
    }
}
