package com.cross.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import android.os.Build
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cross.app.CrossViewModel
import com.cross.app.ble.CrossBleServer
import com.cross.app.health.HealthSyncBatch
import com.cross.app.health.HealthSyncStatus
import com.cross.app.media.MediaState
import com.cross.app.notifications.InstalledNotificationApp
import com.cross.app.notifications.InstalledNotificationAppDiscovery
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CrossLightColors = lightColorScheme(
    primary = Color(0xFF356859),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9EFD0),
    onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF4F6758),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1E8D7),
    onSecondaryContainer = Color(0xFF0C1F13),
    tertiary = Color(0xFF3D6372),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC1E9FA),
    onTertiaryContainer = Color(0xFF001F29),
    background = Color(0xFFF8FBF8),
    onBackground = Color(0xFF191D1A),
    surface = Color(0xFFF8FBF8),
    onSurface = Color(0xFF191D1A),
    surfaceVariant = Color(0xFFDDE8DF),
    onSurfaceVariant = Color(0xFF414943),
    outline = Color(0xFF717A73),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val CrossDarkColors = darkColorScheme(
    primary = Color(0xFF9BD0B6),
    onPrimary = Color(0xFF003827),
    primaryContainer = Color(0xFF1E513C),
    onPrimaryContainer = Color(0xFFB9EFD0),
    secondary = Color(0xFFB5CCBA),
    onSecondary = Color(0xFF203529),
    secondaryContainer = Color(0xFF364B3D),
    onSecondaryContainer = Color(0xFFD1E8D7),
    tertiary = Color(0xFFA5CDDD),
    onTertiary = Color(0xFF073542),
    tertiaryContainer = Color(0xFF244C59),
    onTertiaryContainer = Color(0xFFC1E9FA),
    background = Color(0xFF101411),
    onBackground = Color(0xFFE1E3DF),
    surface = Color(0xFF101411),
    onSurface = Color(0xFFE1E3DF),
    surfaceVariant = Color(0xFF414943),
    onSurfaceVariant = Color(0xFFC1CAC1),
    outline = Color(0xFF8B948C),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val CrossShapes = androidx.compose.material3.Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

private val CrossTypography = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(lineHeight = 24.sp),
    )
}

private val PagePadding = 20.dp
private val CardPadding = 20.dp

/** Material 3 theme tuned for quiet, high-contrast companion controls. */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun CrossTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> CrossDarkColors
        else -> CrossLightColors
    }
    MaterialTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        shapes = CrossShapes,
        typography = CrossTypography,
        content = content,
    )
}

/** Responsive overview: adaptive cards flow into columns on normal and foldable widths. */
@Composable
fun HomeScreen(windowSize: WindowSizeClass, connection: CrossBleServer.Connection, media: MediaState, health: HealthSyncBatch?, healthStatus: HealthSyncStatus, viewModel: CrossViewModel, requestPermissions: () -> Unit, openNotificationRules: () -> Unit, openSettings: () -> Unit) {
    val useRail = windowSize.widthSizeClass != WindowWidthSizeClass.Compact
    val columns = if (windowSize.widthSizeClass == WindowWidthSizeClass.Compact) 1 else 2
    val cards: @Composable () -> Unit = {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(horizontal = PagePadding, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { ConnectionCard(connection, viewModel, requestPermissions) }
            item { NotificationCard(openNotificationRules) }
            item { NowPlayingCard(media) }
            item { HealthCard(health, healthStatus) }
            item { FindDeviceCard(viewModel) }
        }
    }
    if (useRail) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxSize()) {
                AppNavigationRail(settingsSelected = false, onOverview = {}, onSettings = openSettings)
                Column(Modifier.fillMaxSize()) {
                    TopBar("Cross")
                    cards()
                }
            }
        }
    } else {
        Scaffold(
            topBar = { TopBar("Cross") },
            bottomBar = { AppNavigationBar(settingsSelected = false, onOverview = {}, onSettings = openSettings) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) { cards() }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TopBar(title: String, onBack: (() -> Unit)? = null) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
        navigationIcon = onBack?.let { callback ->
            @Composable {
                IconButton(onClick = callback) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        } ?: {},
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun AppNavigationRail(settingsSelected: Boolean, onOverview: () -> Unit, onSettings: () -> Unit) {
    NavigationRail(
        modifier = Modifier.padding(vertical = 12.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        NavigationRailItem(
            selected = !settingsSelected,
            onClick = onOverview,
            icon = { Icon(Icons.Default.Bluetooth, contentDescription = null) },
            label = { Text("Overview") },
        )
        NavigationRailItem(
            selected = settingsSelected,
            onClick = onSettings,
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text("Settings") },
        )
    }
}

@Composable
private fun AppNavigationBar(settingsSelected: Boolean, onOverview: () -> Unit, onSettings: () -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = !settingsSelected,
            onClick = onOverview,
            icon = { Icon(Icons.Default.Bluetooth, contentDescription = null) },
            label = { Text("Overview") },
        )
        NavigationBarItem(
            selected = settingsSelected,
            onClick = onSettings,
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text("Settings") },
        )
    }
}

@Composable
private fun CardHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, iconDescription: String, title: String, supporting: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.small,
        ) {
            Icon(icon, contentDescription = iconDescription, modifier = Modifier.padding(10.dp).size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun SectionHeader(title: String, supporting: String? = null) {
    Column(
        modifier = Modifier.semantics { heading() },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        supporting?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun StatusPill(text: String, containerColor: Color, contentColor: Color) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
private fun ConnectionCard(connection: CrossBleServer.Connection, viewModel: CrossViewModel, requestPermissions: () -> Unit) {
    val active = connection.status == CrossBleServer.Status.CONNECTED
    val statusColors = when (connection.status) {
        CrossBleServer.Status.CONNECTED -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        CrossBleServer.Status.ADVERTISING -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        CrossBleServer.Status.STARTING -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        CrossBleServer.Status.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        CrossBleServer.Status.STOPPED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CardHeader(Icons.Default.Bluetooth, "Bluetooth", "Apple Watch connection", "Cross companion transport")
            StatusPill(connectionLabel(connection), statusColors.first, statusColors.second)
            connection.address?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (connection.status == CrossBleServer.Status.STOPPED || connection.status == CrossBleServer.Status.ERROR) {
                    Button(onClick = requestPermissions) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start BLE")
                    }
                } else {
                    OutlinedButton(onClick = viewModel::stopBle) {
                        Icon(Icons.Default.StopCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Stop")
                    }
                }
                if (connection.status == CrossBleServer.Status.ADVERTISING) {
                    Text("Advertising Cross service", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun connectionLabel(value: CrossBleServer.Connection): String = when (value.status) {
    CrossBleServer.Status.CONNECTED -> "Connected to ${value.deviceName ?: "Apple Watch"}"
    CrossBleServer.Status.ADVERTISING -> value.detail ?: "Ready for a watch"
    CrossBleServer.Status.STARTING -> "Starting Bluetooth transport…"
    CrossBleServer.Status.ERROR -> value.detail ?: "Bluetooth needs attention"
    CrossBleServer.Status.STOPPED -> "Transport is off"
}

@Composable
private fun NotificationCard(openNotificationRules: () -> Unit) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CardHeader(Icons.Default.Notifications, "Notifications", "Notification rules", "Control forwarding to your watch")
            Text("Choose which installed apps can forward notifications and actions to your watch.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = openNotificationRules, modifier = Modifier.fillMaxWidth()) { Text("Manage notification rules") }
        }
    }
}

@Composable
private fun NowPlayingCard(media: MediaState) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardHeader(Icons.Default.MusicNote, "Now playing", "Phone media status")
            Text(media.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                media.artist.ifBlank { media.packageName ?: "No active media session" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusPill(
                text = if (media.isPlaying) "Playing on phone" else "Paused on phone",
                containerColor = if (media.isPlaying) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (media.isPlaying) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HealthCard(batch: HealthSyncBatch?, status: HealthSyncStatus) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeader(Icons.Default.HealthAndSafety, "Health", "Health Connect sync", "Exact sleep stages and workout sessions")
            val colors = healthStatusColors(status)
            StatusPill(healthStatusLabel(status), colors.first, colors.second)
            Text(healthStatusDetail(status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (batch == null) {
                Text("Waiting for Apple Watch health sessions", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("${batch.sleepSessions.size} sleep sessions  •  ${batch.workouts.size} workouts")
                val totalDurationMs = batch.workouts.sumOf { it.durationMs }
                val energySamples = batch.workouts.mapNotNull { it.activeEnergyKcal }
                val energyLabel = if (energySamples.isEmpty()) "No energy data" else "${formatCompact(energySamples.sum())} kcal"
                Text(
                    "Workout duration ${formatDuration(totalDurationMs)}  •  $energyLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (batch.deleted.isNotEmpty()) {
                    Text("${batch.deleted.size} deletions included", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "Watch generation ${formatHealthTimestamp(batch.generationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun healthStatusColors(status: HealthSyncStatus): Pair<Color, Color> = when (status) {
    HealthSyncStatus.Checking, HealthSyncStatus.Ready, is HealthSyncStatus.Syncing ->
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    is HealthSyncStatus.Synced -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    is HealthSyncStatus.PermissionRequired -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    is HealthSyncStatus.Unavailable, is HealthSyncStatus.Failed -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
}

private fun healthStatusLabel(status: HealthSyncStatus): String = when (status) {
    HealthSyncStatus.Checking -> "Checking Health Connect"
    HealthSyncStatus.Ready -> "Health Connect ready"
    is HealthSyncStatus.Unavailable -> "Health Connect unavailable"
    is HealthSyncStatus.PermissionRequired -> "Health permissions needed"
    is HealthSyncStatus.Syncing -> "Syncing health sessions"
    is HealthSyncStatus.Synced -> "Health sessions synced"
    is HealthSyncStatus.Failed -> "Health sync failed"
}

private fun healthStatusDetail(status: HealthSyncStatus): String = when (status) {
    HealthSyncStatus.Checking -> "Cross is checking provider availability and permissions."
    HealthSyncStatus.Ready -> "Health Connect can accept Apple Watch sessions."
    is HealthSyncStatus.Unavailable -> status.message
    is HealthSyncStatus.PermissionRequired -> "Grant sleep, exercise, active calories, and distance access in Settings."
    is HealthSyncStatus.Syncing -> "Writing generation ${formatHealthTimestamp(status.generationMs)}."
    is HealthSyncStatus.Synced -> "Stored ${status.sleepSessionCount} sleep and ${status.workoutCount} workout sessions; applied ${status.deletionCount} deletions."
    is HealthSyncStatus.Failed -> status.message
}

private fun formatCompact(value: Double): String = if (value % 1.0 == 0.0) "%.0f".format(value) else "%.1f".format(value)

private fun formatDuration(durationMs: Long): String {
    val minutes = durationMs / 60_000L
    return if (minutes >= 60L) "${minutes / 60L}h ${minutes % 60L}m" else "${minutes}m"
}

private fun formatHealthTimestamp(measuredAtMs: Long): String =
    DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault()).format(
        Instant.ofEpochMilli(measuredAtMs).atZone(ZoneId.systemDefault()),
    )

@Composable
private fun FindDeviceCard(viewModel: CrossViewModel) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(CardPadding)) {
            Button(onClick = viewModel::sendFindDevice, modifier = Modifier.fillMaxWidth()) { Text("Find watch") }
        }
    }
}

/** Settings route for platform permissions and special access only. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsScreen(windowSize: WindowSizeClass, viewModel: CrossViewModel, requestPermissions: () -> Unit, requestHealthConnectPermissions: () -> Unit, openHealthPrivacy: () -> Unit, openOverview: () -> Unit) {
    val useRail = windowSize.widthSizeClass != WindowWidthSizeClass.Compact
    val healthStatus by viewModel.runtime.healthConnect.status.collectAsStateWithLifecycle()
    var showReplaceWatchConfirmation by rememberSaveable { mutableStateOf(false) }
    var replacementSucceeded by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var replacementStatus by rememberSaveable { mutableStateOf<String?>(null) }
    val settingsContent: @Composable (PaddingValues) -> Unit = { padding ->
        LazyColumn(
            contentPadding = PaddingValues(horizontal = PagePadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item {
                SectionHeader("Permissions")
            }
            item {
                Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = requestPermissions, modifier = Modifier.fillMaxWidth()) {
                            Text("Request Bluetooth")
                        }
                        OutlinedButton(
                            onClick = { viewModel.openNotificationAccess(viewModel.runtime.appContext) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Notification access")
                        }
                        OutlinedButton(
                            onClick = requestHealthConnectPermissions,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = healthStatus !is HealthSyncStatus.Unavailable,
                        ) {
                            Text(if (healthStatus is HealthSyncStatus.PermissionRequired) "Grant health data access" else "Review health data access")
                        }
                        OutlinedButton(
                            onClick = openHealthPrivacy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("How Cross uses health data")
                        }
                        val colors = healthStatusColors(healthStatus)
                        StatusPill(healthStatusLabel(healthStatus), colors.first, colors.second)
                        Text(healthStatusDetail(healthStatus), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                SectionHeader(
                    "Watch pairing",
                    "Replace the trusted Apple Watch only when moving Cross to a different watch.",
                )
            }
            item {
                Card(elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Replacing the paired watch disconnects the current watch and clears its trusted identity.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = { showReplaceWatchConfirmation = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Replace paired watch")
                        }
                        replacementStatus?.let { status ->
                            val colors = if (replacementSucceeded == true) {
                                MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                            }
                            StatusPill(status, colors.first, colors.second)
                        }
                    }
                }
            }
        }
    }
    if (showReplaceWatchConfirmation) {
        AlertDialog(
            onDismissRequest = { showReplaceWatchConfirmation = false },
            title = { Text("Replace paired watch?") },
            text = {
                Text("The current watch will be disconnected and forgotten. Cross will then advertise for a replacement watch. This cannot be undone without pairing again.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val result = viewModel.replacePairedWatch()
                        replacementSucceeded = result.isSuccess
                        replacementStatus = if (result.isSuccess) {
                            "Paired watch cleared. Cross is advertising for a replacement watch."
                        } else {
                            val error = checkNotNull(result.exceptionOrNull()) { "Failed peer replacement did not contain an error" }
                            "Watch replacement failed: $error"
                        }
                        showReplaceWatchConfirmation = false
                    },
                ) {
                    Text("Replace watch")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showReplaceWatchConfirmation = false }) {
                    Text("Keep current watch")
                }
            },
        )
    }
    if (useRail) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxSize()) {
                AppNavigationRail(settingsSelected = true, onOverview = openOverview, onSettings = {})
                Column(Modifier.fillMaxSize()) {
                    TopBar("Settings")
                    settingsContent(PaddingValues())
                }
            }
        }
    } else {
        Scaffold(
            topBar = { TopBar("Settings") },
            bottomBar = { AppNavigationBar(settingsSelected = true, onOverview = openOverview, onSettings = {}) },
        ) { padding -> settingsContent(padding) }
    }
}

/** In-app Health Connect rationale used by both platform privacy intents and Settings. */
@Composable
fun HealthDataPrivacyScreen(onBack: () -> Unit) {
    Scaffold(topBar = { TopBar("Health data privacy", onBack = onBack) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(horizontal = PagePadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item {
                SectionHeader(
                    "What Cross accesses",
                    "Cross reads sleep sessions and workouts on your Apple Watch so you can copy them to this phone.",
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Cross writes only those sleep and workout sessions, plus their active energy and distance, to Health Connect on this phone.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "The watch and phone communicate directly over encrypted Bluetooth. Cross has no cloud service and does not upload this health data.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "To revoke access, open Health Connect, choose App permissions, select Cross, and turn off its health permissions. You can also remove Cross data from Health Connect.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}

/** Overview-owned destination for per-package notification forwarding rules. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun NotificationRulesScreen(viewModel: CrossViewModel, onBack: () -> Unit) {
    val rules by viewModel.runtime.rules.rules.collectAsStateWithLifecycle()
    val installedApps = remember(viewModel.runtime.appContext) {
        InstalledNotificationAppDiscovery.discover(viewModel.runtime.appContext)
    }
    val savedPackages = rules.map { it.packageName }.toSet()
    val discoveredPackages = installedApps.map { it.packageName }.toSet()
    val savedOnly = rules.filter { it.packageName !in discoveredPackages }
    Scaffold(topBar = { TopBar("Notification rules", onBack = onBack) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(horizontal = PagePadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item {
                SectionHeader(
                    "Installed apps",
                    "${installedApps.size} apps discovered. New apps are allowed by default until you block them.",
                )
            }
            items(installedApps, key = { it.packageName }) { app ->
                NotificationRuleRow(app.label, app.packageName, rules.firstOrNull { it.packageName == app.packageName }?.allowed ?: true, viewModel)
            }
            if (savedOnly.isNotEmpty()) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    SectionHeader("Saved rules for unavailable apps")
                }
                items(savedOnly, key = { it.packageName }) { rule ->
                    NotificationRuleRow(rule.packageName, rule.packageName, rule.allowed, viewModel)
                }
            }
            if (installedApps.isEmpty() && savedPackages.isEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(CardPadding), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Text("No launchable installed apps were found.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRuleRow(label: String, packageName: String, allowed: Boolean, viewModel: CrossViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = allowed,
                onCheckedChange = { viewModel.runtime.rules.setAllowed(packageName, it) },
                thumbContent = if (allowed) {
                    { Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else {
                    null
                },
            )
        }
    }
}
