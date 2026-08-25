package top.r2dblog.justcamera.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.r2dblog.justcamera.camera.application.CameraController
import top.r2dblog.justcamera.camera.model.CameraState
import top.r2dblog.justcamera.camera.model.CaptureStatus
import top.r2dblog.justcamera.nativecore.NativeCore

@Composable
fun CameraScreen(
    cameraController: CameraController,
    cameraPermissionGranted: Boolean,
    storagePermissionGranted: Boolean,
    requestPermissions: () -> Unit,
    openCapabilities: () -> Unit,
) {
    val state by cameraController.state.collectAsState()
    val captureStatus by cameraController.captureStatus.collectAsState()
    val cameras by cameraController.cameras.collectAsState()
    val selectedCamera by cameraController.selectedCamera.collectAsState()
    val previewSize by cameraController.previewSize.collectAsState()
    val nativeVersion = remember {
        runCatching(NativeCore::version).getOrElse { "Native core unavailable" }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (cameraPermissionGranted) {
            CameraPreview(
                cameraController = cameraController,
                previewSize = previewSize,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = stateLabel(state),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = selectedCamera?.let { "Camera ${it.cameraId} · ${it.hardwareLevel}" }
                        ?: "Discovering cameras",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 12.sp,
                )
            }
            Button(
                onClick = openCapabilities,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.16f),
                ),
            ) {
                Text("CAP", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        if (!cameraPermissionGranted || !storagePermissionGranted) {
            PermissionPanel(
                cameraMissing = !cameraPermissionGranted,
                storageMissing = !storagePermissionGranted,
                requestPermissions = requestPermissions,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.68f))
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CaptureMessage(captureStatus)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CameraActionButton(
                    text = "INFO",
                    enabled = true,
                    onClick = openCapabilities,
                )
                Button(
                    onClick = cameraController::captureJpeg,
                    enabled = state is CameraState.Previewing && storagePermissionGranted &&
                        captureStatus !is CaptureStatus.Capturing &&
                        captureStatus !is CaptureStatus.Saving,
                    shape = CircleShape,
                    contentPadding = ButtonDefaults.ContentPadding,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .size(76.dp)
                        .border(4.dp, Color.White, CircleShape),
                ) {
                    Box(
                        Modifier
                            .size(54.dp)
                            .background(Color.White, CircleShape),
                    )
                }
                CameraActionButton(
                    text = "SWITCH",
                    enabled = cameras.size > 1,
                    onClick = cameraController::switchCamera,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(nativeVersion, color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp)
        }
    }
}

@Composable
private fun PermissionPanel(
    cameraMissing: Boolean,
    storageMissing: Boolean,
    requestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(28.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Permission required", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    cameraMissing && storageMissing ->
                        "Camera access and photo storage access are required on this device."
                    cameraMissing -> "Camera access is required for preview and capture."
                    else -> "Photo storage access is required on Android 8–9."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = requestPermissions) { Text("Grant access") }
        }
    }
}

@Composable
private fun CameraActionButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.14f),
            disabledContainerColor = Color.White.copy(alpha = 0.06f),
        ),
    ) {
        Text(text, fontSize = 11.sp, color = Color.White)
    }
}

@Composable
private fun CaptureMessage(status: CaptureStatus) {
    val message = when (status) {
        CaptureStatus.Idle -> "JPEG · MediaStore"
        CaptureStatus.Capturing -> "Capturing…"
        CaptureStatus.Saving -> "Saving…"
        is CaptureStatus.Saved -> "Saved ${status.displayName}"
        is CaptureStatus.Failed -> status.error.message
    }
    val color = if (status is CaptureStatus.Failed) {
        MaterialTheme.colorScheme.error
    } else {
        Color.White.copy(alpha = 0.78f)
    }
    Text(message, color = color, fontSize = 12.sp)
}

private fun stateLabel(state: CameraState): String = when (state) {
    CameraState.PermissionRequired -> "Permission required"
    CameraState.Closed -> "Camera closed"
    is CameraState.Opening -> "Opening camera"
    is CameraState.Opened -> "Camera opened"
    is CameraState.Configuring -> "Configuring preview"
    is CameraState.Previewing -> "Ready"
    is CameraState.Capturing -> "Capturing"
    is CameraState.Error -> "Error · ${state.error.message}"
}
