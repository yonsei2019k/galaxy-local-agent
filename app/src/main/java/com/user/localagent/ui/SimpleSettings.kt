package com.user.localagent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

internal enum class SettingsPage(val title: String) {
    HOME("설정"),
    CONNECTIONS("앱과 컴퓨터 연결"),
    AUTOMATION("자동으로 도와주기"),
    PRIVACY("개인정보와 권한"),
    UPDATES("앱 업데이트"),
}

@Composable
internal fun SettingsEntry(title: String, description: String, onOpen: () -> Unit) {
    OutlinedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val tint = MaterialTheme.colorScheme.primary
            Canvas(Modifier.size(12.dp)) {
                val inner = size.width * if (layoutDirection == LayoutDirection.Ltr) .35f else .65f
                val outer = size.width - inner
                val tip = Offset(outer, size.height / 2)
                val stroke = 1.8.dp.toPx()
                drawLine(tint, Offset(inner, size.height * .22f), tip, stroke, StrokeCap.Round)
                drawLine(tint, tip, Offset(inner, size.height * .78f), stroke, StrokeCap.Round)
            }
        }
    }
}

@Composable
internal fun SettingsDisclosure(title: String, description: String, content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    if (!expanded) {
        SettingsEntry(title, description) { expanded = true }
    } else {
        ZipHapCard {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ZipHapSectionTitle(title, Modifier.weight(1f))
                    TextButton(onClick = { expanded = false }) {
                        Text("접기", Modifier.clearAndSetSemantics { contentDescription = "$title 접기" })
                    }
                }
                content()
            }
        }
    }
}
