package com.example.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.data.VideoJobEntity
import com.example.ui.components.VideoPreviewDialog
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.QueueScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceHighlight
import com.example.ui.theme.StudioVoid
import com.example.ui.theme.TextSecondary
import kotlinx.serialization.Serializable

@Serializable object StudioRoute

@Serializable object QueueRoute

@Serializable object HistoryRoute

@Serializable object SettingsRoute

private data class NavItemSpec<T : Any>(
  val route: T,
  val labelRes: Int,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
  val testTag: String,
)

@Composable
fun CompressXApp(
  widthSizeClass: WindowWidthSizeClass,
  viewModel: CompressXViewModel = viewModel(),
) {
  val context = LocalContext.current
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val navController = rememberNavController()
  val snackbarHostState = remember { SnackbarHostState() }
  var previewJob by remember { mutableStateOf<VideoJobEntity?>(null) }

  // Android Photo Picker (PickVisualMedia.VideoOnly): multi-video selection without broad storage permissions
  val pickMediaVideosLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
      if (uris.isNotEmpty()) {
        viewModel.onVideosSelected(uris)
      }
    }

  // Storage Access Framework: Multi-video file browser for external/custom folders
  val pickSafVideosLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
      if (uris.isNotEmpty()) {
        viewModel.onVideosSelected(uris)
      }
    }

  val launchSafPicker = {
    try {
      pickSafVideosLauncher.launch(arrayOf("video/*"))
    } catch (t: Throwable) {
      com.example.engine.CompressionLogger.e("CompressXUI", "Failed launching SAF video picker", t)
      viewModel.reportMessage("No video file picker available on this device.")
    }
  }

  val launchPhotoPicker = {
    try {
      pickMediaVideosLauncher.launch(
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
      )
    } catch (t: Throwable) {
      com.example.engine.CompressionLogger.w("CompressXUI", "Photo Picker unavailable; falling back to SAF", t)
      launchSafPicker()
    }
  }

  // Storage Access Framework: Output directory picker
  val pickOutputFolderLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
      viewModel.onOutputFolderSelected(treeUri)
    }

  val launchOutputFolderPicker = {
    try {
      pickOutputFolderLauncher.launch(null)
    } catch (t: Throwable) {
      com.example.engine.CompressionLogger.e("CompressXUI", "Failed launching SAF folder picker", t)
      viewModel.reportMessage("Folder picker unavailable on this device.")
    }
  }

  // Android 13+ runtime notification permission (only requested when starting batch)
  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
      viewModel.startOrResumeBatch()
    }

  val startBatchWithNotificationCheck = {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val granted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED
      if (granted) {
        viewModel.startOrResumeBatch()
      } else {
        try {
          notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (t: Throwable) {
          com.example.engine.CompressionLogger.w("CompressXUI", "Notification permission launcher failed; continuing", t)
          viewModel.startOrResumeBatch()
        }
      }
    } else {
      viewModel.startOrResumeBatch()
    }
  }

  val shareOutputVideo: (VideoJobEntity) -> Unit = { job ->
    val uriStr = job.outputUri
    if (!uriStr.isNullOrBlank()) {
      try {
        val shareIntent =
          Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, Uri.parse(uriStr))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          }
        context.startActivity(Intent.createChooser(shareIntent, "Share Compressed Video"))
      } catch (t: Throwable) {
        com.example.engine.CompressionLogger.e("CompressXUI", "Failed sharing output video", t)
        viewModel.reportMessage("Unable to share video: ${com.example.engine.CompressionLogger.formatUserErrorMessage(t)}")
      }
    }
  }

  LaunchedEffect(uiState.statusBannerMessage) {
    val msg = uiState.statusBannerMessage
    if (!msg.isNullOrBlank()) {
      snackbarHostState.showSnackbar(msg)
      viewModel.dismissBanner()
    }
  }

  val navItems =
    listOf(
      NavItemSpec(
        route = StudioRoute,
        labelRes = R.string.nav_studio,
        selectedIcon = Icons.Filled.Dashboard,
        unselectedIcon = Icons.Outlined.Dashboard,
        testTag = "nav_studio",
      ),
      NavItemSpec(
        route = QueueRoute,
        labelRes = R.string.nav_queue,
        selectedIcon = Icons.AutoMirrored.Filled.QueueMusic,
        unselectedIcon = Icons.AutoMirrored.Outlined.QueueMusic,
        testTag = "nav_queue",
      ),
      NavItemSpec(
        route = HistoryRoute,
        labelRes = R.string.nav_history,
        selectedIcon = Icons.Filled.History,
        unselectedIcon = Icons.Outlined.History,
        testTag = "nav_history",
      ),
      NavItemSpec(
        route = SettingsRoute,
        labelRes = R.string.nav_settings,
        selectedIcon = Icons.Filled.Tune,
        unselectedIcon = Icons.Outlined.Tune,
        testTag = "nav_settings",
      ),
    )

  val backStackEntry by navController.currentBackStackEntryAsState()
  val currentDestination = backStackEntry?.destination
  val useNavRail = widthSizeClass != WindowWidthSizeClass.Compact

  val navigateTopLevel: (Any) -> Unit = { targetRoute ->
    navController.navigate(targetRoute) {
      popUpTo(navController.graph.findStartDestination().id) { saveState = true }
      launchSingleTop = true
      restoreState = true
    }
  }

  Scaffold(
    containerColor = StudioVoid,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    bottomBar = {
      if (!useNavRail) {
        NavigationBar(containerColor = StudioSurface) {
          navItems.forEach { item ->
            val selected = currentDestination?.hasRoute(item.route::class) == true
            val label = stringResource(item.labelRes)
            NavigationBarItem(
              selected = selected,
              onClick = { navigateTopLevel(item.route) },
              icon = {
                Icon(
                  imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                  contentDescription = label,
                )
              },
              label = { Text(label) },
              colors =
                NavigationBarItemDefaults.colors(
                  selectedIconColor = CyanPrimary,
                  selectedTextColor = CyanPrimary,
                  indicatorColor = StudioSurfaceHighlight,
                  unselectedIconColor = TextSecondary,
                  unselectedTextColor = TextSecondary,
                ),
              modifier = Modifier.testTag(item.testTag),
            )
          }
        }
      }
    },
  ) { innerPadding ->
    Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      if (useNavRail) {
        NavigationRail(containerColor = StudioSurface) {
          navItems.forEach { item ->
            val selected = currentDestination?.hasRoute(item.route::class) == true
            val label = stringResource(item.labelRes)
            NavigationRailItem(
              selected = selected,
              onClick = { navigateTopLevel(item.route) },
              icon = {
                Icon(
                  imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                  contentDescription = label,
                )
              },
              label = { Text(label) },
              colors =
                NavigationRailItemDefaults.colors(
                  selectedIconColor = CyanPrimary,
                  selectedTextColor = CyanPrimary,
                  indicatorColor = StudioSurfaceHighlight,
                  unselectedIconColor = TextSecondary,
                  unselectedTextColor = TextSecondary,
                ),
              modifier = Modifier.testTag(item.testTag),
            )
          }
        }
      }

      Box(modifier = Modifier.weight(1f).fillMaxSize()) {
        NavHost(navController = navController, startDestination = StudioRoute) {
          composable<StudioRoute> {
            DashboardScreen(
              uiState = uiState,
              onPickVideos = launchPhotoPicker,
              onPickSafVideos = launchSafPicker,
              onPickOutputFolder = launchOutputFolderPicker,
              onStartOrResume = startBatchWithNotificationCheck,
              onPause = { viewModel.pauseBatch() },
              onRetryFailed = { viewModel.retryAllFailed() },
              onSelectPreset = { viewModel.selectPreset(it) },
              onNavigateToQueue = { filter ->
                viewModel.selectFilter(filter)
                navigateTopLevel(QueueRoute)
              },
              onNavigateToSettings = { navigateTopLevel(SettingsRoute) },
            )
          }

          composable<QueueRoute> {
            QueueScreen(
              uiState = uiState,
              onSelectFilter = { viewModel.selectFilter(it) },
              onAddVideos = launchPhotoPicker,
              onAddSafVideos = launchSafPicker,
              onStartOrResume = startBatchWithNotificationCheck,
              onPause = { viewModel.pauseBatch() },
              onMoveJob = { from, to, visible -> viewModel.moveJob(from, to, visible) },
              onCancelJob = { viewModel.cancelJob(it) },
              onRetryJob = { viewModel.retryJob(it) },
              onRetryAllFailed = { viewModel.retryAllFailed() },
              onDeleteJob = { viewModel.deleteJob(it) },
              onClearCompleted = { viewModel.clearCompleted() },
              onClearAll = { viewModel.clearAllQueue() },
              onPreviewOutput = { previewJob = it },
              onShareOutput = shareOutputVideo,
            )
          }

          composable<HistoryRoute> {
            HistoryScreen(
              uiState = uiState,
              onClearHistory = { viewModel.clearCompleted() },
              onDeleteJob = { viewModel.deleteJob(it) },
              onPreviewOutput = { previewJob = it },
              onShareOutput = shareOutputVideo,
            )
          }

          composable<SettingsRoute> {
            SettingsScreen(
              uiState = uiState,
              onSelectPreset = { viewModel.selectPreset(it) },
              onSelectResolution = { viewModel.selectResolution(it) },
              onSelectCodec = { viewModel.selectCodec(it) },
              onChangeBitrateKbps = { viewModel.updateCustomBitrateKbps(it) },
              onPickOutputFolder = launchOutputFolderPicker,
              onResetOutputFolder = { viewModel.resetOutputFolderToDefault() },
              onTogglePreferHardware = { viewModel.setPreferHardware(it) },
              onToggleThermalProtection = { viewModel.setThermalProtection(it) },
              onTogglePauseLowBattery = { viewModel.setPauseOnLowBattery(it) },
              onToggleAutoResume = { viewModel.setAutoResumeAfterRestart(it) },
            )
          }
        }
      }
    }
  }

  val jobToPreview = previewJob
  if (jobToPreview != null) {
    VideoPreviewDialog(job = jobToPreview, onDismiss = { previewJob = null })
  }
}
