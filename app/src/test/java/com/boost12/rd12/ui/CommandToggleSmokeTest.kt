package com.boost12.rd12.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.boost12.rd12.data.catalog.DefaultCommandCatalog
import com.boost12.rd12.domain.model.CommandCardItem
import com.boost12.rd12.domain.model.CommandRuntimeState
import com.boost12.rd12.ui.components.CommandCard
import com.boost12.rd12.ui.theme.Boost12Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommandToggleSmokeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `toggle switch updates to ON only after verified state updates and shows error banner on mismatch`() {
        val animCmd = DefaultCommandCatalog.builtInCommands.first { it.id == "anim_scale_zero" }
        var cardItem by mutableStateOf(
            CommandCardItem(
                command = animCmd,
                state = CommandRuntimeState(
                    commandId = animCmd.id,
                    verifiedEnabled = false
                )
            )
        )

        var simulatedVerifyPasses = true

        composeTestRule.setContent {
            Boost12Theme {
                CommandCard(
                    item = cardItem,
                    onToggle = { target ->
                        cardItem = if (simulatedVerifyPasses) {
                            cardItem.copy(
                                state = cardItem.state.copy(
                                    verifiedEnabled = target,
                                    errorBanner = null
                                )
                            )
                        } else {
                            cardItem.copy(
                                state = cardItem.state.copy(
                                    verifiedEnabled = false,
                                    errorBanner = "Didn't apply — tap to inspect raw output"
                                )
                            )
                        }
                    },
                    onCardClick = {}
                )
            }
        }

        // Initial state is OFF
        composeTestRule.onNodeWithText("Instant UI Animations (0.0x)").assertIsDisplayed()
        composeTestRule.onNodeWithTag("toggle_anim_scale_zero").assertIsOff()

        // Tap toggle when verification succeeds -> Switch turns ON
        composeTestRule.onNodeWithTag("toggle_anim_scale_zero").performClick()
        composeTestRule.onNodeWithTag("toggle_anim_scale_zero").assertIsOn()
        composeTestRule.onNodeWithText("Verified ON").assertIsDisplayed()

        // Turn back OFF
        composeTestRule.onNodeWithTag("toggle_anim_scale_zero").performClick()
        composeTestRule.onNodeWithTag("toggle_anim_scale_zero").assertIsOff()

        // Simulate verification failing -> Switch must stay OFF and show error banner
        simulatedVerifyPasses = false
        composeTestRule.onNodeWithTag("toggle_anim_scale_zero").performClick()
        composeTestRule.onNodeWithTag("toggle_anim_scale_zero").assertIsOff()
        composeTestRule.onNodeWithTag("error_anim_scale_zero", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Didn't apply — tap to inspect raw output").assertIsDisplayed()
    }

    @Test
    fun `root-only command card is disabled and displays Unavailable on this device`() {
        val rootCmd = DefaultCommandCatalog.builtInCommands.first { it.requiresRoot }
        val item = CommandCardItem(
            command = rootCmd,
            state = CommandRuntimeState(commandId = rootCmd.id, verifiedEnabled = false)
        )

        composeTestRule.setContent {
            Boost12Theme {
                CommandCard(
                    item = item,
                    onToggle = {},
                    onCardClick = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Unavailable on this device").assertIsDisplayed()
        composeTestRule.onNodeWithTag("toggle_${rootCmd.id}").assertIsNotEnabled()
    }
}
