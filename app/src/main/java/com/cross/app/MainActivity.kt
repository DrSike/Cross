package com.cross.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import com.cross.app.ui.CrossTheme
import com.cross.app.ui.HomeScreen
import com.cross.app.ui.HealthDataPrivacyScreen
import com.cross.app.ui.NotificationRulesScreen
import com.cross.app.ui.SettingsScreen
import com.cross.app.health.HealthPermissionRationale

/** Main entry point. Compose window size classes allow the same navigation to become a rail on foldables/tablets. */
class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<CrossViewModel> {
        val runtime = (application as CrossApplication).runtime
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CrossViewModel(runtime) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CrossRoot(viewModel, HealthPermissionRationale.handles(intent?.action)) }
    }

    override fun onResume() {
        super.onResume()
        viewModel.runtime.syncPendingHealthToHealthConnect()
    }
}

/** Navigation and runtime permission shell. Platform settings screens remain the source of truth for special access. */
@Composable
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
private fun CrossRoot(viewModel: CrossViewModel, showHealthPrivacyOnLaunch: Boolean) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val navController = rememberNavController()
    val windowSize = calculateWindowSizeClass(activity)
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.refreshPermissions(); viewModel.startBle() }
    val healthPermissions = rememberLauncherForActivityResult(viewModel.runtime.healthConnect.permissionRequestContract()) {
        viewModel.runtime.syncPendingHealthToHealthConnect()
    }
    val requestPermissions: () -> Unit = {
        val requested = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (requested.isNotEmpty()) permissions.launch(requested.toTypedArray()) else { viewModel.refreshPermissions(); viewModel.startBle() }
    }
    val requestHealthConnectPermissions: () -> Unit = {
        if (viewModel.runtime.healthConnect.isAvailable) {
            healthPermissions.launch(com.cross.app.health.HealthConnectRepository.WRITE_PERMISSIONS)
        } else {
            viewModel.runtime.syncPendingHealthToHealthConnect()
        }
    }
    val connection by viewModel.runtime.ble.connection.collectAsStateWithLifecycle()
    val media by viewModel.runtime.media.state.collectAsStateWithLifecycle()
    val health by viewModel.runtime.health.latest.collectAsStateWithLifecycle()
    val healthStatus by viewModel.runtime.healthConnect.status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshPermissions(); viewModel.runtime.media.refresh() }

    fun openTopLevel(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    CrossTheme {
        NavHost(
            navController = navController,
            startDestination = if (showHealthPrivacyOnLaunch) "health_privacy" else "home",
        ) {
            composable("home") {
                HomeScreen(windowSize, connection, media, health, healthStatus, viewModel, requestPermissions, openNotificationRules = { navController.navigate("notification_rules") }, openSettings = { openTopLevel("settings") })
            }
            composable("notification_rules") { NotificationRulesScreen(viewModel, onBack = { navController.popBackStack() }) }
            composable("settings") {
                SettingsScreen(
                    windowSize,
                    viewModel,
                    requestPermissions,
                    requestHealthConnectPermissions,
                    openHealthPrivacy = { navController.navigate("health_privacy") },
                    openOverview = { openTopLevel("home") },
                )
            }
            composable("health_privacy") {
                HealthDataPrivacyScreen(onBack = {
                    if (!navController.popBackStack()) activity.finish()
                })
            }
        }
    }
}

/** View-model façade keeping Compose unaware of Android service details. */
class CrossViewModel(val runtime: CrossRuntime) : ViewModel() {
    var bluetoothPermissionGranted = false
        private set
    var notificationAccessGranted = false
        private set

    fun refreshPermissions() {
        bluetoothPermissionGranted = Build.VERSION.SDK_INT < 31 || (
            runtime.appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                runtime.appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            )
        notificationAccessGranted = android.provider.Settings.Secure.getString(runtime.appContext.contentResolver, "enabled_notification_listeners")
            ?.contains(runtime.appContext.packageName) == true
    }

    fun startBle() { runtime.startBleService() }
    fun stopBle() { runtime.stopBleService() }
    fun replacePairedWatch(): Result<Unit> = runtime.replacePairedWatch()
    fun volumeDown() = runtime.media.adjustVolume(android.media.AudioManager.ADJUST_LOWER)
    fun volumeUp() = runtime.media.adjustVolume(android.media.AudioManager.ADJUST_RAISE)
    fun openNotificationAccess(context: android.content.Context) {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun sendFindDevice() = runtime.ble.send(com.cross.app.ble.ProtocolMessage(com.cross.app.ble.MessageType.FIND_DEVICE, payloadJson = com.cross.app.ble.ProtocolMessage.payload("target" to "watch")))
}
