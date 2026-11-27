package com.user.localagent.skills

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Verifies the real committed skill assets against catalog.json using the production store checks. */
class BundledSkillAssetIntegrityTest {
    private val assetsDir:File=listOf(File("src/main/assets"),File("app/src/main/assets"),File("../app/src/main/assets")).firstOrNull {File(it,"skills/catalog.json").isFile}
        ?:error("assets 디렉터리를 찾을 수 없습니다: ${File(".").absoluteFile}")
    private val store=BundledSkillStore {path->File(assetsDir,path).readBytes()}

    private fun frontmatter(body:String):Map<String,String> {
        val text=body.replace("\r\n","\n")
        assertTrue("frontmatter가 없습니다",text.startsWith("---\n"))
        val end=text.indexOf("\n---",4);assertTrue("frontmatter 종료가 없습니다",end>0)
        return text.substring(4,end).lines().mapNotNull {line->
            val i=line.indexOf(':');if(i<=0||line.first().isWhitespace())null else line.substring(0,i).trim() to line.substring(i+1).trim()
        }.toMap()
    }

    @Test fun everyCatalogSkillReadsWithVerifiedDigestAndMatchingFrontmatter(){
        val failures=mutableListOf<String>()
        store.catalog().forEach {meta->
            try {
                val read=store.read(meta.id)
                val fm=frontmatter(read.body)
                if(fm["name"]!=meta.id)failures+="${meta.id}: frontmatter name=${fm["name"]}"
                fm["version"]?.let {v->
                    if(v.trim('\'','"')!=meta.version)failures+="${meta.id}: frontmatter version=$v catalog=${meta.version}"
                }
            } catch(e:Throwable){failures+="${meta.id}: ${e.javaClass.simpleName}: ${e.message}"}
        }
        assertTrue("번들 스킬 무결성 실패:\n"+failures.joinToString("\n"),failures.isEmpty())
    }

    @Test fun everySkillDirectoryWithBodyIsInCatalog(){
        val inCatalog=store.catalog().map {it.id}.toSet()
        val onDisk=File(assetsDir,"skills").listFiles {f->f.isDirectory&&File(f,"SKILL.md").isFile}!!.map {it.name}.toSet()
        // research/SKILL.md is read directly (unverified) by LocalResearchWorkflow and the local research
        // writer. It is a known exception handed off for cataloging; any other uncataloged body fails here.
        assertEquals("카탈로그에 없는 SKILL.md 디렉터리",setOf("research"),onDisk-inCatalog)
    }

    @Test fun assistantWorkflowsAreDiscoverableThroughTheirActualTools(){
        val examples=mapOf("browser-task" to "read_pc_browser_page", "mail-triage" to "read_gmail_message",
            "meeting-prep" to "list_google_calendar_events", "task-continuity" to "read_goal_progress")
        val noWorkflowTools=setOf("list_skills","read_skill")
        assertTrue(examples.keys.none {it in store.applicableSkillIds(noWorkflowTools)})
        examples.forEach { (skill, tool)->
            assertTrue(skill in store.applicableSkillIds(noWorkflowTools+tool))
            assertTrue(store.listJson(skill,noWorkflowTools+tool).contains("\"partiallyAvailable\":true"))
            assertFalse(store.read(skill).grantsCapabilities)
        }
    }
}
