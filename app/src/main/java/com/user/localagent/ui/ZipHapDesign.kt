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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.user.localagent.R

/** Quiet charcoal surfaces; mint is reserved for small status indicators. */
object ZipHapColors {
    val Background = Color(0xFF111315)
    val Surface = Color(0xFF1B1D20)
    val Elevated = Color(0xFF25282C)
    val TextPrimary = Color(0xFFF3F4F6)
    val TextSecondary = Color(0xFF979DA6)
    val Outline = Color(0xFF35383D)
    val Mint = Color(0xFFA8C8BC)
    val Error = Color(0xFFEAA5A0)
    // Keep existing feature screens source-compatible while using the neutral palette.
    val DeepNavy = Background
    val NavySurface = Surface
    val NavyElevated = Elevated
    val Periwinkle = TextSecondary
}

private val ZipHapScheme = darkColorScheme(
    primary = ZipHapColors.TextPrimary,
    onPrimary = ZipHapColors.Background,
    primaryContainer = ZipHapColors.Elevated,
    onPrimaryContainer = ZipHapColors.TextPrimary,
    secondary = ZipHapColors.TextSecondary,
    onSecondary = ZipHapColors.Background,
    secondaryContainer = ZipHapColors.Elevated,
    onSecondaryContainer = ZipHapColors.TextPrimary,
    background = ZipHapColors.Background,
    onBackground = ZipHapColors.TextPrimary,
    surface = ZipHapColors.Surface,
    onSurface = ZipHapColors.TextPrimary,
    surfaceVariant = ZipHapColors.Elevated,
    onSurfaceVariant = ZipHapColors.TextSecondary,
    outline = ZipHapColors.Outline,
    outlineVariant = Color(0xFF2B2E32),
    error = ZipHapColors.Error,
    onError = ZipHapColors.Background,
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

/** Fixed brand theme with readable bundled Korean and Latin typography. */
@Composable
fun ZipHapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ZipHapScheme,
        typography = ZipHapTypography,
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
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("ZipHap.Agent", style = MaterialTheme.typography.labelMedium, color = ZipHapColors.TextSecondary)
        Text(title, style = MaterialTheme.typography.titleLarge, color = ZipHapColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(11.dp), color = ZipHapColors.TextSecondary, strokeWidth = 1.5.dp)
            } else {
                Box(Modifier.size(5.dp).background(ZipHapColors.Mint, CircleShape))
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
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(Modifier.padding(18.dp), content = { content() })
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
            Text(text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge, color = ZipHapColors.TextPrimary)
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
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ZipHapColors.Periwinkle,
                modifier = Modifier.size(32.dp)
            )
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



