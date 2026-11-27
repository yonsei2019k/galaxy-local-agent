package com.user.localagent.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.user.localagent.runtime.R

/**
 * "Sunlit Porcelain" 2.0 (design language 2.0, 2026-11-25): a pale grey page with white rounded cards grouped by
 * spacing, no dark lines (owner direction 2026-11-19: no black UI). Values come from the generated [ZipHapTokens].
 * Text-safe shades are used for text (WCAG AA >= 4.5:1 on white); the soft tints are backgrounds only.
 * Cobalt = the one primary action, Coral = needs the owner, Mint = done/connected, Sun = waiting, Lilac = creative/collab.
 *
 * Member names stay source-compatible for existing screens; the legacy names map onto the 2.0 values.
 */
object ZipHapColors {
    val Background = ZipHapTokens.Bg                 // page #F3F4F8
    val Surface = ZipHapTokens.Surface               // cards, sheets, dialogs
    val Elevated = Color(0xFFEEF2FF)                 // cobalt-tinted container (user bubble, selected rows)
    val SurfaceSoft = ZipHapTokens.SurfaceSunken     // filled inputs, secondary buttons, tags
    val TextPrimary = ZipHapTokens.Text
    val TextSecondary = ZipHapTokens.TextSub         // 6.13:1 white, 5.58:1 page, 5.52:1 sunken
    /** Input/focus outlines where one is unavoidable: 3.33:1 white, 3.03:1 page, 3.00:1 sunken (>= 3:1). */
    val Outline = Color(0xFF868CA4)
    /** Legacy "card border and divider" name; now the faint inset hairline #EEF0F4. */
    val OutlineSoft = ZipHapTokens.Divider

    // 2.0 additions.
    val SurfaceSunken = ZipHapTokens.SurfaceSunken
    val Divider = ZipHapTokens.Divider
    /** Disabled text, placeholders and decorative glyphs (4.61:1 on sunken since the D1 text-faint change). */
    val TextFaint = ZipHapTokens.TextFaint
    /** Placeholder text: token text-faint #676D85, 4.61:1 on sunken #F1F3F7 and 5.1:1 on white (AA). */
    val Placeholder = ZipHapTokens.TextFaint

    val Cobalt = ZipHapTokens.Cobalt
    val CobaltSoft = ZipHapTokens.CobaltSoft
    val OnCobalt = Color(0xFFFFFFFF)
    val Coral = ZipHapTokens.Coral
    val CoralSoft = ZipHapTokens.CoralSoft
    val Mint = ZipHapTokens.Mint                     // text-safe; also status dots
    val MintSoft = ZipHapTokens.MintSoft
    val Sun = ZipHapTokens.Sun                       // fill only, with TextPrimary on it
    val SunText = ZipHapTokens.SunText
    val SunSoft = ZipHapTokens.SunSoft
    val Lilac = ZipHapTokens.Lilac
    val LilacSoft = ZipHapTokens.LilacSoft
    val Error = Color(0xFFC62828)
    val ErrorSoft = Color(0xFFFDE7E7)

    // Keep existing feature screens source-compatible: the old names now map onto the bright palette.
    val DeepNavy = Background
    val NavySurface = Surface
    val NavyElevated = Elevated
    val Periwinkle = Cobalt
}

/*
 * Surface roles: white for anything that floats or groups (cards, sheets, dialogs, menus where Material allows),
 * sunken #F1F3F7 for filled controls (filled TextField, Switch track, tags). Material reads surfaceContainerHighest
 * for filled TextField/Card, surfaceContainerHigh for AlertDialog and surfaceContainerLow for ModalBottomSheet.
 */
private val ZipHapScheme = lightColorScheme(
    primary = ZipHapColors.Cobalt,
    onPrimary = ZipHapColors.OnCobalt,
    primaryContainer = ZipHapColors.CobaltSoft,
    onPrimaryContainer = Color(0xFF1A2A8C),
    secondary = ZipHapColors.Coral,
    onSecondary = Color.White,
    secondaryContainer = ZipHapColors.CoralSoft,
    onSecondaryContainer = Color(0xFF8C2A12),
    tertiary = ZipHapColors.Mint,
    onTertiary = Color.White,
    tertiaryContainer = ZipHapColors.MintSoft,
    onTertiaryContainer = Color(0xFF075C43),
    background = ZipHapColors.Background,
    onBackground = ZipHapColors.TextPrimary,
    surface = ZipHapColors.Surface,
    onSurface = ZipHapColors.TextPrimary,
    surfaceVariant = ZipHapColors.SurfaceSunken,
    onSurfaceVariant = ZipHapColors.TextSecondary,
    surfaceTint = ZipHapColors.Cobalt,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE6E8EF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = ZipHapColors.SurfaceSunken,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = ZipHapColors.SurfaceSunken,
    inverseSurface = ZipHapColors.TextPrimary,
    inverseOnSurface = Color.White,
    inversePrimary = Color(0xFFB9C6FF),
    outline = ZipHapColors.Outline,
    outlineVariant = ZipHapColors.Divider,
    scrim = ZipHapColors.TextPrimary,
    error = ZipHapColors.Error,
    onError = Color.White,
    errorContainer = ZipHapColors.ErrorSoft,
    onErrorContainer = Color(0xFF8C1D18),
)
/**
 * The only place font families are chosen. `ui` is body text and controls (SUIT Variable 2.0.5, weights 400-700 via
 * variation settings); `display` is the large playful accent (Jua, single weight) for the 집순이 name, greeting and
 * empty-state titles. Both are bundled SIL OFL 1.1 fonts (no runtime font network); licenses are in assets/licenses.
 */
@OptIn(ExperimentalTextApi::class)
object ZipFonts {
    private fun suit(weight: Int) = Font(
        R.font.suit_variable,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

    val ui: FontFamily = FontFamily(suit(400), suit(500), suit(600), suit(700))
    val display: FontFamily = FontFamily(Font(R.font.jua_regular))
}

private fun zipStyle(size: Float, weight: Int, lineHeight: Float, tight: Boolean, family: FontFamily = ZipFonts.ui) =
    TextStyle(
        fontFamily = family,
        fontWeight = FontWeight(weight),
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = if (tight) ZipHapTokens.LsTight.em else 0.sp,
    )

private fun headingLine(size: Float) = size * ZipHapTokens.LhTight

/**
 * Token type scale: hero 28, screen title 24/700 (headlineSmall), title 20/700 (titleLarge), subtitle 17/700
 * (titleMedium), body 15/400 line 24 (bodyLarge; bodyMedium is the same size with a denser 22 line for UI copy),
 * small 13 (bodySmall), caption 12/500 (labelSmall). labelLarge 15/700 is the button label and labelMedium 13/700
 * the section title. Headings use the tight letter spacing; Korean body text keeps 0.
 */
private val ZipHapTypography = run {
    val bold = ZipHapTokens.FwBold
    val medium = ZipHapTokens.FwMedium
    val regular = ZipHapTokens.FwRegular
    Typography(
        displayLarge = zipStyle(40f, bold, 48f, tight = true, family = ZipFonts.display),
        displayMedium = zipStyle(34f, bold, 42f, tight = true, family = ZipFonts.display),
        displaySmall = zipStyle(28f, bold, headingLine(28f), tight = true, family = ZipFonts.display),
        headlineLarge = zipStyle(28f, bold, headingLine(28f), tight = true),
        headlineMedium = zipStyle(26f, bold, headingLine(26f), tight = true),
        headlineSmall = zipStyle(24f, bold, headingLine(24f), tight = true),
        titleLarge = zipStyle(20f, bold, headingLine(20f), tight = true),
        titleMedium = zipStyle(17f, bold, 23f, tight = true),
        titleSmall = zipStyle(15f, bold, 21f, tight = false),
        bodyLarge = zipStyle(15f, regular, 15f * ZipHapTokens.LhBody, tight = false),
        bodyMedium = zipStyle(15f, regular, 22f, tight = false),
        bodySmall = zipStyle(13f, regular, 19f, tight = false),
        labelLarge = zipStyle(15f, bold, 20f, tight = false),
        labelMedium = zipStyle(13f, bold, 18f, tight = false),
        labelSmall = zipStyle(12f, medium, 16f, tight = false),
    )
}

private val ZipHapShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(ZipHapTokens.RadiusChip),
    medium = RoundedCornerShape(ZipHapTokens.RadiusControl),
    large = RoundedCornerShape(ZipHapTokens.RadiusCard),
    extraLarge = RoundedCornerShape(ZipHapTokens.RadiusSheet),
)

/** Fixed brand theme with readable bundled Korean and Latin typography. */
@Composable
fun ZipHapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ZipHapScheme,
        typography = ZipHapTypography,
        shapes = ZipHapShapes,
        content = content
    )
}

/** Compact feature-page heading; the chat workspace supplies its own single-row header. */
@Composable
fun ZipHapHeader(
    title: String,
    status: String,
    busy: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        if (status.isNotBlank() || busy) Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(11.dp), color = ZipHapColors.Cobalt, strokeWidth = 1.5.dp)
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = ZipHapColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
/** Spacious white card for grouped content (2.0: soft card shadow, no border); callers provide the inner layout. */
@Composable
fun ZipHapCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = MaterialTheme.shapes.large
    Surface(
        modifier = modifier.fillMaxWidth().zipShadow(ZipHapTokens.ShadowCard, shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(Modifier.padding(16.dp), content = { content() })
    }
}

@Composable
internal fun ZipHapSectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

@Composable
internal fun ZipHapStatusTag(
    label: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    background: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    Surface(shape = CircleShape, color = background, contentColor = color) {
        Text(label, Modifier.padding(horizontal = 9.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
    }
}

/** Assistant prose stays full-width; only the user's message receives a quiet gray bubble. */
@Composable
fun ZipHapChatBubble(text: String, fromUser: Boolean, modifier: Modifier = Modifier) {
    if (fromUser) {
        Row(modifier.fillMaxWidth().padding(start = 40.dp), horizontalArrangement = Arrangement.End) {
            Surface(shape = RoundedCornerShape(20.dp), color = ZipHapColors.Elevated, contentColor = ZipHapColors.TextPrimary) {
                SelectionContainer {
                    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 11.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    } else {
        SelectionContainer(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            MarkdownText(text, Modifier.fillMaxWidth())
        }
    }
}
/** Empty state with an optional caller supplied next step. */
@Composable
fun ZipHapEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    ZipHapCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp).defaultMinSize(minHeight = 132.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(Modifier.size(56.dp).background(ZipHapColors.CobaltSoft, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = ZipHapColors.Cobalt,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(5.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = ZipHapColors.TextSecondary,
                textAlign = TextAlign.Center
            )
            action?.let {
                Spacer(Modifier.height(14.dp))
                it()
            }
        }
    }
}



