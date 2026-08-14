package com.midnight.kuira.dapp.wallet

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A big "select token" list must SCROLL, not overflow or clip. The token step centres its list when
 * short (the two NIGHT rows today) and scrolls it when long; a regression — a fixed-height container,
 * or dropping the scroll while centring — would strand every token past the fold.
 *
 * This injects hundreds of synthetic rows via [TokenModeStep]'s test-only `debugExtraTokenRows` hook
 * (always 0 in production) and proves the LAST row is reachable by scrolling the LazyColumn to it: a
 * clipped or overflowing list would never bring it into view. Mirrors the iOS `SendTokenListUITests`.
 *
 * A real [ComponentActivity] + `ComposeTestRule` is fine here (unlike [LoadingStateScreenshotTest]):
 * the token step has no Lottie/infinite animation to stall the test clock.
 */
@RunWith(AndroidJUnit4::class)
class SendTokenListScrollTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun bigTokenList_scrollsToTheLastRow() {
        val count = 300
        composeTestRule.setContent {
            // TokenModeStep emits into a ColumnScope (a top bar + the scrolling list), mirroring how
            // WalletSendScreen hosts it.
            Column(modifier = Modifier.fillMaxSize()) {
                TokenModeStep(
                    palette = SendPalette.from(WalletPanelColors.Default),
                    availableNight = "0",
                    onBack = {},
                    onPickUnshielded = {},
                    debugExtraTokenRows = count,
                )
            }
        }

        val lastTag = "$SYNTHETIC_TOKEN_TAG_PREFIX${count - 1}"
        composeTestRule.onNodeWithTag(TOKEN_LIST_TAG).performScrollToNode(hasTestTag(lastTag))
        composeTestRule.onNodeWithTag(lastTag).assertIsDisplayed()
    }
}
