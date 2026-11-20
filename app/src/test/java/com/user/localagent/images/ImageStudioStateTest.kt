package com.user.localagent.images

import org.junit.Assert.*
import org.junit.Test

class ImageStudioStateTest {
    @Test fun hiddenResultDoesNotReappearOnRestoreOrDuringAnotherAttempt(){
        val hidden=completed().copy(outputHidden=true)
        assertNull(hidden.savedOutput(restored=true).previewPath)
        assertNull(hidden.beginAttempt().previewPath)
        assertEquals(hidden.outputPath,hidden.copy(outputHidden=false).previewPath)
        assertEquals("new.png",hidden.copy(outputPath="new.png").savedOutput().previewPath)
    }
    private fun completed()=ImageStudioState(outputPath="synthetic-prior.png",seed=73,sourceModelId="fixture-old-model",
        computeLocation="pc",mode="img2img",referenceSha256="a".repeat(64),referenceWidth=800,referenceHeight=600,
        strength=.5f,progress=1f,step=50,totalSteps=50).savedOutput()
    @Test fun startingAnotherAttemptRetainsPriorFileAndItsOwnMetadataWithoutCompletedProgress(){
        val prior=completed()
        val started=prior.beginAttempt().copy(running=true,prompt="new fixture",mode="txt2img",referenceSha256=null,
            referenceWidth=null,referenceHeight=null,strength=null,seed=99,sourceModelId="fixture-new-selection")
        assertEquals(prior.outputPath,started.outputPath)
        assertEquals(prior.displayedOutput,started.displayedOutput)
        assertEquals(73L,started.displayedOutput?.seed)
        assertEquals("fixture-old-model",started.displayedOutput?.sourceModelId)
        assertEquals(ImageOutputOrigin.PREVIOUS,started.outputOrigin)
        assertEquals("이전 작업의 결과",started.outputTitle)
        assertEquals(0f,started.progress,0f);assertEquals(0,started.step)
    }
    @Test fun pauseFailureAndRetryCannotPromoteOldResultToTheCurrentRequest(){
        val prior=completed();val started=prior.beginAttempt().copy(running=true,mode="txt2img",referenceSha256=null)
        for(phase in listOf("PC 작업 일시정지","이미지를 만들지 못했습니다","중단했습니다")){
            val stopped=started.copy(running=false,phase=phase,error="synthetic status")
            assertEquals(ImageOutputOrigin.PREVIOUS,stopped.outputOrigin)
            assertEquals(prior.displayedOutput,stopped.displayedOutput)
            assertEquals(prior.displayedOutput,stopped.beginAttempt().displayedOutput)
        }
    }
    @Test fun durableNewResultReplacesOnlyTheDisplayedResultSnapshotAndBecomesCurrent(){
        val updated=completed().beginAttempt().copy(outputPath="synthetic-new.png",seed=99,sourceModelId="fixture-new-model",
            computeLocation="phone",mode="txt2img",referenceSha256=null,referenceWidth=null,referenceHeight=null,strength=null,
            progress=1f,step=4,totalSteps=4).savedOutput()
        assertEquals(ImageOutputOrigin.CURRENT,updated.outputOrigin)
        assertEquals("완성한 이미지",updated.outputTitle)
        assertEquals(99L,updated.displayedOutput?.seed)
        assertEquals("fixture-new-model",updated.displayedOutput?.sourceModelId)
        assertNull(updated.displayedOutput?.referenceSha256)
    }
    @Test fun restoredOutputIsRecentHistoryAndEmptyStateCannotInventAnOutput(){
        val restored=completed().savedOutput(restored=true)
        assertEquals(ImageOutputOrigin.RESTORED,restored.outputOrigin)
        assertEquals("최근 완성한 이미지",restored.outputTitle)
        assertEquals(ImageOutputOrigin.PREVIOUS,restored.beginAttempt().outputOrigin)
        assertNull(ImageStudioState().beginAttempt().displayedOutput)
        assertNull(ImageStudioState().beginAttempt().outputPath)
    }
}
