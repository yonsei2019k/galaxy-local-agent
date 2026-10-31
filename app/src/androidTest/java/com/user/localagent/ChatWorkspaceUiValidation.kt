package com.user.localagent

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.ui.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Rendered injectable UI fixtures only: no real chat CRUD, files, models, or external actions. */
@RunWith(AndroidJUnit4::class)
class ChatWorkspaceUiValidation {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Before fun approved(){assumeTrue("chatWorkspaceUiApproved=true required",InstrumentationRegistry.getArguments().getString("chatWorkspaceUiApproved")=="true")}

    @Test fun navigatorBodySearchAndCapturedRoomActionsUseStableIds(){
        val rooms=mutableStateOf(listOf(UiThread("a","p","Synthetic A"),UiThread("b","p","Synthetic B")))
        val selected=mutableStateOf("a");val renames=mutableListOf<Pair<String,String>>()
        compose.activity.setContentForFixture {
            ChatNavigator(ChatNavigation(listOf(UiProject("p","Synthetic project")),rooms.value,"p",selected.value,
                ChatNavigationActions(onSelectThread={selected.value=it},onNewThread={},
                    onRenameThread={id,title->renames+=id to title;rooms.value=rooms.value.map{if(it.id==id)it.copy(title=title)else it}},
                    onPinThread={id,pinned->rooms.value=rooms.value.map{if(it.id==id)it.copy(pinned=pinned)else it}},
                    onArchiveThread={id,archived->rooms.value=rooms.value.map{if(it.id==id)it.copy(archived=archived)else it}},
                    onSearchThreads={term->rooms.value.filter{it.title.contains(term,true)||it.id=="b"&&term=="SYNTHETIC_BODY_NEEDLE"}.mapTo(hashSetOf()){it.id}}),supportsTrash=false),{},{},{})
        }
        compose.onNodeWithTag("thread_search").performTextInput("SYNTHETIC_BODY_NEEDLE")
        compose.waitUntil(10_000){compose.onAllNodesWithTag("thread_b").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("thread_a").assertDoesNotExist()
        compose.onNodeWithTag("thread_search").performTextClearance()
        compose.onNodeWithContentDescription("대화 작업 · Synthetic A").performClick()
        compose.onNodeWithText("이름 변경").performClick()
        compose.runOnIdle{selected.value="b"}
        compose.onNodeWithText("이름").performTextReplacement("Synthetic renamed A")
        compose.onNodeWithText("저장").performClick()
        compose.runOnIdle{assertEquals(listOf("a" to "Synthetic renamed A"),renames)}
        compose.onNodeWithContentDescription("대화 작업 · Synthetic renamed A").performClick()
        compose.onNodeWithText("고정",substring=false).performClick()
        compose.runOnIdle{assertTrue(rooms.value.first{it.id=="a"}.pinned)}
        compose.onNodeWithContentDescription("대화 작업 · Synthetic renamed A").performClick()
        compose.onNodeWithText("보관",substring=false).performClick()
        compose.onNodeWithText("보관함",substring=false).performClick()
        compose.onNodeWithTag("thread_a").assertIsDisplayed()
        compose.onNodeWithContentDescription("대화 작업 · Synthetic renamed A").performClick()
        compose.onNodeWithText("보관 해제").performClick()
        compose.runOnIdle{assertFalse(rooms.value.first{it.id=="a"}.archived)}
    }

    @Test fun projectFormKeepsUnsavedInstructionsAcrossSavedStateRestoreAndFolderCallback(){
        val restore=restorationTesterInActivity(compose,compose.activity);var folder:String?=null
        restore.setContent{MaterialTheme{
            ChatNavigator(ChatNavigation(listOf(UiProject("p","Synthetic project")),emptyList(),"p",null,
                ChatNavigationActions(onSelectThread={},onNewThread={},onChooseProjectFolder={folder=it})),{},{},{})
        }}
        compose.onNodeWithContentDescription("Synthetic project 프로젝트 설정").performClick()
        compose.onNodeWithText("이 프로젝트의 응답 지침").performTextInput("SYNTHETIC_UNSAVED_INSTRUCTIONS")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("이 프로젝트의 응답 지침").assertTextContains("SYNTHETIC_UNSAVED_INSTRUCTIONS")
        compose.onNodeWithText("휴대전화 폴더 선택").performScrollTo().performClick()
        compose.runOnIdle{assertEquals("p",folder)}
        compose.onNodeWithText("이 프로젝트의 응답 지침").assertTextContains("SYNTHETIC_UNSAVED_INSTRUCTIONS")
    }

    @Test fun middleAnswerBlockRestoresAcrossRoomSwitchSavedStateAndPrependedHistory(){
        val selected=mutableStateOf("a");val older=mutableStateOf(false)
        val saved=mutableMapOf("a" to UiChatViewport("a","branch-a","long-a",0,4),"b" to UiChatViewport("b","branch-b"))
        val body="SYNTHETIC_LONG_LINE\n".repeat(2500)
        val restore=restorationTesterInActivity(compose,compose.activity)
        restore.setContent{MaterialTheme{
            val room=selected.value
            val prefix=if(older.value&&room=="a")List(10){ChatLine("older-$it","user","Synthetic older $it")}else emptyList()
            val messages=if(room=="a")prefix+listOf(ChatLine("owner-a","user","Synthetic owner"),ChatLine("long-a","assistant",body))else listOf(ChatLine("owner-b","user","Synthetic B"))
            ChatWorkspace(messages,false,"Synthetic fixture",emptyList(),onModelChange={},onSend={false},onStop={},onNavigate={},
                composerKey=room,currentBranchId="branch-$room",draftValue="",onDraftChange={},
                viewport=saved.getValue(room),onViewportChange={view->saved[view.threadId]=view})
        }}
        fun firstIndex()=compose.onNodeWithTag("chat_messages").fetchSemanticsNode().config[ChatFirstVisibleItemIndex]
        fun awaitIndex(expected:Int,phase:String){
            try{compose.waitUntil(10_000){firstIndex()==expected}}
            catch(failure:ComposeTimeoutException){
                val node=compose.onNodeWithTag("chat_messages").fetchSemanticsNode()
                val actual=node.config[ChatFirstVisibleItemIndex];val offset=node.config[ChatFirstVisibleItemOffset]
                val view=saved[selected.value]
                throw AssertionError("$phase: expectedItem=$expected, actualItem=$actual, offsetPx=$offset, anchorMessage=${view?.messageId}, anchorBlock=${view?.blockIndex}",failure)
            }
        }
        awaitIndex(5,"initial restore")
        compose.waitUntil(10_000){saved["a"]?.messageId=="long-a"&&saved["a"]?.blockIndex==4}
        compose.runOnIdle{selected.value="b"};compose.waitForIdle()
        compose.runOnIdle{selected.value="a"}
        awaitIndex(5,"room switch restore")
        restore.emulateSavedInstanceStateRestore()
        awaitIndex(5,"saved state restore")
        compose.runOnIdle{older.value=true}
        awaitIndex(15,"prepend history")
        compose.runOnIdle{assertEquals("long-a",saved["a"]?.messageId);assertEquals(4,saved["a"]?.blockIndex);assertEquals("b",saved["b"]?.threadId)}
    }

    @Test fun pendingRoomSelectionDisablesComposerControlsButKeepsStopAvailable(){
        val ready=mutableStateOf(false);val draft=mutableStateOf("SYNTHETIC_A_OLD_TEXT")
        val expectedRoom="b";val edits=mutableListOf<Pair<String,String>>();var stops=0
        compose.activity.setContentForFixture {
            ChatWorkspace(emptyList(),true,"Synthetic room loading",emptyList(),onModelChange={error("Loading selection must not change a model")},
                onSend={error("Loading selection must not send")},onStop={stops++},onNavigate={},composerKey=expectedRoom,
                draftValue=draft.value,onDraftChange={edits+=expectedRoom to it;draft.value=it},
                onQueue={error("Loading selection must not queue")},onAttach={error("Loading selection must not attach")},composerReady=ready.value)
        }
        compose.onNodeWithTag("chat_input").assertIsNotEnabled().assertEditableValue("")
        compose.onNodeWithTag("send_button").assertIsNotEnabled()
        compose.onNodeWithTag("model_selector").assertIsNotEnabled()
        compose.onNodeWithTag("attach_button").assertIsNotEnabled()
        compose.onNodeWithTag("stop_button").assertIsEnabled().performClick()
        compose.runOnIdle{assertEquals(1,stops);assertTrue(edits.isEmpty());draft.value="SYNTHETIC_B_DRAFT";ready.value=true}
        compose.onNodeWithTag("chat_input").assertIsEnabled().assertEditableValue("SYNTHETIC_B_DRAFT")
        compose.onNodeWithTag("chat_input").performTextReplacement("SYNTHETIC_B_EDIT")
        compose.runOnIdle{assertEquals(listOf("b" to "SYNTHETIC_B_EDIT"),edits)}
    }

    @Test fun pastedKoreanOverSendLimitRemainsWholeAndShowsAnExplicitError(){
        val draft=mutableStateOf("");val pasted="가".repeat(12_001);var sends=0
        compose.activity.setContentForFixture {
            ChatWorkspace(emptyList(),false,"Synthetic long paste",emptyList(),onModelChange={},onSend={sends++;true},onStop={},onNavigate={},
                composerKey="synthetic-long-paste",draftValue=draft.value,onDraftChange={draft.value=it})
        }
        compose.onNodeWithTag("chat_input").performTextInput(pasted)
        compose.onNodeWithTag("chat_input").assertEditableValue(pasted)
        compose.onNodeWithTag("send_button").assertIsNotEnabled()
        val warning=compose.onNodeWithTag("chat_input_error")
        try{warning.assertIsDisplayed()}
        catch(failure:AssertionError){
            val bounds=warning.fetchSemanticsNode().boundsInRoot
            val input=compose.onNodeWithTag("chat_input").fetchSemanticsNode().boundsInRoot
            val send=compose.onNodeWithTag("send_button").fetchSemanticsNode().boundsInRoot
            val root=compose.onRoot().fetchSemanticsNode().boundsInRoot
            throw AssertionError("Paste warning hidden: draftChars=${draft.value.length}, warningBounds=$bounds, inputBounds=$input, sendBounds=$send, rootBounds=$root",failure)
        }
        warning.assertTextContains("파일로 첨부",substring=true)
        compose.runOnIdle{assertTrue("Draft must preserve all 12,001 synthetic characters; actualChars=${draft.value.length}",pasted==draft.value);assertEquals(0,sends)}
    }
}

private fun MainActivity.setContentForFixture(content:@androidx.compose.runtime.Composable ()->Unit){
    runOnUiThread{setContent{MaterialTheme{content()}}}
}
