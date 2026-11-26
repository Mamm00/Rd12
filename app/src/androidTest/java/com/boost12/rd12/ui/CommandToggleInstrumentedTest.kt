package com.boost12.rd12.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.boost12.rd12.data.catalog.DefaultCommandCatalog
import com.boost12.rd12.domain.model.CommandCardItem
import com.boost12.rd12.domain.model.CommandRuntimeState
import com.boost12.rd12.ui.components.CommandCard
import com.boost12.rd12.ui.theme.Boost12Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommandToggleInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun toggleFlowReflectsVerifiedState() {
        val cmd = DefaultCommandCatalog.builtInCommands.first { it.id == "display_force_90hz" }
        var item by mutableStateOf(
            CommandCardItem(
                command = cmd,
                state = CommandRuntimeState(commandId = cmd.id, verifiedEnabled = false)
            )
        )

        composeTestRule.setContent {
            Boost12Theme {
                CommandCard(
                    item = item,
                    onToggle = { target ->
                        item = item.copy(
                            state = item.state.copy(verifiedEnabled = target)
                        )
                    },
                    onCardClick = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("toggle_display_force_90hz").assertIsDisplayed()
        composeTestRule.onNodeWithTag("toggle_display_force_90hz").assertIsOff()
        composeTestRule.onNodeWithTag("toggle_display_force_90hz").performClick()
        composeTestRule.onNodeWithTag("toggle_display_force_90hz").assertIsOn()
    }
}
