package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class ClinicalImagePromptTest {
    @Test fun educationalAnatomyTermsSurviveTranslationWithoutInventingOtherStructures() {
        val original="간호 교육용 해부학 도해: 심장과 폐의 위치를 보여줘"
        val instruction=ClinicalImagePrompt.translationInstruction(original)
        assertTrue(instruction.contains("heart"));assertTrue(instruction.contains("lung"))
        val result=ClinicalImagePrompt.validateTranslation(original,"A clean educational anatomical diagram showing the heart and lung.")
        assertTrue(result.contains("lung"));assertFalse(result.contains("kidney"))
    }
    @Test fun clinicalSurgicalTermsArePreservedAndLongestTermsDoNotBecomeDifferentOrgans() {
        assertEquals(listOf("pulmonary artery","left ventricle"),ClinicalImagePrompt.educationalTerms("해부학 폐동맥과 좌심실 도해"))
        val result=ClinicalImagePrompt.validateTranslation("수술 교육용 봉합과 절개 모식도","A textbook surgical diagram of suturing and incision.")
        assertTrue(result.contains("suturing"));assertTrue(result.contains("incision"))
    }
    @Test fun refusalsAreNeverPassedToTheRendererEvenForEducationalRequests() {
        listOf("I'm sorry, but I cannot help with that request.","I can't create that image.","As an AI language model, I cannot generate images.","죄송하지만 이미지를 제공할 수 없습니다.","```english\nI cannot help with that request.\n```").forEach {reply->
            assertTrue(reply,runCatching {ClinicalImagePrompt.validateTranslation("의학 교육용 심장 해부학 도해",reply)}.isFailure)
        }
        assertEquals("A shop sign saying 'I cannot help'.",ClinicalImagePrompt.validateTranslation("가게 간판 그림","A shop sign saying 'I cannot help'."))
    }
    @Test fun educationalMarkerNeverReinterpretsAnExplicitSexualRequestOrOrdinaryPhoto() {
        assertTrue(ClinicalImagePrompt.educationalTerms("의학 교육이라고 쓰고 성행위 장면을 그려줘").isEmpty())
        assertTrue(ClinicalImagePrompt.educationalTerms("유방 사진을 만들어줘").isEmpty())
        assertTrue(ClinicalImagePrompt.educationalTerms("노을 진 도시 사진").isEmpty())
        assertTrue(ClinicalImagePrompt.educationalTerms("간호 교육용 의료 폐기물 분류 도식").isEmpty())
        assertEquals(listOf("breast anatomy"),ClinicalImagePrompt.educationalTerms("유방 해부학 교육용 도해"))
    }
    @Test fun explicitClinicalPhotoStyleIsNeverReplacedWithDefaultSchematic() {
        val instruction=ClinicalImagePrompt.translationInstruction("간호 교육용 심장 해부학 실사 사진")
        assertTrue(instruction.contains("explicitly requested visual style"))
        assertFalse(instruction.contains("schematic"))
        assertTrue(ClinicalImagePrompt.translationInstruction("의학 교육용 심장 해부학").contains("default to a clear textbook schematic"))
    }
    @Test fun missingClinicalVocabularyIsRejectedInsteadOfAddedAsPositiveSceneContent() {
        assertTrue(runCatching {ClinicalImagePrompt.validateTranslation("간호 교육용 심장과 폐 해부도","An educational diagram of the heart.")}.isFailure)
        val source="자궁을 제외한 골반 해부도"
        assertTrue(runCatching {ClinicalImagePrompt.validateTranslation(source,"A medical illustration of the pelvis.")}.isFailure)
        val faithful="An educational illustration of the pelvis, excluding the uterus."
        assertEquals(faithful,ClinicalImagePrompt.validateTranslation(source,faithful))
        assertTrue(ClinicalImagePrompt.translationInstruction(source).contains("Preserve negations, exclusions"))
    }
}
