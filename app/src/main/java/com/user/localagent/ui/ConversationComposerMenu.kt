package com.user.localagent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal enum class ConversationComposerAction { ATTACH, BROWSE_SKILLS, VIEW_GOAL }

/** Optional reference actions. Availability never selects an agent mode or grants tool authority. */
internal fun conversationComposerActions(hasSkills:Boolean,hasGoalManager:Boolean,activeGoalLabel:String?):List<ConversationComposerAction> = buildList {
    add(ConversationComposerAction.ATTACH)
    if(hasSkills)add(ConversationComposerAction.BROWSE_SKILLS)
    if(hasGoalManager&&!activeGoalLabel.isNullOrBlank())add(ConversationComposerAction.VIEW_GOAL)
}

/** Scope keys close an open menu when a room, branch, or selection readiness changes. */
@Composable
internal fun ConversationComposerMenu(
    composerKey:String,branchKey:String,enabled:Boolean,
    onAttach:()->Unit,onShowSkills:(()->Unit)?,onManageGoal:(()->Unit)?,activeGoalLabel:String?,
) {
    var expanded by remember(composerKey,branchKey,enabled){mutableStateOf(false)}
    val origin=composerKey to branchKey
    val currentOrigin by rememberUpdatedState(origin)
    val currentEnabled by rememberUpdatedState(enabled)
    val actions=conversationComposerActions(onShowSkills!=null,onManageGoal!=null,activeGoalLabel)
    fun invoke(action:()->Unit){
        expanded=false
        if(currentEnabled&&currentOrigin==origin)action()
    }
    Box(Modifier.padding(bottom=6.dp)){
        IconButton(enabled=enabled,onClick={expanded=true},modifier=Modifier.size(40.dp).testTag("attach_button")
            .semantics{contentDescription="첨부·대화 도구"}){
            Text("+",style=MaterialTheme.typography.headlineSmall,color=ZipHapColors.TextPrimary)
        }
        DropdownMenu(expanded=expanded&&enabled,onDismissRequest={expanded=false},modifier=Modifier.testTag("conversation_actions_menu")){
            actions.forEach{action->when(action){
                ConversationComposerAction.ATTACH->DropdownMenuItem(
                    text={Text("파일·사진·영상 첨부")},onClick={invoke(onAttach)},modifier=Modifier.testTag("conversation_attach"))
                ConversationComposerAction.BROWSE_SKILLS->DropdownMenuItem(
                    text={Text("스킬 찾아보기")},onClick={invoke{onShowSkills?.invoke()}},modifier=Modifier.testTag("conversation_browse_skills"))
                ConversationComposerAction.VIEW_GOAL->DropdownMenuItem(
                    text={Column{
                        Text("대화의 목표 보기")
                        Text(activeGoalLabel.orEmpty().take(120),style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }},onClick={invoke{onManageGoal?.invoke()}},modifier=Modifier.testTag("conversation_view_goal"))
            }}
        }
    }
}
