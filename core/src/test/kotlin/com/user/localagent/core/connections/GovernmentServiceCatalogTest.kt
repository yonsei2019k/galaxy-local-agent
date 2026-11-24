package com.user.localagent.core.connections

import com.user.localagent.core.PreparedHandoff
import com.user.localagent.core.ToolCall
import com.user.localagent.core.ToolReadPolicy
import com.user.localagent.core.ToolRegistry
import com.user.localagent.core.VerificationState
import java.net.URI
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GovernmentServiceCatalogTest {
    private fun call(name: String, service: String) =
        ToolCall("test", name, buildJsonObject { put("service", service) })

    @Test fun lookingUpAnInstitutionReturnsOnlyTheOfficialDestinationAndAnActionHint() {
        for (service in GovernmentService.entries) {
            val request = call(GovernmentServiceCatalog.GET, service.id)
            ToolRegistry.validate(request)
            val result = GovernmentServiceCatalog.read(request)
            val json = Json.parseToJsonElement(result.content).jsonObject
            assertEquals(VerificationState.VERIFIED, result.verification)
            assertEquals(service.url, json.getValue("officialUrl").jsonPrimitive.content)
            assertEquals("official_link", json.getValue("status").jsonPrimitive.content)
            assertEquals("https", URI(service.url).scheme)
            assertFalse(json.keys.any { it in setOf("payment", "record", "receipt") })
        }
        assertTrue(ToolReadPolicy.isReadOnly(GovernmentServiceCatalog.GET))
        assertFalse(ToolReadPolicy.isReadOnly(GovernmentServiceCatalog.OPEN))
        assertTrue(PreparedHandoff.opensScreenOnly(GovernmentServiceCatalog.OPEN))
    }

    @Test fun onlyARegisteredServiceCanBecomeAPhoneDestination() {
        val request = call(GovernmentServiceCatalog.OPEN, "traffic_fine")
        assertEquals("www.efine.go.kr", URI(GovernmentServiceCatalog.target(request).url).host)
        val bad = listOf(
            call(request.name, "https://example.org"),
            call(request.name, "traffic_fine?notice=123"),
            request.copy(arguments = JsonObject(request.arguments + ("url" to JsonPrimitive("https://example.org")))),
            request.copy(arguments = buildJsonObject { put("service", 123) }),
        )
        for (invalid in bad) {
            assertTrue(runCatching { GovernmentServiceCatalog.target(invalid) }.isFailure)
        }
    }
}
