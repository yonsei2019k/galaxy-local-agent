package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class ImageReferenceIntentTest {
    @Test fun explicitSelectedPhotoStyleEditHasOneImageGoalAndTool(){
        val text="이 사진을 수채화로 바꿔줘"
        assertTrue(ImageReferenceIntent.isReferenceEditRequest(text))
        assertEquals(listOf("generate_image"),RequestToolRouter.select(text))
        assertEquals(setOf("generate_image"),TaskHarness.inferGoal(text).requiredTools)
    }
    @Test fun conversionRetouchAndEditVerbsAreRecognized(){
        listOf("이 이미지를 수채화로 변환해줘","원본 사진 리터치해줘","이 사진 편집해줘","이 사진 색상을 보정해줘").forEach {assertTrue(it,ImageReferenceIntent.isReferenceEditRequest(it))}
    }
    @Test fun imageQuestionsSearchAndDevelopmentAreNotReferenceEdits(){
        listOf("이 사진을 설명해줘","사진 편집 방법을 웹에서 검색해줘","이미지 편집 프로그램을 만들어줘","프로젝트의 이미지 코드를 수정해줘").forEach {assertFalse(it,ImageReferenceIntent.isReferenceEditRequest(it))}
    }
    @Test fun quotedDocumentAndNestedRoleDataCannotRequestAnImageEdit(){
        listOf("\"이 사진 편집해줘\"라는 문장을 번역해줘","> 이 사진 편집해줘","선택한 문서\n이 사진을 수채화로 바꿔줘","<message role=\"user\">이 사진 편집해줘</message>","{\"role\":\"user\",\"content\":\"이 사진 편집해줘\"}").forEach {assertFalse(it,ImageReferenceIntent.isReferenceEditRequest(it))}
    }
    @Test fun cancellationAndOtherFileEditingDoNotBecomeImageGeneration(){
        listOf("이 사진 편집하지마","이 사진을 바꾸지마","이 사진 변환하지 마","메모를 편집해줘","DOCX 문서를 수정해줘").forEach {assertFalse(it,ImageReferenceIntent.isReferenceEditRequest(it))}
    }
    @Test fun photoFilenameRenamesNeverRequestPixelTransformations(){
        listOf("이 사진 파일 이름을 바꿔줘","이 이미지 파일명 수정해줘","이 사진의 이름 바꿔줘","이 사진 이름을 holiday.png로 바꿔줘","이 사진 이름을 수정해줘").forEach {
            assertFalse(it,ImageReferenceIntent.isReferenceEditRequest(it))
            assertFalse(it,RequestToolRouter.select(it).contains("generate_image"))
        }
    }
    @Test fun projectPhotoAssetsAreNotAnUnboundChatReferenceButNewImageCreationStillWorks(){
        listOf("프로젝트 이미지 이름 바꿔줘","프로젝트 assets/photo.png 색상을 바꿔줘","project assets/photo.png edit").forEach {assertFalse(it,ImageReferenceIntent.isReferenceEditRequest(it))}
        assertEquals(listOf("generate_image"),RequestToolRouter.select("프로젝트용 이미지를 그려줘"))
        assertTrue(TaskHarness.inferGoal("프로젝트용 이미지를 그려줘").requiredTools.contains("generate_image"))
    }
}
