package com.boost12.rd12.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boost12.rd12.data.adb.crypto.AppThemeMode
import com.boost12.rd12.domain.model.AdbConnectionState
import com.boost12.rd12.domain.model.AdbDebugLogEntry
import com.boost12.rd12.domain.model.CommandCardItem
import com.boost12.rd12.domain.model.SavedConnectionConfig
import com.boost12.rd12.ui.util.SystemIntents
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    connectionState: AdbConnectionState,
    savedConfig: SavedConnectionConfig,
    cards: List<CommandCardItem>,
    themeMode: AppThemeMode,
    dynamicColorEnabled: Boolean,
    debuggerModeEnabled: Boolean,
    debugLogs: List<AdbDebugLogEntry>,
    onBack: () -> Unit,
    onOpenWirelessSheet: () -> Unit,
    onDisconnectGracefully: () -> Unit,
    onResetPairingKeys: () -> Unit,
    onToggleAutoReconnect: (Boolean) -> Unit,
    onToggleRevertOnDisconnect: (String, Boolean) -> Unit,
    onThemeModeSelected: (AppThemeMode) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
    onToggleDebuggerMode: (Boolean) -> Unit,
    onClearDebugLogs: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Wireless Debugging Connection Management
            item {
                SettingsSectionCard(title = "Wireless Debugging Connection") {
                    val statusLine = when (connectionState) {
                        is AdbConnectionState.Connected ->
                            "Connected to ${connectionState.host}:${connectionState.port} (TLSv1.3)"
                        is AdbConnectionState.Pairing ->
                            "Pairing with ${connectionState.host}:${connectionState.port}…"
                        is AdbConnectionState.Connecting ->
                            "Connecting to ${connectionState.host}:${connectionState.port}…"
                        is AdbConnectionState.Error ->
                            "Error: ${connectionState.message}"
                        is AdbConnectionState.Disconnected ->
                            "Disconnected"
                    }
                    Text(
                        text = statusLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "RSA-2048 X.509 Fingerprint: ${savedConfig.certificateFingerprint ?: "Not generated"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (savedConfig.lastConnectPort > 0) {
                        Text(
                            text = "Last Endpoint: ${savedConfig.lastHost}:${savedConfig.lastConnectPort}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    SettingSwitchRow(
                        title = "Auto-reconnect (Exponential Backoff)",
                        subtitle = "Automatically reconnects (1s → 30s) if sleep or Doze closes the ADB socket.",
                        checked = savedConfig.autoReconnect,
                        onCheckedChange = onToggleAutoReconnect
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onOpenWirelessSheet) {
                            Text(if (savedConfig.isPaired) "Change Port / Pair" else "Pair Device")
                        }
                        if (connectionState is AdbConnectionState.Connected) {
                            OutlinedButton(onClick = onDisconnectGracefully) {
                                Text("Disconnect")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { SystemIntents.requestIgnoreBatteryOptimizations(context) }
                        ) {
                            Text("MIUI Battery Whitelist")
                        }
                        OutlinedButton(onClick = onResetPairingKeys) {
                            Text("Reset Keys")
                        }
                    }
                }
            }

            // 2. Appearance & Theme
            item {
                SettingsSectionCard(title = "Appearance") {
                    Text(
                        text = "Theme Mode",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = themeMode == mode,
                                onClick = { onThemeModeSelected(mode) },
                                label = {
                                    Text(
                                        mode.name.lowercase()
                                            .replaceFirstChar { it.uppercase() }
                                    )
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingSwitchRow(
                        title = "Material You Dynamic Color",
                        subtitle = "Use Android 12+ wallpaper accent instead of Boost12 electric mint.",
                        checked = dynamicColorEnabled,
                        onCheckedChange = onToggleDynamicColor
                    )
                }
            }

            // 3. Auto-Revert on Disconnect List
            item {
                SettingsSectionCard(title = "Auto-Revert on Disconnect") {
                    Text(
                        text = "Commands enabled below will automatically run their OFF revert script when you disconnect from Wireless Debugging.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val eligible = cards.filter { !it.command.requiresRoot }
                    eligible.forEachIndexed { idx, card ->
                        if (idx > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 6.dp),
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = card.command.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = card.command.category.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = card.state.revertOnDisconnect,
                                onCheckedChange = {
                                    onToggleRevertOnDisconnect(card.command.id, it)
                                }
                            )
                        }
                    }
                }
            }

            // 4. ADB Verbose Debugger Mode & Live Console
            item {
                SettingsSectionCard(title = "ADB Debugger Mode (v1.0)") {
                    SettingSwitchRow(
                        title = "Enable Verbose ADB & Shell Debugger",
                        subtitle = "Captures SPAKE2+ handshake steps, TLSv1.3 packet events, and raw shell exit codes.",
                        checked = debuggerModeEnabled,
                        onCheckedChange = onToggleDebuggerMode
                    )

                    if (debuggerModeEnabled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Live Wire & Shell Trace (${debugLogs.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row {
                                IconButton(
                                    onClick = {
                                        val dump = debugLogs.joinToString("\n") { entry ->
                                            "[${timeFmt.format(Date(entry.timestampEpochMs))}] ${entry.tag}: ${entry.message} ${entry.details.orEmpty()}"
                                        }
                                        clipboard.setText(AnnotatedString(dump))
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ContentCopy,
                                        contentDescription = "Copy debug trace"
                                    )
                                }
                                IconButton(onClick = onClearDebugLogs) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = "Clear debug trace"
                                    )
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.background)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (debugLogs.isEmpty()) {
                                Text(
                                    text = "Debugger Mode active — awaiting ADB mDNS, SPAKE2+, or shell events.",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                debugLogs.take(40).forEach { entry ->
                                    Text(
                                        text = "${timeFmt.format(Date(entry.timestampEpochMs))} [${entry.tag}] ${entry.message}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
