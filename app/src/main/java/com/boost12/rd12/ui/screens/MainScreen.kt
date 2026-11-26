package com.boost12.rd12.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.boost12.rd12.domain.model.CommandCardItem
import com.boost12.rd12.domain.model.CommandCategory
import com.boost12.rd12.domain.model.RiskLevel
import com.boost12.rd12.ui.MainUiState
import com.boost12.rd12.ui.components.CommandCard
import com.boost12.rd12.ui.components.CommandDetailBottomSheet
import com.boost12.rd12.ui.components.ConnectionStateBanner
import com.boost12.rd12.ui.components.CustomCommandCard
import com.boost12.rd12.ui.components.DeviceProfileCard
import com.boost12.rd12.ui.components.TopStatusHeader
import com.boost12.rd12.ui.components.WirelessDebuggingSheet

@Composable
fun MainScreen(
    uiState: MainUiState,
    debuggerModeOn: Boolean,
    onSearchChanged: (String) -> Unit,
    onCategorySelected: (CommandCategory) -> Unit,
    onToggleCommand: (CommandCardItem, Boolean) -> Unit,
    onCardClick: (String) -> Unit,
    onCloseDetailSheet: () -> Unit,
    onTestRunCommand: (String) -> Unit,
    onRevertCommand: (String) -> Unit,
    onToggleRevertOnDisconnect: (String, Boolean) -> Unit,
    onUpdateTargetPackage: (String, String) -> Unit,
    onDeleteCustomCommand: (String) -> Unit,
    onOpenWirelessSheet: () -> Unit,
    onCloseWirelessSheet: () -> Unit,
    onPairDevice: (String, String) -> Unit,
    onConnectDevice: (String?) -> Unit,
    onDisconnect: () -> Unit,
    onSyncAll: () -> Unit,
    onDismissBatteryGuide: () -> Unit,
    onCustomCommandInputChanged: (String) -> Unit,
    onRunCustomCommand: () -> Unit,
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
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onConsumeTransientMessage: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.transientMessage) {
        val msg = uiState.transientMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            onConsumeTransientMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopStatusHeader(
                connectionState = uiState.connectionState,
                telemetry = uiState.telemetry,
                isSyncing = uiState.isSyncingAll,
                debuggerModeOn = debuggerModeOn,
                onConnectionChipClick = onOpenWirelessSheet,
                onSyncClick = onSyncAll,
                onHistoryClick = onOpenHistory,
                onSettingsClick = onOpenSettings
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("list_commands"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "search_and_categories") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = onSearchChanged,
                        placeholder = { Text("Search CPU, 90Hz, Doze, shell commands…") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = null
                            )
                        },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onSearchChanged("") }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Clear,
                                        contentDescription = "Clear search"
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_search")
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("row_categories")
                    ) {
                        items(CommandCategory.entries, key = { it.name }) { category ->
                            val selected = uiState.selectedCategory == category
                            FilterChip(
                                selected = selected,
                                onClick = { onCategorySelected(category) },
                                label = { Text(category.displayName) },
                                shape = RoundedCornerShape(12.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.testTag("chip_cat_${category.name}")
                            )
                        }
                    }
                }
            }

            if (uiState.searchQuery.isBlank() && uiState.selectedCategory == CommandCategory.ALL) {
                item(key = "device_profile") {
                    DeviceProfileCard(
                        profile = uiState.deviceProfile,
                        activeBoostCount = uiState.activeBoostCount
                    )
                }
            }

            item(key = "connection_state_banner") {
                ConnectionStateBanner(
                    connectionState = uiState.connectionState,
                    savedConfig = uiState.savedConfig,
                    showBatteryGuide = !uiState.batteryGuideDismissed,
                    onOpenSetupSheet = onOpenWirelessSheet,
                    onQuickReconnect = { onConnectDevice(null) },
                    onDismissBatteryGuide = onDismissBatteryGuide
                )
            }

            if (uiState.selectedCategory == CommandCategory.CUSTOM) {
                item(key = "custom_runner_top") {
                    CustomCommandCard(
                        runnerState = uiState.customRunner,
                        onCommandChanged = onCustomCommandInputChanged,
                        onRunClicked = onRunCustomCommand,
                        onSaveCustomToggle = onSaveCustomToggle
                    )
                }
            }

            if (uiState.filteredCards.isEmpty() && uiState.selectedCategory != CommandCategory.CUSTOM) {
                item(key = "empty_search_state") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "No matching commands found",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Try clearing your filter or create a custom ADB shell toggle.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (uiState.searchQuery.isNotEmpty() || uiState.selectedCategory != CommandCategory.ALL) {
                                OutlinedButton(
                                    onClick = {
                                        onSearchChanged("")
                                        onCategorySelected(CommandCategory.ALL)
                                    }
                                ) {
                                    Text("Reset Filters")
                                }
                            }
                        }
                    }
                }
            } else {
                items(
                    items = uiState.filteredCards,
                    key = { it.command.id }
                ) { cardItem ->
                    CommandCard(
                        item = cardItem,
                        onToggle = { target -> onToggleCommand(cardItem, target) },
                        onCardClick = { onCardClick(cardItem.command.id) }
                    )
                }
            }

            if (uiState.selectedCategory == CommandCategory.ALL && uiState.searchQuery.isBlank()) {
                item(key = "custom_runner_bottom") {
                    CustomCommandCard(
                        runnerState = uiState.customRunner,
                        onCommandChanged = onCustomCommandInputChanged,
                        onRunClicked = onRunCustomCommand,
                        onSaveCustomToggle = onSaveCustomToggle
                    )
                }
            }
        }
    }

    uiState.selectedDetailCard?.let { detailItem ->
        CommandDetailBottomSheet(
            item = detailItem,
            onDismiss = onCloseDetailSheet,
            onTestRun = onTestRunCommand,
            onRevert = onRevertCommand,
            onToggleRevertOnDisconnect = onToggleRevertOnDisconnect,
            onUpdateTargetPackage = onUpdateTargetPackage,
            onDeleteCustom = onDeleteCustomCommand
        )
    }

    if (uiState.showWirelessDebugSheet) {
        WirelessDebuggingSheet(
            connectionState = uiState.connectionState,
            savedConfig = uiState.savedConfig,
            discoveredPairing = uiState.discoveredPairing,
            discoveredConnect = uiState.discoveredConnect,
            onDismiss = onCloseWirelessSheet,
            onPairSubmit = onPairDevice,
            onConnectSubmit = { ep -> onConnectDevice(ep) },
            onDisconnect = onDisconnect
        )
    }
}
