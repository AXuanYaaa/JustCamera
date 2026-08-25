package top.r2dblog.justcamera.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import top.r2dblog.justcamera.camera.application.CameraController
import top.r2dblog.justcamera.ui.camera.CameraScreen
import top.r2dblog.justcamera.ui.capability.CapabilityScreen

private enum class AppDestination { CAMERA, CAPABILITIES }

@Composable
fun JustCameraApp(
    cameraController: CameraController,
    cameraPermissionGranted: Boolean,
    storagePermissionGranted: Boolean,
    requestPermissions: () -> Unit,
) {
    var destination by remember { mutableStateOf(AppDestination.CAMERA) }
    when (destination) {
        AppDestination.CAMERA -> CameraScreen(
            cameraController = cameraController,
            cameraPermissionGranted = cameraPermissionGranted,
            storagePermissionGranted = storagePermissionGranted,
            requestPermissions = requestPermissions,
            openCapabilities = { destination = AppDestination.CAPABILITIES },
        )
        AppDestination.CAPABILITIES -> CapabilityScreen(
            cameraController = cameraController,
            onBack = { destination = AppDestination.CAMERA },
        )
    }
}
