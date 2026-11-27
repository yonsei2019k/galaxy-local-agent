// Generated from design/tokens.json by scripts/gen-design-tokens.py. Do not edit.
package com.user.localagent.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One layer of a drop shadow: vertical offset, blur radius and ARGB color (alpha baked in). */
data class ZipShadow(val offsetY: Dp, val blur: Dp, val color: Color)

object ZipHapTokens {
    val Bg = Color(0xFFF3F4F8)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceSunken = Color(0xFFF1F3F7)
    val Divider = Color(0xFFEEF0F4)
    val Text = Color(0xFF171B33)
    val TextSub = Color(0xFF5B6178)
    val TextFaint = Color(0xFF676D85)
    val Muted = Color(0xFF5E6480)
    val Line = Color(0xFFE3E6EF)
    val Cobalt = Color(0xFF3355F0)
    val CobaltText = Color(0xFF2E4BE0)
    val CobaltSoft = Color(0xFFE6EBFF)
    val Coral = Color(0xFFC9401F)
    val CoralText = Color(0xFFB83C1D)
    val CoralSoft = Color(0xFFFFE9E2)
    val Mint = Color(0xFF0B7F5D)
    val MintText = Color(0xFF0B7A5B)
    val MintSoft = Color(0xFFDDF7EE)
    val Sun = Color(0xFFFFB020)
    val SunText = Color(0xFF8A5A00)
    val SunSoft = Color(0xFFFFF3D6)
    val Lilac = Color(0xFF7C4DEB)
    val LilacText = Color(0xFF6D3FE0)
    val LilacSoft = Color(0xFFF0E9FF)
    val Ink = Color(0xFF141318)
    val InkHover = Color(0xFF2C2B33)
    val OnInk = Color(0xFFFFFFFF)
    val Soft = Color(0xFFEFEFF3)
    val SoftStrong = Color(0xFFE4E4EA)
    val WallTop = Color(0xFFFBE9DC)
    val WallBottom = Color(0xFFF6D9C6)
    val Floor = Color(0xFFEAC3A6)
    val Rug = Color(0xFFF3D2BD)
    val Night = Color(0xFF141318)
    val NightCard = Color(0xFF22212A)
    val NightText = Color(0xFFF5F4F8)
    val NightMuted = Color(0xFFB4B1BF)
    val ShadowCard = listOf(ZipShadow(1.dp, 2.dp, Color(0x0A171B33)), ZipShadow(6.dp, 20.dp, Color(0x0D171B33)))
    val ShadowSheet = listOf(ZipShadow(-8.dp, 32.dp, Color(0x1F171B33)))
    val ShadowFloat = listOf(ZipShadow(4.dp, 14.dp, Color(0x29171B33)), ZipShadow(1.dp, 3.dp, Color(0x14171B33)))
    const val FontUi = "'SUIT Variable', 'SUIT', 'Pretendard', system-ui, sans-serif"
    const val FontDisplay = "'Jua', 'SUIT Variable', sans-serif"
    val Space1 = 4.dp
    val Space2 = 8.dp
    val Space3 = 12.dp
    val Space4 = 16.dp
    val Space5 = 20.dp
    val Space6 = 24.dp
    val Space7 = 32.dp
    val Space8 = 40.dp
    val SpaceSection = 24.dp
    val Gutter = 16.dp
    val FsCaption = 12.sp
    val FsSmall = 13.sp
    val FsBody = 15.sp
    val FsSubtitle = 17.sp
    val FsTitle = 20.sp
    val FsScreen = 24.sp
    val FsHero = 28.sp
    val RadiusScene = 32.dp
    val RadiusCard = 20.dp
    val RadiusControl = 16.dp
    val RadiusChip = 12.dp
    val RadiusSheet = 28.dp
    val RadiusPill = 999.dp
    val RadiusTile = 12.dp
    val ControlHeight = 52.dp
    val RowMinHeight = 56.dp
    val IconTile = 40.dp
    val TouchMin = 44.dp
    const val FwRegular = 400
    const val FwMedium = 500
    const val FwBold = 700
    const val FwHeavy = 800
    const val LhBody = 1.6f
    const val LhTight = 1.3f
    const val LsTight = -0.01f
    const val LsDisplay = -0.035f
    const val MotionFastMs = 120
    const val MotionBaseMs = 220
    const val MotionSlowMs = 360
}
