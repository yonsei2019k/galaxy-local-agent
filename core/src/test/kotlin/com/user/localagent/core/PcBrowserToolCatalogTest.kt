package com.user.localagent.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class PcBrowserToolCatalogTest {
    private val catalogs=listOf<BrowserToolCatalog>(PcBrowserToolCatalog,AppBrowserToolCatalog)
    private fun act(catalog:BrowserToolCatalog,vararg pairs:Pair<String,String>)=ToolCall("c1",catalog.action,buildJsonObject{pairs.forEach{(k,v)->put(k,v)}})
    @Test fun registeredWithReadPolicy()=catalogs.forEach{c->
        assertTrue(ToolRegistry.definitions.keys.containsAll(c.names))
        assertTrue(ToolReadPolicy.isReadOnly(c.read))
        assertFalse(ToolReadPolicy.isReadOnly(c.action))
        assertFalse(ToolReadPolicy.isReadOnly(c.open))
    }
    @Test fun validActionsPass()=catalogs.forEach{c->
        listOf(act(c,"action" to "click","snapshotId" to "s","ref" to "e3"),act(c,"action" to "type","snapshotId" to "s","ref" to "e1","text" to "서울"),
            act(c,"action" to "scroll","snapshotId" to "s","direction" to "up"),act(c,"action" to "back","snapshotId" to "s")).forEach{
            ToolRegistry.validate(it);c.validate(it)
        }
    }
    @Test fun badActionsAreRefused()=catalogs.forEach{c->
        listOf(act(c,"action" to "run_js","snapshotId" to "s"),act(c,"action" to "click","snapshotId" to "s"),act(c,"action" to "click","snapshotId" to "s","ref" to "#x"),
            act(c,"action" to "type","snapshotId" to "s","ref" to "e1"),act(c,"action" to "type","snapshotId" to "s","ref" to "e1","text" to "4111 1111 1111 1111"),
            act(c,"action" to "scroll","snapshotId" to "s","direction" to "left")).forEach{
            assertThrows(IllegalArgumentException::class.java){c.validate(it)}
        }
    }

    @Test fun phoneRulesAreLimitedToOneTypedClickWithAnExactReadback() {
        val args=arrayOf("action" to "click","snapshotId" to "s","ref" to "e3",
            BrowserNavigationRule.LABEL to "이용 안내",BrowserNavigationRule.VERIFY to "서비스 이용 안내")
        val call=act(AppBrowserToolCatalog,*args)
        ToolRegistry.validate(call)
        AppBrowserToolCatalog.validate(call)
        assertTrue(BrowserNavigationRule.from(call)!!.sees("", "다른 줄\n서비스 이용 안내"))
        assertFalse(BrowserNavigationRule.from(call)!!.sees("", "이곳은 서비스 이용 안내 페이지"))
        assertThrows(IllegalArgumentException::class.java){AppBrowserToolCatalog.validate(act(AppBrowserToolCatalog,*args.dropLast(1).toTypedArray()))}
        assertThrows(IllegalArgumentException::class.java){ToolRegistry.validate(act(PcBrowserToolCatalog,*args))}
        assertTrue(BrowserNavigationRule.sameSiteLink("https://example.org/a","https://example.org/b"))
        assertFalse(BrowserNavigationRule.sameSiteLink("https://example.org/a","https://other.org/b"))
        assertFalse(BrowserNavigationRule.sameSiteLink("https://example.org/a","https://example.org/%6cogin"))
        assertFalse(BrowserNavigationRule.sameSiteLink("https://example.org/a","https://example.org/b?token=opaque"))
    }
}
