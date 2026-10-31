package com.user.localagent.analysis

data class PrivateReportBudgetResult(val text:String,val utf8Bytes:Int,val includedFindings:Int,val omittedFindings:Int,
    val complete:Boolean,val footerTruncated:Boolean) {
    override fun toString()="PrivateReportBudgetResult(utf8Bytes=$utf8Bytes,includedFindings=$includedFindings,omittedFindings=$omittedFindings,complete=$complete,footerTruncated=$footerTruncated,privateTextOmitted=true)"
}

/** Whole finding blocks only. Omitted file detail remains in the caller's encrypted journal. */
object PrivateAnalysisReportBudget {
    const val MAX_BYTES=20*1024*1024
    fun build(header:String,detailBlocks:Sequence<String>,totalFindings:Int,footer:String="",maxBytes:Int=MAX_BYTES):PrivateReportBudgetResult {
        require(maxBytes in 1024..MAX_BYTES&&totalFindings in 0..40_000)
        val headerBytes=utf8Length(header)
        // Reserve the largest possible count widths and the longer false value before any detail.
        val statusReserve=utf8Length(status(totalFindings,totalFindings,totalFindings,false))
        require(headerBytes<=maxBytes-statusReserve){"질문·필수 설명이 보고서 크기 예산을 넘습니다. 필수 내용을 조용히 자르지 않았습니다."}
        val footerBudget=minOf(4096,maxBytes/4,maxBytes-headerBytes-statusReserve)
        val keptFooter=prefixWithinUtf8(footer,footerBudget)
        val footerBytes=utf8Length(keptFooter)
        val footerTruncated=keptFooter.length<footer.length
        val detailBudget=maxBytes-headerBytes-footerBytes-statusReserve
        val builder=StringBuilder(header)
        var bytes=headerBytes;var detailBytes=0;var included=0;var budgetHit=false
        val iterator=detailBlocks.iterator()
        while(included<totalFindings&&iterator.hasNext()){
            val block=iterator.next();require(block.isNotEmpty()){"빈 상세 항목을 보고서에 수록할 수 없습니다."}
            val size=utf8Length(block)
            if(size>detailBudget-detailBytes){budgetHit=true;break}
            builder.append(block);detailBytes+=size;bytes+=size;included++
        }
        if(!budgetHit)require(included==totalFindings&&!iterator.hasNext()){"상세 항목의 실제 개수와 보고한 개수가 다릅니다."}
        // Do not render the omitted tail merely to count it: totalFindings comes from the saved journal.
        val omitted=totalFindings-included
        val ending=status(included,omitted,totalFindings,footerTruncated)
        builder.append(keptFooter);builder.append(ending)
        bytes+=footerBytes+utf8Length(ending)
        check(bytes<=maxBytes)
        return PrivateReportBudgetResult(builder.toString(),bytes,included,omitted,omitted==0&&!footerTruncated,footerTruncated)
    }
    private fun status(included:Int,omitted:Int,total:Int,footerTruncated:Boolean)=buildString {
        appendLine();appendLine()
        appendLine("이 파일에 수록: $included / $total 항목")
        appendLine("파일 크기 제한으로 생략: $omitted 항목")
        appendLine("추가 설명 일부 생략: ${if(footerTruncated)"예"else"아니오"}")
        appendLine("전체 저장 항목과 근거는 암호화된 분석 기록에 보존됩니다. 이 파일에서 빠진 항목은 대화에서 분석 근거의 다음 페이지를 요청해 확인할 수 있습니다.")
    }
    private fun utf8Length(text:String):Int {
        var bytes=0L;var index=0
        while(index<text.length){
            val char=text[index++]
            bytes+=when {
                char.isHighSurrogate()->{require(index<text.length&&text[index].isLowSurrogate()){"보고서 문자의 유니코드 형식이 잘못되었습니다."};index++;4}
                char.isLowSurrogate()->error("보고서 문자의 유니코드 형식이 잘못되었습니다.")
                char.code<0x80->1
                char.code<0x800->2
                else->3
            }
            require(bytes<=Int.MAX_VALUE){"보고서 항목이 너무 큽니다."}
        }
        return bytes.toInt()
    }
    private fun prefixWithinUtf8(text:String,budget:Int):String {
        var at=0;var bytes=0
        while(at<text.length){
            val char=text[at]
            val length=when {
                char.isHighSurrogate()->{require(at+1<text.length&&text[at+1].isLowSurrogate());4}
                char.isLowSurrogate()->error("보고서 문자의 유니코드 형식이 잘못되었습니다.")
                char.code<0x80->1
                char.code<0x800->2
                else->3
            }
            if(length>budget-bytes)break
            bytes+=length;at+=if(char.isHighSurrogate())2 else 1
        }
        return text.substring(0,at)
    }
}
