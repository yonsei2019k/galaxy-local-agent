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
}
