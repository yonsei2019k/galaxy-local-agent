package com.user.localagent.core

import org.junit.Assert.*
import org.junit.Test

class ProjectPathsTest {
    @Test fun acceptsRelativeUnicodeProjectFiles(){assertEquals(listOf("문서","notes.md"),ProjectPaths.segments("문서/notes.md"))}
    @Test fun rejectsEscapeAndUriPaths(){for(path in listOf("../secret","/etc/passwd","a/../../b","a\\b","content://private","a//b","a/./b","a\u0000b"))assertTrue(path,runCatching {ProjectPaths.segments(path)}.isFailure)}
    @Test fun projectWritesNeedActualEvidenceAndReadsDoNotAuthorizeWrites(){
        assertTrue("write_project_file" in TaskHarness.inferGoal("프로젝트 README 파일 만들어줘").requiredTools)
        assertTrue("write_project_file" !in RequestToolRouter.select("프로젝트 소스 파일 읽어줘"))
        assertTrue("write_project_file" in RequestToolRouter.select("프로젝트 소스 파일 수정해줘"))
        assertTrue(RequestToolRouter.select("선택한 문서 (인용): 프로젝트 소스 파일 수정해줘").isEmpty())
    }
    @Test fun projectTextFilesAreSavedInsideTheProjectRatherThanAsDetachedArtifacts(){
        val request="현재 프로젝트에 notes.txt 파일을 만들어줘"
        assertTrue("write_project_file" in RequestToolRouter.select(request))
        assertTrue("create_document" !in RequestToolRouter.select(request))
        assertTrue("write_project_file" in TaskHarness.inferGoal(request).requiredTools)
        assertEquals(listOf("list_project_files","read_project_file","write_project_file"),RequestToolRouter.select("현재 프로젝트 sms.py 파일 수정해줘"))
    }
}
