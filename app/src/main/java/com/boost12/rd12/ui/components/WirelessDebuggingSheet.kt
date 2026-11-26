package com.boost12.rd12.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.boost12.rd12.domain.model.AdbConnectionState
import com.boost12.rd12.domain.model.MdnsDiscoveredEndpoint
import com.boost12.rd12.domain.model.SavedConnectionConfig
import com.boost12.rd12.ui.util.SystemIntents

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WirelessDebuggingSheet(
    connectionState: AdbConnectionState,
    savedConfig: SavedConnectionConfig,
    discoveredPairing: MdnsDiscoveredEndpoint?,
    discoveredConnect: MdnsDiscoveredEndpoint?,
    onDismiss: () -> Unit,
    onPairSubmit: (hostPort: String, sixDigitCode: String) -> Unit,
    onConnectSubmit: (hostPort: String) -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selectedTab by rememberSaveable {
        mutableIntStateOf(if (savedConfig.isPaired) 1 else 0)
    }

    var pairingHostPort by rememberSaveable {
        mutableStateOf(
            discoveredPairing?.addressLabel
                ?: savedConfig.takeIf { it.lastPairingPort > 0 }
                    ?.let { "${it.lastHost}:${it.lastPairingPort}" }
                    .orEmpty()
        )
    }
    var sixDigitCode by rememberSaveable { mutableStateOf("") }

    var connectHostPort by rememberSaveable {
        mutableStateOf(
            discoveredConnect?.addressLabel
                ?: savedConfig.takeIf { it.lastConnectPort > 0 }
                    ?.let { "${it.lastHost}:${it.lastConnectPort}" }
                    .orEmpty()
        )
    }

    LaunchedEffect(discoveredPairing) {
        if (discoveredPairing != null) {
            pairingHostPort = discoveredPairing.addressLabel
        }
    }
    LaunchedEffect(discoveredConnect) {
        if (discoveredConnect != null) {
            connectHostPort = discoveredConnect.addressLabel
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
                .testTag("sheet_wireless_debugging"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Wireless Debugging (On-Device ADB)",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Boost12 connects directly to your Redmi 12 4G over TLSv1.3 using Android 11+ SPAKE2+ pairing. Credentials are encrypted on-device for one-tap reconnection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // mDNS auto-discovery status pill
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = if (discoveredPairing != null || discoveredConnect != null) {
                            Icons.Outlined.CheckCircle
                        } else {
                            Icons.Outlined.Radar
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "mDNS Auto-Discovery (_adb-tls-*._tcp)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val mdnsText = buildString {
                            if (discoveredPairing != null) {
                                append("Pairing port: ${discoveredPairing.addressLabel}")
                            }
                            if (discoveredConnect != null) {
                                if (isNotEmpty()) append(" · ")
                                append("Connect port: ${discoveredConnect.addressLabel}")
                            }
                            if (isEmpty()) {
                                append("Scanning local Wi-Fi… Open Wireless Debugging or enter IP:port manually below.")
                            }
                        }
                        Text(
                            text = mdnsText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = { Text("1. Pair (6-Digit Code)") }
                )
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = { Text("2. Connect Session") }
                )
            }

            if (selectedTab == 0) {
                Text(
                    text = "Tip: Open Developer Options in Split-Screen, tap Wireless Debugging → 'Pair device with pairing code', and enter the 6-digit code & pairing IP:port shown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedButton(
                    onClick = {
                        SystemIntents.openDeveloperOptions(context, adjacentSplitScreen = true)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Outlined.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open Developer Options (Split-Screen Friendly)")
                }

                OutlinedTextField(
                    value = pairingHostPort,
                    onValueChange = { pairingHostPort = it },
                    label = { Text("Pairing IP:Port") },
                    placeholder = { Text("192.168.1.20:39123 or :39123") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_pairing_host_port")
                )

                OutlinedTextField(
                    value = sixDigitCode,
                    onValueChange = { value ->
                        if (value.length <= 6 && value.all { it.isDigit() }) {
                            sixDigitCode = value
                        }
                    },
                    label = { Text("6-Digit Wi-Fi Pairing Code") },
                    placeholder = { Text("123456") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_pairing_code")
                )

                Button(
                    onClick = { onPairSubmit(pairingHostPort, sixDigitCode) },
                    enabled = sixDigitCode.length == 6 &&
                        connectionState !is AdbConnectionState.Pairing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_submit_pair")
                ) {
                    Text(
                        if (connectionState is AdbConnectionState.Pairing) {
                            "Pairing…"
                        } else {
                            "Pair with SPAKE2+"
                        }
                    )
                }
            } else {
                Text(
                    text = "Enter the main 'IP address & Port' shown at the top of Wireless Debugging (note: this port differs from the temporary pairing port).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = connectHostPort,
                    onValueChange = { connectHostPort = it },
                    label = { Text("Connect IP:Port") },
                    placeholder = { Text("192.168.1.20:41555 or :41555") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_connect_host_port")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onConnectSubmit(connectHostPort) },
                        enabled = connectionState !is AdbConnectionState.Connecting,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_submit_connect")
                    ) {
                        Text(
                            if (connectionState is AdbConnectionState.Connecting) {
                                "Connecting…"
                            } else {
                                "Connect TLSv1.3"
                            }
                        )
                    }

                    if (connectionState is AdbConnectionState.Connected) {
                        OutlinedButton(
                            onClick = onDisconnect,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Disconnect")
                        }
                    }
                }
            }

            if (connectionState is AdbConnectionState.Error) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = connectionState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
