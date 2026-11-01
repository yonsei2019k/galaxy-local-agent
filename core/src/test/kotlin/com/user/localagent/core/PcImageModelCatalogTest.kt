package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class PcImageModelCatalogTest {
    @Test fun referenceUsesExplicitEditorWhileBaseChooserStaysTwoModels(){
        assertEquals(PcImageModelCatalog.QUALITY_ID,PcImageModelCatalog.effectiveSelection(PcImageModelCatalog.QUALITY_ID,false).id)
        assertEquals(PcImageModelCatalog.EDIT_ID,PcImageModelCatalog.effectiveSelection(PcImageModelCatalog.QUALITY_ID,true).id)
        assertEquals(PcImageModelCatalog.SDXL_ID,PcImageModelCatalog.effectiveSelection(PcImageModelCatalog.SDXL_ID,true).id)
        assertThrows(IllegalArgumentException::class.java){PcImageModelCatalog.selection(PcImageModelCatalog.EDIT_ID)}
    }
    @Test fun editorCannotBeReportedAsTextImageModel(){
        val edit=PcImageModelCatalog.effectiveSelection(PcImageModelCatalog.QUALITY_ID,true)
        assertThrows(IllegalArgumentException::class.java){PcImageModelCatalog.verifyResult(edit,PcImageModelCatalog.QUALITY_ID,PcImageModelCatalog.QUALITY_SHA)}
        assertEquals(edit,PcImageModelCatalog.verifyResult(edit,PcImageModelCatalog.EDIT_ID,PcImageModelCatalog.EDIT_SHA))
    }
    @Test fun upgradeCannotAcceptUnknownWeightsUnderTrustedModelLabel(){
        assertThrows(IllegalArgumentException::class.java){PcImageModelCatalog.verified(PcImageModelCatalog.QUALITY_ID,"0".repeat(64))}
        assertThrows(IllegalArgumentException::class.java){PcImageModelCatalog.verified("invented-model",PcImageModelCatalog.QUALITY_SHA)}
        assertThrows(IllegalArgumentException::class.java){PcImageModelCatalog.verified(null,null)}
    }
    @Test fun pinnedOlderServerRemainsIdentifiableButCannotReplaceCurrentJobResult(){
        val old=PcImageModelCatalog.selection(PcImageModelCatalog.SDXL_ID)
        assertThrows(IllegalArgumentException::class.java){PcImageModelCatalog.verifyResult(old,PcImageModelCatalog.QUALITY_ID,PcImageModelCatalog.QUALITY_SHA)}
    }
    @Test fun qualityResultMustMatchItsExactPreflightPin(){
        val pin=PcImageModelCatalog.verified(PcImageModelCatalog.QUALITY_ID,PcImageModelCatalog.QUALITY_SHA)
        assertEquals(pin,PcImageModelCatalog.verifyResult(pin,pin.id,pin.sha256))
    }
}
