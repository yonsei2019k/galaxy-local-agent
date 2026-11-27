package com.user.localagent.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal enum class SettingsPage(val title: String) {
    HOME("설정"),
    CONNECTIONS("앱과 컴퓨터 연결"),
    AUTOMATION("자동으로 도와주기"),
    PRIVACY("개인정보와 권한"),
    UPDATES("앱 업데이트"),
}

/** A settings destination as one white card with an icon tile row (2.0); grouped pages use [SettingsRow]. */
@Composable
internal fun SettingsEntry(
    title: String,
    description: String,
    @DrawableRes icon: Int? = null,
    tone: ZipTone = ZipTone.Cobalt,
    onOpen: () -> Unit,
) {
    ZipCard(Modifier.fillMaxWidth()) {
        SettingsRow(title, description, icon, tone, onOpen)
    }
}

/** One navigating row inside a settings [ZipSection]: icon tile, title, grey description and a chevron. */
@Composable
internal fun SettingsRow(
    title: String,
    description: String,
    @DrawableRes icon: Int? = null,
    tone: ZipTone = ZipTone.Cobalt,
    onOpen: () -> Unit,
) {
    ZipRow(title = title, subtitle = description, icon = icon, iconTone = tone, onClick = onOpen,
        modifier = Modifier.heightIn(min = 64.dp))
}

/**
 * A settings group that opens in place: collapsed it is a [SettingsEntry]; expanded it is one white card with the
 * icon tile, title and a "접기" action. [flush] lets row content (ZipMenuRow) reach the card edges.
 */
@Composable
internal fun SettingsDisclosure(
    title: String,
    description: String,
    @DrawableRes icon: Int? = null,
    tone: ZipTone = ZipTone.Cobalt,
    flush: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    if (!expanded) {
        SettingsEntry(title, description, icon, tone) { expanded = true }
    } else {
        ZipCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                icon?.let { ZipIconTile(it, tone = tone) }
                Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                    color = ZipHapColors.TextPrimary)
                TextButton(onClick = { expanded = false }) {
                    Text("접기", Modifier.clearAndSetSemantics { contentDescription = "$title 접기" },
                        color = ZipHapTokens.CobaltText)
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(
                    start = if (flush) 0.dp else 16.dp,
                    end = if (flush) 0.dp else 16.dp,
                    top = 4.dp,
                    bottom = if (flush) 8.dp else 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { content() }
        }
    }
}
