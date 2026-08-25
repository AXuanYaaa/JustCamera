package top.r2dblog.justcamera.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import top.r2dblog.justcamera.camera.application.CameraController
import top.r2dblog.justcamera.ui.camera.CameraScreen
import top.r2dblog.justcamera.ui.capability.CapabilityScreen
import top.r2dblog.justcamera.ui.filter.FilterScreen
import top.r2dblog.justcamera.settings.AppLanguage
import top.r2dblog.justcamera.ui.settings.SettingsScreen

private enum class AppDestination { CAMERA, CAPABILITIES, FILTERS, SETTINGS }

@Composable
fun JustCameraApp(
    cameraController: CameraController,
    cameraPermissionGranted: Boolean,
    storagePermissionGranted: Boolean,
    requestPermissions: () -> Unit,
    selectedLanguage: AppLanguage,
    selectLanguage: (AppLanguage) -> Unit,
) {
    var destination by remember { mutableStateOf(AppDestination.CAMERA) }
    when (destination) {
        AppDestination.CAMERA -> CameraScreen(
            cameraController = cameraController,
            cameraPermissionGranted = cameraPermissionGranted,
            storagePermissionGranted = storagePermissionGranted,
            requestPermissions = requestPermissions,
            openFilters = { destination = AppDestination.FILTERS },
            openSettings = { destination = AppDestination.SETTINGS },
        )
        AppDestination.CAPABILITIES -> CapabilityScreen(
            cameraController = cameraController,
            onBack = { destination = AppDestination.SETTINGS },
        )
        AppDestination.FILTERS -> FilterScreen(onBack = { destination = AppDestination.CAMERA })
        AppDestination.SETTINGS -> SettingsScreen(
            selectedLanguage = selectedLanguage,
            onLanguageSelected = selectLanguage,
            onOpenDeveloperInfo = { destination = AppDestination.CAPABILITIES },
            onBack = { destination = AppDestination.CAMERA },
        )
    }
}
