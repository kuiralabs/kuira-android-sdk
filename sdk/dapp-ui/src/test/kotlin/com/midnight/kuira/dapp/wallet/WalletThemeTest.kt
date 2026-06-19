package com.midnight.kuira.dapp.wallet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
