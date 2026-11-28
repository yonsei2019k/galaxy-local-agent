package com.user.localagent.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipComponentsTest {
    private fun assertContrast(minimum: Double, foreground: Color, background: Color, what: String) {
        val ratio = zipContrastRatio(foreground, background)
        assertTrue("$what: %.2f:1 < %.1f:1".format(ratio, minimum), ratio >= minimum)
    }

    @Test
    fun contrastMathMatchesWcagReferencePoints() {
        assertEquals(21.0, zipContrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, zipContrastRatio(ZipHapTokens.Bg, ZipHapTokens.Bg), 0.0001)
        assertEquals(6.13, zipContrastRatio(ZipHapTokens.TextSub, ZipHapTokens.Surface), 0.01)
        assertEquals(zipContrastRatio(ZipHapTokens.Cobalt, Color.White), zipContrastRatio(Color.White, ZipHapTokens.Cobalt), 0.0)
    }

    @Test
    fun everyToneContentIsReadableTextOnItsSoftTile() {
        ZipTone.entries.forEach { tone -> assertContrast(4.5, tone.content, tone.soft, "ZipTone.${tone.name}") }
    }

    @Test
    fun toneMappingUsesTokenTints() {
        assertEquals(ZipHapTokens.CobaltSoft, ZipTone.Cobalt.soft)
        assertEquals(ZipHapTokens.CobaltText, ZipTone.Cobalt.content)
        assertEquals(ZipHapTokens.CoralSoft, ZipTone.Coral.soft)
        assertEquals(ZipHapTokens.MintSoft, ZipTone.Mint.soft)
        assertEquals(ZipHapTokens.MintText, ZipTone.Mint.content)
        assertEquals(ZipHapTokens.SunSoft, ZipTone.Sun.soft)
        assertEquals(ZipHapTokens.SunText, ZipTone.Sun.content)
        assertEquals(ZipHapTokens.LilacSoft, ZipTone.Lilac.soft)
        assertEquals(ZipHapTokens.LilacText, ZipTone.Lilac.content)
        assertEquals(ZipHapTokens.SurfaceSunken, ZipTone.Neutral.soft)
        assertEquals(ZipHapTokens.TextSub, ZipTone.Neutral.content)
    }

    @Test
    fun placeholderAndSecondaryTextPassAaOnFilledInputs() {
        listOf(ZipHapColors.SurfaceSunken, ZipHapColors.Surface, ZipHapColors.Background).forEach { bg ->
            assertContrast(4.5, ZipHapColors.Placeholder, bg, "placeholder")
            assertContrast(4.5, ZipHapColors.TextSecondary, bg, "text-sub")
        }
    }

    @Test
    fun controlOutlinesAndFocusRingKeepNonTextContrast() {
        listOf(ZipHapColors.SurfaceSunken, ZipHapColors.Surface, ZipHapColors.Background).forEach { bg ->
            assertContrast(3.0, ZipHapColors.Outline, bg, "outline")
            assertContrast(3.0, ZipHapColors.Cobalt, bg, "focus ring")
        }
    }

    @Test
    fun monochromeTilesAreGreyWithInkIconsExceptCoral() {
        ZipTone.entries.forEach { tone ->
            val (background, content) = zipTileColors(tone)
            if (tone == ZipTone.Coral) {
                assertEquals(ZipTone.Coral.soft to ZipTone.Coral.content, background to content)
            } else {
                assertEquals("ZipTone.${tone.name}", ZipHapColors.Soft to ZipHapColors.TextPrimary, background to content)
            }
            assertContrast(4.5, content, background, "tile ${tone.name}")
        }
    }

    @Test
    fun buttonLabelsAreReadable() {
        assertContrast(4.5, ZipHapColors.OnInk, ZipHapColors.Ink, "primary ink pill label")
        assertContrast(3.0, ZipHapColors.Ink, ZipHapColors.Background, "primary ink pill on the page")
        assertContrast(4.5, ZipHapTokens.NightText, ZipHapTokens.Night, "dark security card text")
        assertContrast(4.5, Color.White, ZipHapColors.Cobalt, "primary label")
        assertContrast(4.5, Color.White, ZipHapColors.Coral, "destructive label")
        assertContrast(4.5, ZipHapColors.TextPrimary, ZipHapColors.SurfaceSunken, "secondary label")
        assertContrast(4.5, ZipHapTokens.CobaltText, ZipHapColors.Surface, "text button")
        assertContrast(4.5, ZipTone.Coral.content, ZipHapColors.Surface, "destructive text button")
    }

    @Test
    fun legacyColorNamesMapOntoTheTwoPointZeroPalette() {
        assertEquals(ZipHapTokens.Bg, ZipHapColors.Background)
        assertEquals(ZipHapTokens.Divider, ZipHapColors.OutlineSoft)
        assertEquals(ZipHapTokens.SurfaceSunken, ZipHapColors.SurfaceSoft)
        assertEquals(ZipHapTokens.TextSub, ZipHapColors.TextSecondary)
        assertEquals(ZipHapColors.Background, ZipHapColors.DeepNavy)
        assertEquals(ZipHapColors.Cobalt, ZipHapColors.Periwinkle)
    }

    @Test
    fun siteLoginPillIsAnInkPillOnWhiteWithReadableText() {
        // Concept match S7: the strip under the chat is a small ink pill on the white chat background.
        assertEquals(ZipHapTokens.Ink, ZipInkPillStyle.Container)
        assertEquals(ZipHapTokens.Surface, ZipInkPillStyle.Background)
        assertContrast(4.5, ZipInkPillStyle.Content, ZipInkPillStyle.Container, "pill label")
        assertContrast(4.5, ZipInkPillStyle.DisabledContent, ZipInkPillStyle.DisabledContainer, "disabled pill label")
        assertContrast(3.0, ZipInkPillStyle.Container, ZipInkPillStyle.Background, "pill against the chat background")
    }
}
