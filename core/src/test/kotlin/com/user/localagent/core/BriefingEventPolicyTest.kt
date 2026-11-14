package com.user.localagent.core
import org.junit.Assert.*
import org.junit.Test
class BriefingEventPolicyTest {
    private fun block(confirmed:Boolean=true,owner:Boolean=true,changed:Boolean=false,partial:Boolean=false,
        start:String?="2027-01-01T10:00:00+09:00",end:String?="2027-01-01T11:00:00+09:00",quote:String="회의",now:Long=0)=
        BriefingEventPolicy.automaticBlockReason(confirmed,owner,changed,partial,"회의",start,end,quote,"내년 1월 1일 오전10시~11시 회의 확정",now)
    @Test fun completeConfirmedFutureEventIsEligible(){assertNull(block())}
    @Test fun partialChangedOtherPersonsAndUncertainEventsNeedReview(){assertNotNull(block(partial=true));assertNotNull(block(changed=true));assertNotNull(block(owner=false));assertNotNull(block(confirmed=false))}
    @Test fun absentOrMalformedDateEndAndSourceCannotAutoRegister(){assertNotNull(block(end=null));assertNotNull(block(start="내일"));assertNotNull(block(quote="제출"));assertNotNull(block(now=Long.MAX_VALUE))}
}
