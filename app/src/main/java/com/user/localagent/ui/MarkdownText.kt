package com.user.localagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

private fun annotated(spans:List<MarkdownLite.Span>):AnnotatedString = buildAnnotatedString {
    spans.forEach {span->
        val style=SpanStyle(
            fontWeight=if(span.bold)FontWeight.SemiBold else null,
            fontStyle=if(span.italic)FontStyle.Italic else null,
            fontFamily=if(span.code)FontFamily.Monospace else null,
            background=if(span.code)ZipHapColors.Elevated else androidx.compose.ui.graphics.Color.Unspecified,
        )
        withStyle(style){append(span.text)}
    }
}

/** Renders an assistant answer's Markdown; the underlying answer text is unchanged. */
@Composable
internal fun MarkdownText(text:String,modifier:Modifier=Modifier) {
    val blocks=remember(text){MarkdownLite.parse(text)}
    val body=MaterialTheme.typography.bodyLarge
    Column(modifier,verticalArrangement=Arrangement.spacedBy(6.dp)) {
        blocks.forEach {block->
            when(block){
                is MarkdownLite.Block.Heading->Text(annotated(block.spans),
                    style=if(block.level<=2)MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    fontWeight=FontWeight.SemiBold,color=ZipHapColors.TextPrimary,modifier=Modifier.padding(top=4.dp))
                is MarkdownLite.Block.Paragraph->Text(annotated(block.spans),style=body,color=ZipHapColors.TextPrimary)
                is MarkdownLite.Block.ListItem->Row(Modifier.padding(start=(block.depth*16).dp)) {
                    Text(block.marker,style=body,color=ZipHapColors.TextSecondary,modifier=Modifier.width(if(block.marker=="•")16.dp else 26.dp))
                    Text(annotated(block.spans),style=body,color=ZipHapColors.TextPrimary)
                }
                is MarkdownLite.Block.Quote->Text(annotated(block.spans),style=body,color=ZipHapColors.TextSecondary,
                    modifier=Modifier.padding(start=10.dp))
                is MarkdownLite.Block.Code->Text(block.text,style=MaterialTheme.typography.bodyMedium,fontFamily=FontFamily.Monospace,
                    color=ZipHapColors.TextPrimary,modifier=Modifier.fillMaxWidth().background(ZipHapColors.Elevated,RoundedCornerShape(8.dp)).padding(10.dp))
                is MarkdownLite.Block.Table->Column(Modifier.fillMaxWidth().background(ZipHapColors.Elevated,RoundedCornerShape(8.dp)).padding(8.dp)) {
                    block.rows.forEachIndexed {index,row->
                        Row(Modifier.fillMaxWidth().padding(vertical=3.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            row.forEach {cell->Text(annotated(cell),style=MaterialTheme.typography.bodyMedium,
                                fontWeight=if(index==0)FontWeight.SemiBold else null,color=ZipHapColors.TextPrimary,modifier=Modifier.weight(1f))}
                        }
                        if(index==0)HorizontalDivider(color=ZipHapColors.TextSecondary.copy(alpha=0.3f))
                    }
                }
                MarkdownLite.Block.Rule->HorizontalDivider(color=ZipHapColors.TextSecondary.copy(alpha=0.3f))
            }
        }
    }
}
