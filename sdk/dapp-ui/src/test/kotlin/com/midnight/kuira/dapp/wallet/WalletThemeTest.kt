package com.midnight.kuira.dapp.wallet

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.max
import kotlin.math.min

/** Registry invariants + [ThemeStore] persistence round-trip. */
@RunWith(RobolectricTestRunner::class)
class WalletThemeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPrefs() {
        // Robolectric reuses SharedPreferences across tests in the same process; start clean.
        context.getSharedPreferences("wallet_panel", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `default is first so it is the visual fallback`() {
        assertSame(WalletThemes.Default, WalletThemes.all.first())
    }

    @Test
    fun `theme ids are unique and labels non-blank`() {
        val ids = WalletThemes.all.map { it.id }
        assertEquals("ids must be unique", ids.size, ids.toSet().size)
        assertTrue("every theme needs a label", WalletThemes.all.all { it.label.isNotBlank() })
    }

    @Test
    fun `byId resolves a known theme`() {
        assertSame(WalletThemes.Dracula, WalletThemes.byId("dracula"))
    }

    @Test
    fun `byId falls back to Default for null or unknown id`() {
        assertSame(WalletThemes.Default, WalletThemes.byId(null))
        assertSame(WalletThemes.Default, WalletThemes.byId("a_removed_theme"))
    }

    @Test
    fun `store returns null until a theme is chosen`() {
        assertNull(ThemeStore.selectedThemeId(context))
    }

    @Test
    fun `store round-trips the selected id`() {
        ThemeStore.setSelectedThemeId(context, WalletThemes.Nord.id)
        assertEquals(WalletThemes.Nord.id, ThemeStore.selectedThemeId(context))
        // And a later pick overwrites, not appends.
        ThemeStore.setSelectedThemeId(context, WalletThemes.TokyoNight.id)
        assertEquals(WalletThemes.TokyoNight.id, ThemeStore.selectedThemeId(context))
    }

    @Test
    fun `disabled button fill is distinct from the enabled fill`() {
        // Regression guard: reusing the enabled `button` color for `buttonDisabled` makes a
        // disabled button look tappable. Every theme must distinguish the two.
        WalletThemes.all.forEach { theme ->
            assertNotEquals(
                "${theme.label}: disabled button fill must differ from enabled",
                theme.colors.button,
                theme.colors.buttonDisabled,
            )
        }
    }

    @Test
    fun `imported themes keep text and secondary text above WCAG AA`() {
        // Regression guard for the Tokyo Night / Dracula readability bugs: a theme's body
        // (onSheet) and secondary (onSheetDim) text must clear 4.5:1 against its own surface.
        // Excludes the Kuira monochrome/Paper palettes, whose alpha-tuned tokens are the
        // pre-existing brand baseline rather than imported configs.
        val imported = WalletThemes.all.filter {
            it !== WalletThemes.Default && it !== WalletThemes.KuiraLight
        }
        imported.forEach { theme ->
            val base = theme.colors.sheetBackground
            val bodyContrast = contrast(theme.colors.onSheet, base)
            val secondaryContrast = contrast(theme.colors.onSheetDim, base)
            assertTrue("${theme.label}: body text $bodyContrast < 4.5", bodyContrast >= 4.5)
            assertTrue("${theme.label}: secondary text $secondaryContrast < 4.5", secondaryContrast >= 4.5)
        }
    }

    /** WCAG 2.x contrast ratio of [fg] (composited over [bg]) against [bg]. */
    private fun contrast(fg: Color, bg: Color): Double {
        val opaque = composite(fg, bg)
        val a = opaque.luminance() + 0.05
        val b = bg.luminance() + 0.05
        return max(a, b).toDouble() / min(a, b).toDouble()
    }

    /** Straight-alpha source-over composite, so translucent tokens are measured as drawn. */
    private fun composite(fg: Color, bg: Color): Color {
        val a = fg.alpha
        return Color(
            red = fg.red * a + bg.red * (1 - a),
            green = fg.green * a + bg.green * (1 - a),
            blue = fg.blue * a + bg.blue * (1 - a),
        )
    }
}
