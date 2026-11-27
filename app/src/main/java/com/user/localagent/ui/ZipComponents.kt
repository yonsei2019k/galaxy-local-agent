package com.user.localagent.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.user.localagent.runtime.R
import kotlin.math.pow

/*
 * Design language 2.0 building blocks for native screens (D6). Screens adopt them in D7.
 * Rules: pale grey page, white rounded cards grouped by spacing, no dark lines (only ZipInsetDivider inside a card),
 * filled controls, duotone icons in role-colored tiles. Icons are `zi_<name>` (tintable) and illustrations are
 * `zi_ill_<name>` (fixed colors, never tinted) from design/icons.json and design/illustrations.json.
 */

// ---------------------------------------------------------------------------------------------------------------
// Tones and color math
// ---------------------------------------------------------------------------------------------------------------

/**
 * Role tone for icon tiles and soft chips: [soft] is the tile background and [content] the icon/text color on it.
 * Every pair is at least 4.5:1 so the same pair can carry text (token coral-text is #B83C1D for that reason).
 */
enum class ZipTone(val soft: Color, val content: Color) {
    Cobalt(ZipHapTokens.CobaltSoft, ZipHapTokens.CobaltText),
    Coral(ZipHapTokens.CoralSoft, ZipHapTokens.CoralText),
    Mint(ZipHapTokens.MintSoft, ZipHapTokens.MintText),
    Sun(ZipHapTokens.SunSoft, ZipHapTokens.SunText),
    Lilac(ZipHapTokens.LilacSoft, ZipHapTokens.LilacText),
    Neutral(ZipHapTokens.SurfaceSunken, ZipHapTokens.TextSub),
}

/** WCAG 2.x relative luminance of an opaque sRGB color. */
internal fun zipRelativeLuminance(color: Color): Double {
    fun channel(value: Float): Double {
        val c = value.toDouble()
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}

/** WCAG 2.x contrast ratio between two opaque colors (1.0 to 21.0). */
internal fun zipContrastRatio(a: Color, b: Color): Double {
    val la = zipRelativeLuminance(a)
    val lb = zipRelativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/**
 * Draws token shadow layers ([ZipHapTokens.ShadowCard], [ZipHapTokens.ShadowSheet], [ZipHapTokens.ShadowFloat])
 * behind the element, matching the CSS box-shadow values. Apply before `clip`/`background`.
 */
fun Modifier.zipShadow(layers: List<ZipShadow>, shape: Shape): Modifier =
    layers.fold(this) { modifier, layer ->
        modifier.dropShadow(shape, Shadow(radius = layer.blur, color = layer.color, offset = DpOffset(0.dp, layer.offsetY)))
    }

// ---------------------------------------------------------------------------------------------------------------
// Icons and illustrations
// ---------------------------------------------------------------------------------------------------------------

/** Tintable duotone icon (`R.drawable.zi_*`). Pass `contentDescription = null` when a label next to it says the same. */
@Composable
fun ZipIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
) {
    Icon(painterResource(icon), contentDescription, modifier.size(size), tint = tint)
}

/** Fixed-color spot illustration (`R.drawable.zi_ill_*`). Never tinted; null description marks it decorative. */
@Composable
fun ZipIllustration(
    @DrawableRes illustration: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Image(painterResource(illustration), contentDescription, modifier)
}

/**
 * Tile colors in the monochrome UI (2026-11-27, same rule as the web): a grey tile with an ink icon. Coral keeps its
 * tint because it means "needs the owner" or a failure; the other tones only named a category.
 */
internal fun zipTileColors(tone: ZipTone): Pair<Color, Color> =
    if (tone == ZipTone.Coral) tone.soft to tone.content else ZipHapColors.Soft to ZipHapColors.TextPrimary

/** 40dp rounded tile (radius 12): grey background and a 22dp duotone ink icon (coral tone keeps its tint). */
@Composable
fun ZipIconTile(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    tone: ZipTone = ZipTone.Cobalt,
    contentDescription: String? = null,
    size: Dp = ZipHapTokens.IconTile,
    iconSize: Dp = 22.dp,
) {
    val (background, content) = zipTileColors(tone)
    Box(
        modifier.size(size).clip(RoundedCornerShape(ZipHapTokens.RadiusTile)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        ZipIcon(icon, contentDescription, tint = content, size = iconSize)
    }
}

/**
 * Security tile for approval, sign-in, payment and vault surfaces: a dark rounded tile holding the metal lock
 * (`R.drawable.zi_ill_metal_lock`, fixed gradient colors, never tinted). Decorative; the text next to it carries the meaning.
 */
@Composable
fun ZipMetalLockTile(modifier: Modifier = Modifier, size: Dp = 36.dp) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(ZipHapTokens.RadiusTile)).background(ZipHapTokens.Night),
        contentAlignment = Alignment.Center,
    ) {
        ZipIllustration(com.user.localagent.runtime.R.drawable.zi_ill_metal_lock, contentDescription = null, modifier = Modifier.size(size * 0.82f))
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Screen, titles and sections
// ---------------------------------------------------------------------------------------------------------------

/** Large screen title, 24/700 tight, marked as a heading. */
@Composable
fun ZipScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineSmall,
        color = ZipHapColors.TextPrimary,
    )
}

/** Small grey bold section title (13/700, text-sub) placed above its card. */
@Composable
fun ZipSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.semantics { heading() },
        style = MaterialTheme.typography.labelMedium,
        color = ZipHapColors.TextSecondary,
    )
}

/**
 * Full screen on the page color with a large title header (no app bar line). With [onBack] a 48dp back button row
 * sits above the title; [actions] go on the right of that row (or of the title when there is no back button).
 * Content is a column with the 16dp gutter and 24dp between sections. When [scrollable] is false the column fills
 * the remaining height and the caller scrolls (use this to host a LazyColumn). Window insets are left to the host.
 */
@Composable
fun ZipScreen(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollable: Boolean = true,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(ZipHapColors.Background)) {
        if (onBack != null) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    ZipIcon(R.drawable.zi_back, contentDescription = "뒤로", tint = ZipHapColors.TextPrimary)
                }
                Spacer(Modifier.weight(1f))
                actions()
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(
                start = ZipHapTokens.Gutter,
                end = if (onBack == null) 4.dp else ZipHapTokens.Gutter,
                top = if (onBack == null) 20.dp else 4.dp,
                bottom = 12.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ZipScreenTitle(title)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ZipHapColors.TextSecondary) }
            }
            if (onBack == null) actions()
        }
        val body = Modifier.fillMaxWidth().weight(1f)
        Column(
            (if (scrollable) body.verticalScroll(scrollState) else body)
                .padding(start = ZipHapTokens.Gutter, end = ZipHapTokens.Gutter, top = 4.dp, bottom = ZipHapTokens.SpaceSection),
            verticalArrangement = Arrangement.spacedBy(ZipHapTokens.SpaceSection),
            content = content,
        )
    }
}

/**
 * A group: optional [title] (13/700 grey) above one white card (radius 20, soft card shadow, no border).
 * Rows inside bring their own 16dp padding; separate them with [ZipInsetDivider] only. For free text or forms pass
 * `contentPadding = PaddingValues(16.dp)`.
 */
@Composable
fun ZipSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 4.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(ZipHapTokens.RadiusCard)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let { ZipSectionTitle(it, Modifier.padding(horizontal = 4.dp)) }
        Column(
            Modifier.fillMaxWidth()
                .zipShadow(ZipHapTokens.ShadowCard, shape)
                .clip(shape)
                .background(ZipHapColors.Surface)
                .padding(contentPadding),
            content = content,
        )
    }
}

/** The only permitted hairline: 1dp #EEF0F4, inset 16 from both sides, between rows of one card. */
@Composable
fun ZipInsetDivider(modifier: Modifier = Modifier, inset: Dp = ZipHapTokens.Space4) {
    HorizontalDivider(modifier.padding(horizontal = inset), thickness = 1.dp, color = ZipHapColors.Divider)
}

// ---------------------------------------------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun ZipRowLayout(
    title: String,
    subtitle: String?,
    icon: Int?,
    iconTone: ZipTone,
    enabled: Boolean,
    modifier: Modifier,
    trailing: @Composable (() -> Unit)?,
    showChevron: Boolean,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = ZipHapTokens.RowMinHeight)
            .padding(horizontal = ZipHapTokens.Space4, vertical = 8.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZipHapTokens.Space3),
    ) {
        icon?.let { ZipIconTile(it, tone = iconTone) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight(ZipHapTokens.FwMedium), lineHeight = 22.sp),
                color = ZipHapColors.TextPrimary,
            )
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ZipHapColors.TextSecondary) }
        }
        trailing?.invoke()
        if (showChevron) ZipIcon(R.drawable.zi_chevron_right, contentDescription = null, tint = ZipHapColors.TextFaint, size = 20.dp)
    }
}

/**
 * List row: min height 56, optional 40dp icon tile, title 15/500, optional subtitle 13 grey, optional [trailing]
 * (value text, tag) and a chevron when it navigates. The whole row is one button (ripple, Role.Button) when
 * [onClick] is set; disabled rows are dimmed and not clickable.
 */
@Composable
fun ZipRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
    iconTone: ZipTone = ZipTone.Cobalt,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    enabled: Boolean = true,
) {
    val click = if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier
    ZipRowLayout(title, subtitle, icon, iconTone, enabled, modifier.then(click), trailing, showChevron)
}

/** Ink switch colors (monochrome); the unchecked track keeps a 3:1 outline so the control stays visible on white. */
@Composable
fun zipSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = ZipHapColors.Ink,
    checkedBorderColor = Color.Transparent,
    checkedIconColor = ZipHapColors.Ink,
    uncheckedThumbColor = ZipHapColors.Outline,
    uncheckedTrackColor = ZipHapColors.SurfaceSunken,
    uncheckedBorderColor = ZipHapColors.Outline,
)

/** Row with a trailing ink switch; the whole row toggles (Role.Switch), so the touch target is the full row. */
@Composable
fun ZipSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
    iconTone: ZipTone = ZipTone.Cobalt,
    enabled: Boolean = true,
) {
    ZipRowLayout(
        title, subtitle, icon, iconTone, enabled,
        modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = zipSwitchColors()) },
        showChevron = false,
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun ZipButtonContent(text: String, icon: Int?) {
    icon?.let {
        ZipIcon(it, contentDescription = null, size = 20.dp)
        Spacer(Modifier.size(8.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
}

/** Primary action: ink pill (coral when [destructive]), white label, height 52, fully rounded. */
@Composable
fun ZipPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fullWidth: Boolean = true,
    destructive: Boolean = false,
    @DrawableRes icon: Int? = null,
) {
    Button(
        onClick = onClick,
        modifier = (if (fullWidth) modifier.fillMaxWidth() else modifier).heightIn(min = ZipHapTokens.ControlHeight),
        enabled = enabled,
        shape = RoundedCornerShape(ZipHapTokens.RadiusPill),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (destructive) ZipHapColors.Coral else ZipHapColors.Ink,
            contentColor = Color.White,
            disabledContainerColor = ZipHapTokens.Line,
            disabledContentColor = ZipHapColors.TextFaint,
        ),
        elevation = null,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) { ZipButtonContent(text, icon) }
}

/** Secondary action: sunken grey pill, no border, dark label, height 52. */
@Composable
fun ZipSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fullWidth: Boolean = true,
    @DrawableRes icon: Int? = null,
) {
    Button(
        onClick = onClick,
        modifier = (if (fullWidth) modifier.fillMaxWidth() else modifier).heightIn(min = ZipHapTokens.ControlHeight),
        enabled = enabled,
        shape = RoundedCornerShape(ZipHapTokens.RadiusPill),
        colors = ButtonDefaults.buttonColors(
            containerColor = ZipHapColors.SurfaceSunken,
            contentColor = ZipHapColors.TextPrimary,
            disabledContainerColor = ZipHapColors.SurfaceSunken,
            disabledContentColor = ZipHapColors.TextFaint,
        ),
        elevation = null,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) { ZipButtonContent(text, icon) }
}

/** Low-emphasis text action in ink, at least 48dp tall. */
@Composable
fun ZipTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(ZipHapTokens.RadiusChip),
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (destructive) ZipTone.Coral.content else ZipHapColors.TextPrimary,
            disabledContentColor = ZipHapColors.TextFaint,
        ),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

// ---------------------------------------------------------------------------------------------------------------
// Text field
// ---------------------------------------------------------------------------------------------------------------

/**
 * Filled input: sunken fill, no border or indicator line, radius 16; focus draws a 2dp cobalt ring (error: 2dp red).
 * Placeholder uses [ZipHapColors.Placeholder] (4.61:1 on the fill). [label] floats inside the field.
 */
@Composable
fun ZipTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    @DrawableRes leadingIcon: Int? = null,
    trailing: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(ZipHapTokens.RadiusControl)
    val ring = when {
        isError -> ZipHapColors.Error
        focused -> ZipHapColors.Cobalt
        else -> Color.Transparent
    }
    val none = Color.Transparent
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().border(2.dp, ring, shape),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = MaterialTheme.typography.bodyMedium,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, color = ZipHapColors.Placeholder) } },
        leadingIcon = leadingIcon?.let { { ZipIcon(it, contentDescription = null, tint = ZipHapColors.TextSecondary, size = 22.dp) } },
        trailingIcon = trailing,
        supportingText = supportingText?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interaction,
        shape = shape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = ZipHapColors.SurfaceSunken,
            unfocusedContainerColor = ZipHapColors.SurfaceSunken,
            disabledContainerColor = ZipHapColors.SurfaceSunken,
            errorContainerColor = ZipHapColors.SurfaceSunken,
            focusedIndicatorColor = none,
            unfocusedIndicatorColor = none,
            disabledIndicatorColor = none,
            errorIndicatorColor = none,
            cursorColor = ZipHapColors.Cobalt,
            focusedLabelColor = ZipHapTokens.CobaltText,
            unfocusedLabelColor = ZipHapColors.TextSecondary,
            focusedTextColor = ZipHapColors.TextPrimary,
            unfocusedTextColor = ZipHapColors.TextPrimary,
            disabledTextColor = ZipHapColors.TextFaint,
            focusedSupportingTextColor = ZipHapColors.TextSecondary,
            unfocusedSupportingTextColor = ZipHapColors.TextSecondary,
            errorSupportingTextColor = ZipHapColors.Error,
            errorLabelColor = ZipHapColors.Error,
        ),
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Bottom sheet
// ---------------------------------------------------------------------------------------------------------------

/**
 * Rounded white bottom sheet (top radius 28, drag handle, sheet shadow tone) used in place of AlertDialog.
 * [title] is 20/700; [actions] render as a full-width row under the content (put ZipSecondaryButton/ZipPrimaryButton
 * with `Modifier.weight(1f)` and `fullWidth = false`). To animate closing from a button, call
 * `scope.launch { sheetState.hide() }.invokeOnCompletion { onDismissRequest() }` with the passed [sheetState].
 * The body scrolls when it is taller than the sheet; pass `scrollable = false` when the content hosts a LazyColumn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZipSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    actions: (@Composable RowScope.() -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = ZipHapTokens.RadiusSheet, topEnd = ZipHapTokens.RadiusSheet),
        containerColor = ZipHapColors.Surface,
        contentColor = ZipHapColors.TextPrimary,
        tonalElevation = 0.dp,
        scrimColor = ZipHapColors.TextPrimary.copy(alpha = 0.32f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = ZipHapTokens.Line) },
    ) {
        val body = Modifier.fillMaxWidth()
        Column(
            (if (scrollable) body.verticalScroll(rememberScrollState()) else body)
                .padding(start = 20.dp, end = 20.dp, bottom = ZipHapTokens.Space6),
            verticalArrangement = Arrangement.spacedBy(ZipHapTokens.Space4),
        ) {
            title?.let {
                Text(it, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = ZipHapColors.TextPrimary)
            }
            content()
            actions?.let {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(ZipHapTokens.Space2),
                    verticalAlignment = Alignment.CenterVertically,
                    content = it,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Empty state
// ---------------------------------------------------------------------------------------------------------------

/** Empty screen or section: a spot illustration (decorative), heavy SUIT title, grey body (omitted when blank) and an optional action. */
@Composable
fun ZipEmptyState(
    @DrawableRes illustration: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = ZipHapTokens.Space6, vertical = ZipHapTokens.Space7),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ZipIllustration(illustration, contentDescription = null, modifier = Modifier.size(width = 160.dp, height = 120.dp))
        Spacer(Modifier.height(ZipHapTokens.Space4))
        Text(
            title,
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize, lineHeight = MaterialTheme.typography.titleLarge.lineHeight),
            color = ZipHapColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        if (body.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = ZipHapColors.TextSecondary, textAlign = TextAlign.Center)
        }
        action?.let {
            Spacer(Modifier.height(ZipHapTokens.Space5))
            it()
        }
    }
}
