package com.user.localagent.core.connections

import com.user.localagent.core.PreparedHandoff
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue

class LifeServiceCatalogTest {
    private fun open(service: String) = ToolCall("fixture", "open_url",
        buildJsonObject { put("service", service) })

    @Test fun officialTargetsAreScreenHandoffs() {
        assertEquals("https://my.kt.com/", LifeServiceCatalog.url(ToolRegistry.validate(open("kt"))))
        assertEquals("https://cont.insure.or.kr/cont_web/", LifeServiceCatalog.url(open("insurance_lookup")))
        assertEquals("https://flyasiana.com/", LifeServiceCatalog.url(open("asiana")))
        assertTrue(PreparedHandoff.opensScreenOnly("open_url"))
    }

    @Test fun serviceIdentifiersCannotPassAnArbitraryUrl() {
        assertThrows(IllegalArgumentException::class.java) { LifeServiceCatalog.url(open("https://example.org/login")) }
        assertThrows(IllegalArgumentException::class.java) { ToolRegistry.validate(open("unknown")) }
        assertThrows(IllegalArgumentException::class.java) { ToolRegistry.validate(ToolCall("fixture", "open_url",
            buildJsonObject { put("service", "kt"); put("url", "https://example.org/") })) }
        ToolRegistry.validate(ToolCall("fixture", "open_url", buildJsonObject { put("url", "https://example.org/") }))
    }
}
