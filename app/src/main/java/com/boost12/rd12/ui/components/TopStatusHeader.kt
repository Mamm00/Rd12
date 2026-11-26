package com.boost12.rd12.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boost12.rd12.domain.model.AdbConnectionState
import com.boost12.rd12.domain.model.AdbErrorKind
import com.boost12.rd12.domain.model.DeviceTelemetry
import com.boost12.rd12.ui.theme.AdvancedPillColor
import com.boost12.rd12.ui.theme.ModeratePillColor
import com.boost12.rd12.ui.theme.SafePillColor

@Composable
fun TopStatusHeader(
    connectionState: AdbConnectionState,
    telemetry: DeviceTelemetry,
    isSyncing: Boolean,
    debuggerModeOn: Boolean,
    onConnectionChipClick: () -> Unit,
    onSyncClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Boost12",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (debuggerModeOn) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "DBG",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onSyncClick,
                    modifier = Modifier.testTag("btn_sync_all")
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Verify all commands",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = onHistoryClick,
                    modifier = Modifier.testTag("btn_open_history")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = "Command History",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier.testTag("btn_open_settings")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Live connection status chip + dumpsys battery/temp telemetry pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ConnectionStatusChip(
                state = connectionState,
                onClick = onConnectionChipClick
            )

            if (connectionState is AdbConnectionState.Connected &&
                (telemetry.batteryPercent != null || telemetry.temperatureCelsius != null)
            ) {
                TelemetryPill(telemetry = telemetry)
            }
        }
    }
}

@Composable
fun ConnectionStatusChip(
    state: AdbConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (label, dotColor) = when (state) {
        is AdbConnectionState.Disconnected -> "Disconnected · Tap to connect" to Color(0xFF8A99AD)
        is AdbConnectionState.Pairing -> "Pairing (${state.port})…" to ModeratePillColor
        is AdbConnectionState.Connecting -> "Connecting (${state.port})…" to ModeratePillColor
        is AdbConnectionState.Connected -> "Connected · TLS" to SafePillColor
        is AdbConnectionState.Error -> {
            val shortLabel = when (state.kind) {
                AdbErrorKind.UNAUTHORIZED_REVOKED -> "Error · Auth Revoked"
                AdbErrorKind.PAIRING_TIMEOUT -> "Error · Pairing Timeout"
                AdbErrorKind.SOCKET_DROPPED_DOZE ->
                    if (state.nextRetryInMs != null) "Reconnecting (#${state.attempt})…" else "Error · Socket Closed"
                else -> "Error · Tap to retry"
            }
            shortLabel to AdvancedPillColor
        }
    }

    val animatedDot by animateColorAsState(targetValue = dotColor, label = "conn_dot")

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .testTag("chip_connection_status"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(animatedDot)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun TelemetryPill(telemetry: DeviceTelemetry) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
            .padding(horizontal = 11.dp, vertical = 7.dp)
            .testTag("pill_telemetry"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        telemetry.batteryPercent?.let { pct ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (telemetry.isCharging) {
                        Icons.Outlined.BatteryChargingFull
                    } else {
                        Icons.Outlined.BatteryStd
                    },
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "$pct%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        telemetry.temperatureCelsius?.let { temp ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Thermostat,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (temp >= 41f) ModeratePillColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "%.1f°C".format(temp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        telemetry.activeRefreshRateHz?.let { hz ->
            Text(
                text = "${hz}Hz",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
