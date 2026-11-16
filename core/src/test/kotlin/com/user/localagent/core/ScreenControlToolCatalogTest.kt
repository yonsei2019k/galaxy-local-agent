package com.user.localagent.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScreenControlToolCatalogTest {
    private fun call(vararg pairs:Pair<String,String>)=ToolCall("c1",ScreenControlToolCatalog.ACTION,buildJsonObject{pairs.forEach{(k,v)->put(k,v)}})

    @Test fun registeredWithReadOnlyReadAndNonReadOnlyAction(){
        assertTrue(ToolRegistry.definitions.keys.containsAll(ScreenControlToolCatalog.names))
        assertTrue(ToolReadPolicy.isReadOnly(ScreenControlToolCatalog.READ))
        assertFalse(ToolReadPolicy.isReadOnly(ScreenControlToolCatalog.ACTION))
    }
    @Test fun validatesEachActionShape(){
        ScreenControlToolCatalog.validate(call("action" to "tap","snapshotId" to "s","ref" to "e1"))
        ScreenControlToolCatalog.validate(call("action" to "back","snapshotId" to "s"))
        ScreenControlToolCatalog.validate(call("action" to "open_app","snapshotId" to "s","packageName" to "com.sec.android.app.popupcalculator"))
        listOf(call("action" to "swipe","snapshotId" to "s"),call("action" to "tap","snapshotId" to "s"),
            call("action" to "type","snapshotId" to "s","ref" to "e2"),call("action" to "open_app","snapshotId" to "s","packageName" to "not a package"),
            call("action" to "scroll","snapshotId" to "s","direction" to "sideways")).forEach{
            assertThrows(IllegalArgumentException::class.java){ScreenControlToolCatalog.validate(it)}
        }
    }
    @Test fun refusesCardNumbersButNotOrdinaryText(){
        assertThrows(IllegalArgumentException::class.java){ScreenControlToolCatalog.validate(call("action" to "type","snapshotId" to "s","ref" to "e2","text" to "4111 1111 1111 1111"))}
        ScreenControlToolCatalog.validate(call("action" to "type","snapshotId" to "s","ref" to "e2","text" to "010-1234-5678 내일 3시 회의"))
    }
    @Test fun commitLikeLabelsNeedOwnerReview(){
        listOf("보내기","결제하기","주문하기","삭제","Send","Confirm").forEach{assertTrue(it,ScreenControlToolCatalog.isCommitLabel(it))}
        listOf("검색","메뉴","7","더보기","뒤로").forEach{assertFalse(it,ScreenControlToolCatalog.isCommitLabel(it))}
    }
}
