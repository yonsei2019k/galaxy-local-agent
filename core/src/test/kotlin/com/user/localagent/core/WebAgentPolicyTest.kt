package com.user.localagent.core

import com.user.localagent.core.WebAgentPolicy.Element
import org.junit.Assert.*
import org.junit.Test

/** Same cases as pc-worker/tests/test_browser_agent.py so the phone and PC browsers share one policy. */
class WebAgentPolicyTest {
    @Test fun onlyPublicWebPages(){
        listOf("https://www.letskorail.com/","http://example.com/a?b=1","https://map.naver.com:443/p").forEach{assertTrue(it,WebAgentPolicy.urlAllowed(it))}
        listOf("file:///C:/x","chrome://settings","javascript:alert(1)","data:text/html,x","https://localhost/","http://127.0.0.1/","http://192.168.0.1/",
            "http://10.0.0.5/","http://100.75.229.95/","https://router.local/","https://user:pw@example.com/","https://example.com:8443/","http://intranet/",
            "https://[::1]/","content://x/y","intent://scan/#Intent;end","x".repeat(3000)).forEach{assertFalse(it,WebAgentPolicy.urlAllowed(it))}
    }
    @Test fun sensitiveFieldsAreNeverTyped(){
        listOf(Element("e1","input",type="password"),Element("e1","input",autocomplete="cc-number"),Element("e1","input",autocomplete="one-time-code"),
            Element("e1","input",name="cardNo"),Element("e1","input",label="인증번호 6자리"),Element("e1","input",placeholder="비밀번호"),
            Element("e1","input",id="cvc"),Element("e1","input",label="주민등록번호")).forEach{assertTrue(it.toString(),WebAgentPolicy.sensitiveField(it))}
        listOf(Element("e1","input",type="text",label="출발역"),Element("e1","input",type="search",placeholder="검색어 입력"),Element("e1","input",type="date",name="travelDate"))
            .forEach{assertFalse(it.toString(),WebAgentPolicy.sensitiveField(it))}
    }
    @Test fun irreversibleControlsNeedApproval(){
        listOf(Element("e1","button",label="결제하기"),Element("e1","a",label="예매"),Element("e1","button",label="주문하기"),
            Element("e1","input",type="submit",label="Place order"),Element("e1","div",role="button",label="예약 확정"),
            Element("e1","button",label="보내기"),Element("e1","button",label="Delete")).forEach{assertTrue(it.toString(),WebAgentPolicy.riskyControl(it))}
        listOf(Element("e1","button",label="조회하기"),Element("e1","a",label="다음 날짜"),Element("e1","button",label="장바구니 보기"),
            Element("e1","input",type="text",label="결제 금액 메모"),Element("e1","a",label="IANA-managed Reserved Domains"),
            Element("e1","a",label="Confirmation details"),Element("e1","a",label="Sender guide")).forEach{assertFalse(it.toString(),WebAgentPolicy.riskyControl(it))}
    }
    @Test fun typedTextBounds(){
        assertTrue(WebAgentPolicy.safeText("서울"));assertTrue(WebAgentPolicy.safeText("2026-11-20"))
        listOf("","x".repeat(501),"1234 5678 9012 3456","110-123-456789","a\u0000b",null).forEach{assertFalse(it.toString(),WebAgentPolicy.safeText(it))}
    }
    @Test fun signatureChangesWithPage(){
        val e=listOf(Element("e1","button",label="조회"))
        val same=WebAgentPolicy.signature("https://a.com/",e)
        assertEquals(same,WebAgentPolicy.signature("https://a.com/",listOf(e[0].copy())))
        assertNotEquals(same,WebAgentPolicy.signature("https://a.com/",listOf(Element("e1","button",label="결제"))))
        assertNotEquals(same,WebAgentPolicy.signature("https://b.com/",e))
    }
}
