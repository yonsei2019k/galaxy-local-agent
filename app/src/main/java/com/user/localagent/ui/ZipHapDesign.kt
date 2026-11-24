package com.user.localagent.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.user.localagent.runtime.R

/**
 * "Sunlit Porcelain": bright white surfaces with vivid point colors (owner direction 2026-11-19: no black UI).
 * Text-safe shades are used for text (WCAG AA >= 4.5:1 on white); the soft tints are backgrounds only.
 * Cobalt = the one primary action, Coral = needs the owner, Mint = done/connected, Sun = waiting, Lilac = creative/collab.
 */
object ZipHapColors {
    val Background = Color(0xFFF7F8FC)
    val Surface = Color(0xFFFFFFFF)
    val Elevated = Color(0xFFEEF2FF)        // cobalt-tinted container (user bubble, selected rows)
    val SurfaceSoft = Color(0xFFF0F2F8)
    val TextPrimary = Color(0xFF171B33)
    val TextSecondary = Color(0xFF5E6480)
    val Outline = Color(0xFF868CA4)          // control borders, >= 3:1
    val OutlineSoft = Color(0xFFE3E6EF)      // card borders and dividers

    val Cobalt = Color(0xFF3355F0)
    val CobaltSoft = Color(0xFFE6EBFF)
    val OnCobalt = Color(0xFFFFFFFF)
    val Coral = Color(0xFFC9401F)
    val CoralSoft = Color(0xFFFFE9E2)
    val Mint = Color(0xFF0B7F5D)             // text-safe; also status dots
    val MintSoft = Color(0xFFDDF7EE)
    val Sun = Color(0xFFFFB020)              // fill only, with TextPrimary on it
    val SunText = Color(0xFF8A5A00)
    val SunSoft = Color(0xFFFFF3D6)
    val Lilac = Color(0xFF7C4DEB)
    val LilacSoft = Color(0xFFF0E9FF)
    val Error = Color(0xFFC62828)
    val ErrorSoft = Color(0xFFFDE7E7)

    // Keep existing feature screens source-compatible: the old names now map onto the bright palette.
    val DeepNavy = Background
    val NavySurface = Surface
    val NavyElevated = Elevated
    val Periwinkle = Cobalt
}

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
    surfaceVariant = ZipHapColors.SurfaceSoft,
    onSurfaceVariant = ZipHapColors.TextSecondary,
    surfaceTint = ZipHapColors.Cobalt,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFBFE),
    surfaceContainer = ZipHapColors.SurfaceSoft,
    surfaceContainerHigh = Color(0xFFEAEDF5),
    surfaceContainerHighest = Color(0xFFE3E6EF),
    inverseSurface = ZipHapColors.TextPrimary,
    inverseOnSurface = Color.White,
    inversePrimary = Color(0xFFB9C6FF),
    outline = ZipHapColors.Outline,
    outlineVariant = ZipHapColors.OutlineSoft,
    error = ZipHapColors.Error,
    onError = Color.White,
    errorContainer = ZipHapColors.ErrorSoft,
    onErrorContainer = Color(0xFF8C1D18),
)
/** Official Pretendard v1.3.9 static OTFs, bundled under SIL OFL 1.1; no runtime font network. */
private val ZipHapFontFamily = FontFamily(
    Font(R.font.pretendard_regular, weight = FontWeight.Normal),
    Font(R.font.pretendard_semibold, weight = FontWeight.SemiBold),
)

private val ZipHapTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = ZipHapFontFamily, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        displayMedium = base.displayMedium.copy(fontFamily = ZipHapFontFamily, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        displaySmall = base.displaySmall.copy(fontFamily = ZipHapFontFamily, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        headlineLarge = base.headlineLarge.copy(fontFamily = ZipHapFontFamily, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        headlineMedium = base.headlineMedium.copy(fontFamily = ZipHapFontFamily, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        headlineSmall = base.headlineSmall.copy(fontFamily = ZipHapFontFamily, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleLarge = base.titleLarge.copy(fontFamily = ZipHapFontFamily, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleMedium = base.titleMedium.copy(fontFamily = ZipHapFontFamily, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleSmall = base.titleSmall.copy(fontFamily = ZipHapFontFamily, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        bodyLarge = base.bodyLarge.copy(fontFamily = ZipHapFontFamily, fontWeight = FontWeight.Normal, lineHeight = 25.sp, letterSpacing = 0.sp),
        bodyMedium = base.bodyMedium.copy(fontFamily = ZipHapFontFamily, fontWeight = FontWeight.Normal, lineHeight = 22.sp, letterSpacing = 0.sp),
        bodySmall = base.bodySmall.copy(fontFamily = ZipHapFontFamily, fontWeight = FontWeight.Normal, lineHeight = 18.sp, letterSpacing = 0.sp),
        labelLarge = base.labelLarge.copy(fontFamily = ZipHapFontFamily, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        labelMedium = base.labelMedium.copy(fontFamily = ZipHapFontFamily, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        labelSmall = base.labelSmall.copy(fontFamily = ZipHapFontFamily, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    )
}

private val ZipHapShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp),
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
/** Spacious surface for grouped content; callers provide the inner layout. */
@Composable
fun ZipHapCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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



