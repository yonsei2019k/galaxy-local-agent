package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class ToolCapabilityCatalogTest {
    private fun catalog(env:CapabilityEnvironment=CapabilityEnvironment())=ToolCapabilityCatalog.snapshot(env).associateBy {it.id}
    private fun state(id:String,env:CapabilityEnvironment=CapabilityEnvironment())=catalog(env).getValue(id).availability
    @Test fun everyTypedToolHasOneCapabilityEntry(){
        val entries=ToolCapabilityCatalog.snapshot()
        assertTrue(entries.map {it.id}.containsAll(ToolRegistry.definitions.keys))
        assertEquals(entries.size,entries.map {it.id}.distinct().size)
    }
    @Test fun localDatabaseToolsDoNotInventPermissionOrNetworkDependencies(){
        for(id in listOf("save_note","search_notes","save_memory","search_memories","search_conversation_history")){
            val tool=catalog().getValue(id);assertEquals(CapabilityAvailability.INSTALLED,tool.availability);assertTrue(tool.offline)
        }
    }
    @Test fun androidDataPermissionsAreNotAssumedFromAnInstalledApk(){
        for(id in listOf("get_sms_summary","get_call_log_summary","search_contacts","list_calendar_events","list_audio_recordings"))
            assertEquals(id,CapabilityAvailability.REQUIRES_PERMISSION,state(id))
        assertEquals(CapabilityAvailability.INSTALLED,state("get_sms_summary",CapabilityEnvironment(grantedPermissions=setOf("android.permission.READ_SMS"))))
    }
    @Test fun audioPermissionTracksActualAndroidApi(){
        val older=CapabilityEnvironment(apiLevel=31,grantedPermissions=setOf("android.permission.READ_EXTERNAL_STORAGE"))
        assertEquals(CapabilityAvailability.INSTALLED,state("list_audio_recordings",older))
        assertEquals(CapabilityAvailability.REQUIRES_PERMISSION,state("list_audio_recordings",older.copy(apiLevel=37)))
    }
    @Test fun imageGenerationRequiresItsSeparateImageWeights(){
        assertEquals(CapabilityAvailability.REQUIRES_MODEL,state("generate_image",CapabilityEnvironment(installedModels=setOf("text"))))
        assertEquals(CapabilityAvailability.INSTALLED,state("generate_image",CapabilityEnvironment(installedModels=setOf("image"))))
    }
    @Test fun draftedMailIsNotPresentedAsDirectSending(){
        val capability=catalog(CapabilityEnvironment(installedHandlers=setOf("email"))).getValue("prepare_email")
        assertEquals(CapabilityAvailability.INSTALLED,capability.availability)
        assertTrue(capability.requiresConfirmation);assertTrue(capability.limitation.contains("발송"))
        assertEquals(CapabilityAvailability.UNVALIDATED,state("prepare_email"))
    }
    @Test fun selectedInputAndOutputGrantsAreSeparate(){
        val input=CapabilityEnvironment(selectedDocumentAvailable=true)
        assertEquals(CapabilityAvailability.INSTALLED,state("email.read_eml",input))
        assertEquals(CapabilityAvailability.REQUIRES_PERMISSION,state("document.write",input))
        assertEquals(CapabilityAvailability.REQUIRES_PERMISSION,state("email.read_eml",input.copy(selectedDocumentAvailable=false,selectedOutputAvailable=true)))
    }
    @Test fun PDFMetadataDoesNotPromiseUnsupportedOcrOrOldAndroidTextExtraction(){
        val capability=catalog(CapabilityEnvironment(apiLevel=34)).getValue("pdf.inspect")
        assertTrue(capability.limitation.contains("Android15+"))
        assertTrue(catalog().getValue("pdf.inspect").limitation.contains("OCR 없음"))
    }
    @Test fun notificationGrantAloneDoesNotValidateEveryMessenger(){
        val env=CapabilityEnvironment(grantedPermissions=setOf("notification_access"),validatedConnectors=setOf("com.kakao.talk"))
        assertEquals(CapabilityAvailability.INSTALLED,state("connector.com.kakao.talk",env))
        assertEquals(CapabilityAvailability.UNVALIDATED,state("connector.org.telegram.messenger",env))
        assertEquals(CapabilityAvailability.UNVALIDATED,state("connector.com.meditong.gw",env))
    }
    @Test fun connectorValidationCannotReplaceARevokedSystemGrant(){
        val env=CapabilityEnvironment(validatedConnectors=setOf("com.kakao.talk"))
        assertEquals(CapabilityAvailability.REQUIRES_PERMISSION,state("connector.com.kakao.talk",env))
        assertEquals(CapabilityAvailability.REQUIRES_PERMISSION,state("list_messenger_notifications",env))
    }
    @Test fun notificationCapabilitiesDoNotClaimAllHistoricalMessages(){
        assertTrue(catalog().getValue("list_messenger_notifications").limitation.contains("전체 과거 대화 접근이 아닙니다"))
        assertEquals(CapabilityAvailability.NOT_IMPLEMENTED,state("apps.universal_control"))
    }
    @Test fun specialUsageAndScreenAccessAreSeparatelyReported(){
        assertEquals(CapabilityAvailability.REQUIRES_PERMISSION,state("usage.local_reflection"))
        assertEquals(CapabilityAvailability.INSTALLED,state("usage.local_reflection",CapabilityEnvironment(grantedPermissions=setOf("usage_access"))))
        assertEquals(CapabilityAvailability.UNVALIDATED,state("connector.screen",CapabilityEnvironment(grantedPermissions=setOf("screen_access"))))
    }
    @Test fun modelRemovalDisablesPreviouslyValidatedTranscription(){
        val env=CapabilityEnvironment(validatedCapabilities=setOf("audio.transcribe"))
        assertEquals(CapabilityAvailability.REQUIRES_MODEL,state("audio.transcribe",env))
        assertEquals(CapabilityAvailability.INSTALLED,state("audio.transcribe",env.copy(installedModels=setOf("asr"))))
    }
    @Test fun unsupportedExecutableFeaturesAreNeverReportedInstalled(){
        for(id in listOf("video.generate","email.mailbox_sync","mcp.executable_host"))assertEquals(id,CapabilityAvailability.NOT_IMPLEMENTED,state(id))
    }
    @Test fun metadataNeverGrantsSilentHighRiskExecution(){
        val capabilities=catalog()
        ToolRegistry.definitions.values.filter {it.risk!=Risk.LOW}.forEach {assertTrue(it.name,capabilities.getValue(it.name).requiresConfirmation)}
        assertFalse(capabilities.getValue("search_web").offline)
    }
}
