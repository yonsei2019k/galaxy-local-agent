package com.user.localagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.user.localagent.core.ImageConsistencyRecipe

@Composable
fun ImageSeriesPicker(items:List<ImageConsistencyRecipe>,enabled:Boolean,onDismiss:()->Unit,
    onLoad:(ImageConsistencyRecipe)->Unit,onDelete:(ImageConsistencyRecipe)->Unit){
    var deleting by remember{mutableStateOf<ImageConsistencyRecipe?>(null)}
    if(deleting==null)AlertDialog(onDismissRequest=onDismiss,title={Text("저장한 이미지 시리즈 · ${items.size}/12")},text={
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=400.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            if(items.isEmpty())item{Text("아직 저장한 시리즈가 없습니다. 이름과 조건을 입력해 저장해주세요.")}
            items(items.size){index->val recipe=items[index]
                Column{
                    Text(recipe.title,style=MaterialTheme.typography.titleSmall)
                    Text("시드 ${recipe.seed} · ${if(recipe.referenceName!=null)"기준 사진 있음"else"기준 사진 없음"}",style=MaterialTheme.typography.bodySmall)
                    Row{
                        TextButton(enabled=enabled,onClick={onLoad(recipe)},modifier=Modifier.testTag("series_load_$index")){Text("불러오기")}
                        TextButton(enabled=enabled,onClick={deleting=recipe},modifier=Modifier.testTag("series_remove_$index")){Text("조건 삭제")}
                    }
                    ZipInsetDivider(inset = 0.dp)
                }
            }
        }
    },confirmButton={TextButton(onClick=onDismiss){Text("닫기")}})
    deleting?.let{recipe->AlertDialog(onDismissRequest={deleting=null},title={Text("시리즈 조건을 삭제할까요?")},
        text={Text("${recipe.title}\n저장 조건만 삭제합니다. 원본 사진·기준 사진·생성 결과는 유지됩니다.")},
        confirmButton={TextButton(enabled=enabled&&recipe in items,onClick={onDelete(recipe);deleting=null},modifier=Modifier.testTag("confirm_series_remove")){Text("조건 삭제")}},
        dismissButton={TextButton(onClick={deleting=null}){Text("유지")}})}
}
