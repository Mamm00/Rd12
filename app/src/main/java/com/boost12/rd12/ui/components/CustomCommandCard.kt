package com.boost12.rd12.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boost12.rd12.domain.model.RiskLevel
import com.boost12.rd12.ui.CustomRunnerUiState

@Composable
fun CustomCommandCard(
    runnerState: CustomRunnerUiState,
    onCommandChanged: (String) -> Unit,
    onRunClicked: () -> Unit,
    onSaveCustomToggle: (
        name: String,
        summary: String,
        onCmd: String,
        offCmd: String,
        verifyCmd: String,
        expectedOnSubstring: String,
        risk: RiskLevel,
        revertOnDisconnect: Boolean,
        onSaved: () -> Unit
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    var showBuilder by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var summary by rememberSaveable { mutableStateOf("") }
    var onCmd by rememberSaveable { mutableStateOf("") }
    var offCmd by rememberSaveable { mutableStateOf("") }
    var verifyCmd by rememberSaveable { mutableStateOf("") }
    var expectedOn by rememberSaveable { mutableStateOf("") }
    var selectedRisk by rememberSaveable { mutableStateOf(RiskLevel.MODERATE) }
    var revertOnDisconnect by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("card_custom_runner"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Custom ADB Shell Command",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Run any safe shell command or save an ON/OFF toggle with custom verification.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = runnerState.adHocCommand,
                onValueChange = onCommandChanged,
                placeholder = { Text("e.g. dumpsys meminfo | head -n 12") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_custom_shell")
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRunClicked,
                    enabled = !runnerState.isRunningAdHoc && runnerState.adHocCommand.isNotBlank(),
                    modifier = Modifier.testTag("btn_run_custom_shell")
                ) {
                    if (runnerState.isRunningAdHoc) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Run")
                }

                OutlinedButton(
                    onClick = {
                        if (!showBuilder && onCmd.isBlank() && runnerState.adHocCommand.isNotBlank()) {
                            onCmd = runnerState.adHocCommand
                        }
                        showBuilder = !showBuilder
                    },
                    modifier = Modifier.testTag("btn_toggle_custom_builder")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (showBuilder) "Hide Save Form" else "Save as ON/OFF Toggle")
                }
            }

            if (runnerState.lastAdHocError != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = runnerState.lastAdHocError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("txt_custom_error")
                )
            }

            if (!runnerState.lastAdHocOutput.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .padding(12.dp)
                ) {
                    runnerState.lastDurationMs?.let { ms ->
                        Text(
                            text = "Output (${ms}ms)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        text = runnerState.lastAdHocOutput,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            AnimatedVisibility(visible = showBuilder) {
                Column(modifier = Modifier.padding(top = 14.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "New Custom Verified Toggle",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Card Name") },
                        placeholder = { Text("e.g. Touch Pointer Speed Boost") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = summary,
                        onValueChange = { summary = it },
                        label = { Text("One-line Description") },
                        placeholder = { Text("Sets pointer_speed to +3; OFF restores 0") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = onCmd,
                        onValueChange = { onCmd = it },
                        label = { Text("ON Shell Command(s)") },
                        placeholder = { Text("settings put system pointer_speed 3") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = offCmd,
                        onValueChange = { offCmd = it },
                        label = { Text("OFF Shell Command(s) (Revert)") },
                        placeholder = { Text("settings put system pointer_speed 0") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = verifyCmd,
                        onValueChange = { verifyCmd = it },
                        label = { Text("Verify Shell Command") },
                        placeholder = { Text("settings get system pointer_speed") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = expectedOn,
                        onValueChange = { expectedOn = it },
                        label = { Text("Verify Output String Defining ON") },
                        placeholder = { Text("3") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RiskLevel.entries.forEach { level ->
                            FilterChip(
                                selected = selectedRisk == level,
                                onClick = { selectedRisk = level },
                                label = { Text(level.displayName) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Revert on ADB disconnect",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Switch(
                            checked = revertOnDisconnect,
                            onCheckedChange = { revertOnDisconnect = it }
                        )
                    }

                    Button(
                        onClick = {
                            onSaveCustomToggle(
                                name,
                                summary,
                                onCmd,
                                offCmd,
                                verifyCmd,
                                expectedOn,
                                selectedRisk,
                                revertOnDisconnect
                            ) {
                                name = ""
                                summary = ""
                                onCmd = ""
                                offCmd = ""
                                verifyCmd = ""
                                expectedOn = ""
                                showBuilder = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Verified Toggle Card")
                    }
                }
            }
        }
    }
}
