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

    @Test fun publicSearchParametersMustBelongToTheSelectedSource() {
        fun search(source: String, query: String, day: String? = null) =
            ToolCall("fixture", "search_web", buildJsonObject {
                put("source", source)
                put("query", query)
                day?.let { put("startDate", it) }
            })
        ToolRegistry.validate(search("tago_flights", "GMP-CJU", "2026-12-01"))
        ToolRegistry.validate(search("seoul_reservations", "테니스"))
        assertThrows(IllegalArgumentException::class.java) { ToolRegistry.validate(search("tago_flights", "GMP-CJU")) }
        assertThrows(IllegalArgumentException::class.java) { ToolRegistry.validate(search("seoul_reservations", "테니스", "2026-12-01")) }
    }

    @Test fun officialTargetsAreScreenHandoffs() {
        assertEquals("https://my.kt.com/", LifeServiceCatalog.url(ToolRegistry.validate(open("kt"))))
        assertEquals("https://cont.insure.or.kr/cont_web/", LifeServiceCatalog.url(open("insurance_lookup")))
        assertEquals("https://flyasiana.com/", LifeServiceCatalog.url(open("asiana")))
        assertEquals("https://www.silson24.or.kr/claim/web/", LifeServiceCatalog.url(ToolRegistry.validate(open("silson24"))))
        assertEquals("https://yeyak.seoul.go.kr/web/main.do", LifeServiceCatalog.url(ToolRegistry.validate(open("seoul_reservations"))))
        assertTrue(LifeServiceCatalog.url(ToolRegistry.validate(open("srt_booking"))).startsWith("https://eticket.srail.kr/hpg/hra/01/selectScheduleList.do?"))
        assertTrue(PreparedHandoff.opensScreenOnly("open_url"))
    }

    @Test fun cloudHomeShortcutsStillLaunchTheSameApps() {
        val targets = mapOf("netflix" to "com.netflix.mediaclient",
            "coupang" to "com.coupang.mobile", "coupang_eats" to "com.coupang.mobile.eats")
        for ((service, packageName) in targets) {
            assertEquals(packageName, CommerceListingLinks.homePackage(LifeServiceCatalog.url(open(service))))
        }
    }

    @Test fun serviceIdentifiersCannotPassAnArbitraryUrl() {
        assertThrows(IllegalArgumentException::class.java) { LifeServiceCatalog.url(open("https://example.org/login")) }
        assertThrows(IllegalArgumentException::class.java) { ToolRegistry.validate(open("unknown")) }
        assertThrows(IllegalArgumentException::class.java) { ToolRegistry.validate(ToolCall("fixture", "open_url",
            buildJsonObject { put("service", "kt"); put("url", "https://example.org/") })) }
        ToolRegistry.validate(ToolCall("fixture", "open_url", buildJsonObject { put("url", "https://example.org/") }))
    }
}
