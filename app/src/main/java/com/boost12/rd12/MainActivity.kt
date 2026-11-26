package com.boost12.rd12

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.boost12.rd12.ui.Boost12ViewModel
import com.boost12.rd12.ui.screens.HistoryScreen
import com.boost12.rd12.ui.screens.MainScreen
import com.boost12.rd12.ui.screens.SettingsScreen
import com.boost12.rd12.ui.theme.Boost12Theme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Boost12AppRoot()
        }
    }
}

@Composable
fun Boost12AppRoot(
    viewModel: Boost12ViewModel = hiltViewModel()
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val debuggerMode by viewModel.debuggerModeEnabled.collectAsStateWithLifecycle()
    val debugLogs by viewModel.debugLogs.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val historyRecords by viewModel.historyRecords.collectAsStateWithLifecycle()

    val navController = rememberNavController()

    Boost12Theme(
        themeMode = themeMode,
        dynamicColor = dynamicColor
    ) {
        NavHost(
            navController = navController,
            startDestination = "main"
        ) {
            composable("main") {
                MainScreen(
                    uiState = uiState,
                    debuggerModeOn = debuggerMode,
                    onSearchChanged = viewModel::onSearchQueryChanged,
                    onCategorySelected = viewModel::onCategorySelected,
                    onToggleCommand = viewModel::onToggleCommand,
                    onCardClick = viewModel::openCommandDetail,
                    onCloseDetailSheet = viewModel::closeCommandDetail,
                    onTestRunCommand = viewModel::onTestRunCommand,
                    onRevertCommand = viewModel::onRevertSingleCommand,
                    onToggleRevertOnDisconnect = viewModel::onSetRevertOnDisconnect,
                    onUpdateTargetPackage = viewModel::onUpdateTargetPackage,
                    onDeleteCustomCommand = viewModel::onDeleteCustomCommand,
                    onOpenWirelessSheet = viewModel::openWirelessDebuggingSheet,
                    onCloseWirelessSheet = viewModel::closeWirelessDebuggingSheet,
                    onPairDevice = viewModel::onPairDevice,
                    onConnectDevice = viewModel::onConnectDevice,
                    onDisconnect = viewModel::onDisconnectGracefully,
                    onSyncAll = viewModel::onSyncAllCommands,
                    onDismissBatteryGuide = viewModel::dismissBatteryGuide,
                    onCustomCommandInputChanged = viewModel::onAdHocCommandChanged,
                    onRunCustomCommand = viewModel::onRunAdHocCommand,
                    onSaveCustomToggle = viewModel::onSaveCustomToggle,
                    onOpenHistory = { navController.navigate("history") },
                    onOpenSettings = { navController.navigate("settings") },
                    onConsumeTransientMessage = viewModel::consumeTransientMessage
                )
            }

            composable("history") {
                HistoryScreen(
                    records = historyRecords,
                    activeBoostCount = uiState.activeBoostCount,
                    isRevertingAll = uiState.isRevertingAll,
                    onBack = { navController.popBackStack() },
                    onRevertAll = viewModel::onRevertAllCommands,
                    onClearHistory = viewModel::onClearHistory
                )
            }

            composable("settings") {
                SettingsScreen(
                    connectionState = uiState.connectionState,
                    savedConfig = uiState.savedConfig,
                    cards = uiState.allCards,
                    themeMode = themeMode,
                    dynamicColorEnabled = dynamicColor,
                    debuggerModeEnabled = debuggerMode,
                    debugLogs = debugLogs,
                    onBack = { navController.popBackStack() },
                    onOpenWirelessSheet = {
                        navController.popBackStack()
                        viewModel.openWirelessDebuggingSheet()
                    },
                    onDisconnectGracefully = viewModel::onDisconnectGracefully,
                    onResetPairingKeys = viewModel::onResetPairingCredentials,
                    onToggleAutoReconnect = viewModel::onSetAutoReconnect,
                    onToggleRevertOnDisconnect = viewModel::onSetRevertOnDisconnect,
                    onThemeModeSelected = viewModel::onSetThemeMode,
                    onToggleDynamicColor = viewModel::onSetDynamicColor,
                    onToggleDebuggerMode = viewModel::onSetDebuggerMode,
                    onClearDebugLogs = viewModel::onClearDebugLogs
                )
            }
        }
    }
}
