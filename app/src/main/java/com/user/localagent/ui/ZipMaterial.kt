package com.user.localagent.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Design language 2.0 drop-in replacements for the Material controls that still draw outlines (D7). Each keeps the
 * Material call shape (same parameter names, slots and order for the parameters screens use) so a screen adopts
 * 2.0 by renaming the call: callbacks, modifiers, testTags and semantics pass through untouched.
 */

/** Filled secondary action in place of OutlinedButton: sunken grey pill, no border, at least 48dp tall. */
@Composable
fun ZipOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(ZipHapTokens.RadiusPill),
        colors = ButtonDefaults.buttonColors(
            containerColor = ZipHapColors.SurfaceSunken,
            contentColor = ZipHapColors.TextPrimary,
            disabledContainerColor = ZipHapColors.SurfaceSunken,
            disabledContentColor = ZipHapColors.TextFaint,
        ),
        elevation = null,
        contentPadding = contentPadding,
        content = content,
    )
}

/** Ink pill primary in place of Button (coral when [destructive]): fully rounded, at least 48dp tall, no shadow. */
@Composable
fun ZipFilledButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(ZipHapTokens.RadiusPill),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (destructive) ZipHapColors.Coral else ZipHapColors.Ink,
            contentColor = Color.White,
            disabledContainerColor = ZipHapTokens.Line,
            disabledContentColor = ZipHapColors.TextFaint,
        ),
        elevation = null,
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * Filled input in place of OutlinedTextField with the same slots: sunken fill, no outline or indicator, radius 16,
 * 2dp cobalt ring on focus (error ring on error). Width follows [modifier] like the Material field.
 */
@Composable
fun ZipOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(ZipHapTokens.RadiusControl)
    val ring = when {
        isError -> ZipHapColors.Error
        focused -> ZipHapColors.Cobalt
        else -> Color.Transparent
    }
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.border(2.dp, ring, shape),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interaction,
        shape = shape,
        colors = zipFilledFieldColors(),
    )
}

/** Colors of every 2.0 filled field: sunken in all states, no indicator line, cobalt cursor and focused label. */
@Composable
fun zipFilledFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = ZipHapColors.SurfaceSunken,
    unfocusedContainerColor = ZipHapColors.SurfaceSunken,
    disabledContainerColor = ZipHapColors.SurfaceSunken,
    errorContainerColor = ZipHapColors.SurfaceSunken,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
    cursorColor = ZipHapColors.Cobalt,
    focusedLabelColor = ZipHapTokens.CobaltText,
    unfocusedLabelColor = ZipHapColors.TextSecondary,
    focusedPlaceholderColor = ZipHapColors.Placeholder,
    unfocusedPlaceholderColor = ZipHapColors.Placeholder,
    focusedTextColor = ZipHapColors.TextPrimary,
    unfocusedTextColor = ZipHapColors.TextPrimary,
    disabledTextColor = ZipHapColors.TextFaint,
    focusedSupportingTextColor = ZipHapColors.TextSecondary,
    unfocusedSupportingTextColor = ZipHapColors.TextSecondary,
    errorSupportingTextColor = ZipHapColors.Error,
    errorLabelColor = ZipHapColors.Error,
)

/**
 * Filled filter chip (D6 follow-up): sunken when unselected, cobalt-soft with cobalt-text when selected, no border
 * in either state, so the unselected chip stays visible on white cards without an outline.
 */
@Composable
fun ZipFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = RoundedCornerShape(ZipHapTokens.RadiusChip),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = ZipHapColors.SurfaceSunken,
            labelColor = ZipHapColors.TextPrimary,
            iconColor = ZipHapColors.TextSecondary,
            selectedContainerColor = ZipHapColors.Ink,
            selectedLabelColor = ZipHapColors.OnInk,
            selectedLeadingIconColor = ZipHapColors.OnInk,
            selectedTrailingIconColor = ZipHapColors.OnInk,
            disabledContainerColor = ZipHapColors.SurfaceSunken,
            disabledSelectedContainerColor = ZipHapColors.SoftStrong,
            disabledLabelColor = ZipHapColors.TextFaint,
        ),
        border = null,
    )
}

/** Filled assist chip: sunken fill, no border, radius 12. */
@Composable
fun ZipAssistChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    AssistChip(
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = RoundedCornerShape(ZipHapTokens.RadiusChip),
        colors = AssistChipDefaults.assistChipColors(
            containerColor = ZipHapColors.SurfaceSunken,
            labelColor = ZipHapColors.TextPrimary,
            leadingIconContentColor = ZipHapColors.TextPrimary,
            trailingIconContentColor = ZipHapColors.TextSecondary,
            disabledContainerColor = ZipHapColors.SurfaceSunken,
            disabledLabelColor = ZipHapColors.TextFaint,
        ),
        border = null,
    )
}

/**
 * White rounded card (radius 20, card shadow, no border) in place of Card/OutlinedCard. With [onClick] the whole
 * card is one button, like the Material clickable card.
 */
@Composable
fun ZipCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    color: Color = ZipHapColors.Surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(ZipHapTokens.RadiusCard)
    val click = if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier
    Column(
        modifier
            .then(if (color == ZipHapColors.Surface) Modifier.zipShadow(ZipHapTokens.ShadowCard, shape) else Modifier)
            .clip(shape)
            .background(color)
            .then(click),
        content = content,
    )
}

/** A bulleted line drawn with a small dot instead of a bullet glyph. */
@Composable
fun ZipBulletText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = Color.Unspecified,
    dotColor: Color = ZipHapColors.TextFaint,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.heightIn(min = style.lineHeight.value.takeIf { it > 0f }?.dp ?: 19.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(dotColor))
        }
        Text(text, style = style, color = color)
    }
}

/**
 * Menu row for sheets and dialogs: icon tile and title, min height 56, a 4dp inset so the tile lines up with the
 * sheet title, rounded ripple. The whole row is one button.
 */
@Composable
fun ZipMenuRow(
    title: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ZipTone = ZipTone.Cobalt,
    enabled: Boolean = true,
    subtitle: String? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = ZipHapTokens.RowMinHeight)
            .clip(RoundedCornerShape(ZipHapTokens.RadiusControl))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZipHapTokens.Space3),
    ) {
        ZipIconTile(icon, tone = tone)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = ZipHapColors.TextPrimary)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ZipHapColors.TextSecondary) }
        }
    }
}

/** Small soft tag (radius 999) with a tone, for status labels inside rows. */
@Composable
fun ZipTag(text: String, tone: ZipTone = ZipTone.Neutral, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.clip(RoundedCornerShape(ZipHapTokens.RadiusPill)).background(tone.soft).padding(horizontal = 9.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = tone.content,
    )
}

/** Gap between a section's free content blocks (used with `ZipSection(contentPadding = PaddingValues(16.dp))`). */
val ZipBlockGap: Dp = 10.dp

/**
 * One row of a grouped white list built from LazyColumn items: the first item gets the top card corners, the last
 * the bottom ones, so consecutive items read as one card. Put [ZipInsetDivider] at the top of items after the first.
 */
fun Modifier.zipGroupItem(index: Int, count: Int, color: Color = ZipHapColors.Surface): Modifier {
    val radius = ZipHapTokens.RadiusCard
    val top = if (index == 0) radius else 0.dp
    val bottom = if (index == count - 1) radius else 0.dp
    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
    return clip(shape).background(color)
}

/** Feature section header: icon tile, title (20/700, heading) and an optional grey description. */
@Composable
fun ZipFeatureHeader(
    @DrawableRes icon: Int,
    title: String,
    body: String? = null,
    tone: ZipTone = ZipTone.Cobalt,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ZipIconTile(icon, tone = tone)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = ZipHapColors.TextPrimary)
            body?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ZipHapColors.TextSecondary) }
        }
    }
}
