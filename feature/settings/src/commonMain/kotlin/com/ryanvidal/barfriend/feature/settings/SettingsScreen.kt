package com.ryanvidal.barfriend.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.ryanvidal.barfriend.core.designsystem.SectionHeader
import com.ryanvidal.barfriend.core.designsystem.StatusReadyGreen
import com.ryanvidal.barfriend.core.model.AppThemeMode
import com.ryanvidal.barfriend.core.model.VolumeUnit
import kotlinx.serialization.Serializable

@Serializable
data object SettingsRoute : NavKey

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsContent(
        uiState = uiState,
        onUnitSelected = viewModel::setPreferredUnit,
        onThemeSelected = viewModel::setThemeMode,
        onSyncNowClick = viewModel::syncNow,
        onSignOutClick = viewModel::signOut,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    uiState: SettingsUiState,
    onUnitSelected: (VolumeUnit) -> Unit,
    onThemeSelected: (AppThemeMode) -> Unit,
    onSyncNowClick: () -> Unit,
    onSignOutClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSignOutDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text("Bar & Settings") })
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ActiveBarCard(
                activeBarName = uiState.activeBarName,
                serverUrl = uiState.serverUrl,
                isConnected = uiState.isConnected,
            )

            SyncCacheCard(
                isSyncing = uiState.syncStatus.isSyncing,
                lastSyncedEpochMillis = uiState.syncStatus.lastSyncedEpochMillis,
                syncErrorMessage = uiState.syncStatus.errorMessage,
                isActionEnabled = !uiState.syncStatus.isSyncing && !uiState.isSigningOut,
                onSyncNowClick = onSyncNowClick,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(title = "Default Measurement Units")
                val units = listOf(VolumeUnit.OZ, VolumeUnit.ML, VolumeUnit.CL)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    units.forEachIndexed { index, unit ->
                        SegmentedButton(
                            selected = uiState.preferredUnit == unit,
                            onClick = { onUnitSelected(unit) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = units.size,
                            ),
                            label = { Text(unit.label) },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(title = "Appearance")
                val modes = listOf(
                    AppThemeMode.DARK to "Dark",
                    AppThemeMode.LIGHT to "Light",
                    AppThemeMode.SYSTEM to "System",
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = uiState.themeMode == mode,
                            onClick = { onThemeSelected(mode) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = modes.size,
                            ),
                            label = { Text(label) },
                        )
                    }
                }
            }

            AnimatedVisibility(visible = uiState.errorMessage != null) {
                uiState.errorMessage?.let { message ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { showSignOutDialog = true },
                enabled = !uiState.isSigningOut,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isSigningOut) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Signing Out...")
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sign Out")
                }
            }
        }
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text("Sign out of Bar Friend?") },
            text = {
                Text(
                    "Local cached recipes on this device will stay available for sign-in reconnection, or you can reconnect anytime.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSignOutDialog = false
                        onSignOutClick()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Sign Out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ActiveBarCard(
    activeBarName: String?,
    serverUrl: String?,
    isConnected: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = activeBarName ?: if (isConnected) "Active Bar" else "Not connected",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                )
                ConnectionStatusBadge(isConnected = isConnected)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = serverUrl ?: "No Bar Assistant instance configured",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConnectionStatusBadge(isConnected: Boolean) {
    val containerColor = if (isConnected) {
        StatusReadyGreen.copy(alpha = 0.16f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (isConnected) {
        StatusReadyGreen
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val border = if (isConnected) {
        BorderStroke(1.dp, StatusReadyGreen.copy(alpha = 0.4f))
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (isConnected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = if (isConnected) "Connected" else "Offline",
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun SyncCacheCard(
    isSyncing: Boolean,
    lastSyncedEpochMillis: Long?,
    syncErrorMessage: String?,
    isActionEnabled: Boolean,
    onSyncNowClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title = "Sync & Offline Cache")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val statusText = when {
                    isSyncing -> "Syncing recipes and bar shelf inventory..."
                    lastSyncedEpochMillis != null -> "Last synced: ${formatEpochUtc(lastSyncedEpochMillis)}"
                    else -> "Local offline cache ready. Sync to fetch latest updates."
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (!syncErrorMessage.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = syncErrorMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                FilledTonalButton(
                    onClick = onSyncNowClick,
                    enabled = isActionEnabled,
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Syncing...")
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sync Now")
                    }
                }
            }
        }
    }
}

private fun formatEpochUtc(epochMillis: Long): String {
    if (epochMillis <= 0L) return "Recently"
    val totalSeconds = epochMillis / 1_000L
    val secondsInDay = 86_400L
    val daysSinceEpoch = totalSeconds / secondsInDay
    val daySeconds = totalSeconds % secondsInDay

    val z = daysSinceEpoch + 719_468L
    val era = (if (z >= 0L) z else z - 146_096L) / 146_097L
    val doe = z - era * 146_097L
    val yoe = (doe - doe / 1_460L + doe / 36_524L - doe / 146_096L) / 365L
    val y = yoe + era * 400L
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val day = doy - (153L * mp + 2L) / 5L + 1L
    val month = mp + (if (mp < 10L) 3L else -9L)
    val year = y + (if (month <= 2L) 1L else 0L)
    val hour = daySeconds / 3_600L
    val minute = (daySeconds % 3_600L) / 60L

    return "${year.toString().padStart(4, '0')}-" +
        "${month.toString().padStart(2, '0')}-" +
        "${day.toString().padStart(2, '0')} " +
        "${hour.toString().padStart(2, '0')}:" +
        "${minute.toString().padStart(2, '0')} UTC"
}
