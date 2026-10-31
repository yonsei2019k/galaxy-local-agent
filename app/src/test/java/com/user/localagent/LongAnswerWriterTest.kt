package com.user.localagent

import org.junit.Assert.*
import org.junit.Test
import com.user.localagent.ui.UiAnswerPart

class LongAnswerWriterTest {
    @Test fun ordinaryJsonIsProseEvenWhenItContainsToolWordsAsData(){
        assertEquals(GeneratedResponseKind.PROSE,generatedResponseKind("{\"items\":[1,2,"))
        assertEquals(GeneratedResponseKind.PROSE,generatedResponseKind("{\"id\":17,\"name\":\"Alice\"}"))
        assertEquals(GeneratedResponseKind.PROSE,generatedResponseKind("{\"tool\":\"hammer\",\"arguments\":{\"value\":1}}",true))
        assertEquals(GeneratedResponseKind.PROSE,generatedResponseKind("{\"data\":{\"type\":\"tool_call\",\"tool\":\"save_note\"}}"))
    }
    @Test fun typedAndLegacyToolEnvelopesStaySeparateFromDurableJsonProse(){
        assertEquals(GeneratedResponseKind.TOOL,generatedResponseKind("{\"type\":\"tool_call\",\"tool\":"))
        assertEquals(GeneratedResponseKind.TOOL,generatedResponseKind("{\"name\":\"get_current_datetime\",\"arguments\":{}}",true))
        assertEquals(GeneratedResponseKind.UNDECIDED,generatedResponseKind("{\"id\":\"x\",\"arguments\":{",true))
    }
    @Test fun fullRepeatedContinuationIsHeldAndNeverBecomesANewDurableSuffix(){
        val previous="이미 저장한 합성 문단 🙂. ".repeat(6)
        val gate=ContinuationPrefixGate(previous,previous)
        val prefix=previous.safeHead(previous.length/2)
        assertNull(gate.visibleStart(prefix))
        assertEquals(previous.length,gate.visibleStart(previous,true))
        assertEquals("",previous.substring(gate.visibleStart(previous,true)!!))
    }
    @Test fun provenOverlapIsRemovedButShortCoincidencesAndDivergingPrefixesArePreserved(){
        val overlap="정확하게 이어진 기존 문단의 마지막 부분입니다 🙂. ".repeat(2)
        val previous="먼저 쓴 본문\n"+overlap
        val next=overlap+"새로운 뒷부분"
        val start=ContinuationPrefixGate(previous).visibleStart(next,true)!!
        assertEquals("새로운 뒷부분",next.substring(start))
        val plain="공통 시작이지만 다른 새 문단"
        assertEquals(0,ContinuationPrefixGate("공통 시작이지만 이전 문단").visibleStart(plain,true))
        assertEquals(0,ContinuationPrefixGate("기존 문장은 마침표.").visibleStart(".새로운 문장",true))
    }
    @Test fun partBudgetPausesAtExactBoundaryAndNeverUsesOverflowingIntAddition(){
        assertFalse(reachesAnswerPartBudget(100,27,128))
        assertTrue(reachesAnswerPartBudget(100,28,128))
        assertTrue(reachesAnswerPartBudget(100,29,128))
        assertTrue(reachesAnswerPartBudget(Int.MAX_VALUE,Int.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java){reachesAnswerPartBudget(-1,1)}
    }
    @Test fun continuouslyArrivingDeltasFlushAtOneHundredMillisWithoutWaitingForIdleOrFourK() {
        var lastFlush=0L
        var pending=0
        val flushTimes=mutableListOf<Long>()
        for(tick in 20L..300L step 20L) {
            pending+=3
            if(answerFlushDue(pending,tick,lastFlush)) {
                flushTimes+=tick;pending=0;lastFlush=tick
            }
        }
        assertEquals(listOf(100L,200L,300L),flushTimes)
    }

    @Test fun largeDeltaFlushesImmediatelyAndAnEmptyBufferDoesNotWrite() {
        assertTrue(answerFlushDue(4096,1,0))
        assertFalse(answerFlushDue(4095,99,0))
        assertFalse(answerFlushDue(0,1000,0))
    }

    @Test fun blankOrRepeatedOutputStopsAutomaticContinuation() {
        assertFalse(answerMadeProgress("already written"," \n\t"))
        assertFalse(answerMadeProgress("already written","already written"))
        assertFalse(answerMadeProgress("prefix\nlast paragraph\n","last paragraph"))
        val paragraph="synthetic paragraph ".repeat(10)
        assertFalse(answerMadeProgress("start\n$paragraph\nending",paragraph))
    }

    @Test fun genuinelyNewContinuationStillMakesProgress() {
        assertTrue(answerMadeProgress("", "first paragraph"))
        assertTrue(answerMadeProgress("first paragraph", "second paragraph"))
        assertTrue(answerMadeProgress("a repeated phrase", "a repeated phrase followed by new material"))
    }

    @Test fun twentyThousandCharacterBodyHasNoDisplayOrDurableChunkTruncation() {
        val original=("합성 긴 답변 🙂 문단\n".repeat(3000))+"실제 끝부분"
        assertTrue(original.length>20000)
        val pending=StringBuilder(original)
        val persisted=StringBuilder()
        var chunks=0
        while(pending.isNotEmpty()) {
            val chunk=nextAnswerChunk(pending)
            assertTrue(chunk.length in 1..32768)
            persisted.append(chunk);pending.delete(0,chunk.length);chunks++
        }
        assertTrue(chunks>=2)
        assertEquals(original,persisted.toString())
        assertTrue(persisted.endsWith("실제 끝부분"))
    }

    @Test fun durableChunkBoundaryDoesNotSplitAnEmojiSurrogatePair() {
        val original="a".repeat(32767)+"🙂"+"tail"
        val first=nextAnswerChunk(original)
        assertEquals(32767,first.length)
        val second=nextAnswerChunk(original.substring(first.length))
        assertEquals(original,first+second)
        assertFalse(first.last().isHighSurrogate())
        assertTrue(second.startsWith("🙂"))
    }

    @Test fun reservedPlaceholderIsNotReportedAsAnAlreadySavedAssistantAnswer() {
        val placeholder="reserved-assistant-a"
        assertFalse(hasSavedAnswerBody("",emptyList()))
        assertNull(visibleAnswerMessageId(placeholder,hasSavedAnswerBody("",emptyList())))
        assertNull(visibleAnswerMessageId(placeholder,true,rawOnly=true))
    }

    @Test fun hostToolAnswerIsPreservedAndModelProseStartsAfterItsParts() {
        val hostParts=listOf(UiAnswerPart(1,"파일을 만들고 검증했습니다."),UiAnswerPart(2,"두 번째 결과"))
        assertTrue(hasSavedAnswerBody("",hostParts))
        assertEquals("reserved-assistant-a",visibleAnswerMessageId("reserved-assistant-a",true))
        assertEquals(3,nextAnswerPart(null,hostParts))
        assertEquals(3,nextAnswerPart(1,hostParts))
        assertEquals(5,nextAnswerPart(5,hostParts))
        assertEquals("파일을 만들고 검증했습니다.두 번째 결과",hostParts.joinToString(""){it.text})
    }

    @Test fun oldRunWithoutAReservedPlaceholderStillStartsItsFirstPart() {
        assertEquals(1,nextAnswerPart(null,emptyList()))
        assertNull(visibleAnswerMessageId(null,false))
        assertTrue(hasSavedAnswerBody("기존 전체 본문",emptyList()))
    }

    @Test fun summaryPrefixExcludesCurrentRequestAndAllFutureQueuedMessages() {
        val branch=listOf("older-user","older-answer","request-a","reserved-a","queued-user-b","reserved-b")
        assertEquals(listOf("older-user","older-answer"),answerPrefixBeforeRequest(branch,"request-a"){it})
        assertTrue(answerPrefixBeforeRequest(branch,"missing-request"){it}.isEmpty())
    }
}
