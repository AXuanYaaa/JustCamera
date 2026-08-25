package top.r2dblog.justcamera.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.r2dblog.justcamera.R
import top.r2dblog.justcamera.camera.application.CameraController
import top.r2dblog.justcamera.camera.model.CameraState
import top.r2dblog.justcamera.camera.model.CaptureStatus
import top.r2dblog.justcamera.hdr.capture.HdrMode
import top.r2dblog.justcamera.hdr.capture.HdrStatus
import top.r2dblog.justcamera.ui.theme.CameraColors
import top.r2dblog.justcamera.ui.theme.CameraDimensions
import top.r2dblog.justcamera.ui.theme.CameraShapes
import top.r2dblog.justcamera.ui.theme.CameraSpacing

@Composable
fun CameraScreen(
    cameraController: CameraController,
    cameraPermissionGranted: Boolean,
    storagePermissionGranted: Boolean,
    requestPermissions: () -> Unit,
    openFilters: () -> Unit,
    openSettings: () -> Unit,
) {
    val state by cameraController.state.collectAsState()
    val captureStatus by cameraController.captureStatus.collectAsState()
    val cameras by cameraController.cameras.collectAsState()
    val selectedCamera by cameraController.selectedCamera.collectAsState()
    val previewSize by cameraController.previewSize.collectAsState()
    val controlState by cameraController.controlState.collectAsState()
    val controlCapabilities by cameraController.controlCapabilities.collectAsState()
    val controlError by cameraController.controlError.collectAsState()
    val hdrMode by cameraController.hdrMode.collectAsState()
    val hdrCapability by cameraController.hdrCapability.collectAsState()
    val hdrStatus by cameraController.hdrStatus.collectAsState()
    val captureEnabled = state is CameraState.Previewing &&
        (storagePermissionGranted || hdrMode == HdrMode.ON) &&
        captureStatus !is CaptureStatus.Capturing &&
        captureStatus !is CaptureStatus.Saving && !hdrStatus.isBusy()

    Box(modifier = Modifier.fillMaxSize().background(CameraColors.Background)) {
        if (cameraPermissionGranted) {
            CameraPreview(
                cameraController = cameraController,
                previewSize = previewSize,
                cameraFacing = selectedCamera?.facing,
                sensorOrientation = selectedCamera?.sensorOrientation,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(144.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = CameraSpacing.Large, vertical = CameraSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            HdrControl(
                mode = hdrMode,
                enabled = hdrMode == HdrMode.ON || hdrCapability?.captureEnabled == true,
                onClick = {
                    cameraController.setHdrMode(
                        if (hdrMode == HdrMode.ON) HdrMode.OFF else HdrMode.ON,
                    )
                },
            )
            Surface(
                color = CameraColors.ControlSurface,
                shape = CameraShapes.Control,
            ) {
                Text(
                    stateLabel(state),
                    color = CameraColors.PrimaryContent,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CameraSpacing.Small)) {
                CameraIconButton(
                    icon = R.drawable.ic_filter,
                    description = stringResource(R.string.open_filters),
                    onClick = openFilters,
                )
                CameraIconButton(
                    icon = R.drawable.ic_settings,
                    description = stringResource(R.string.open_settings),
                    onClick = openSettings,
                )
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

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(260.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f)),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    start = CameraSpacing.Large,
                    end = CameraSpacing.Large,
                    bottom = CameraSpacing.Large,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ProControlPanel(
                state = controlState,
                capabilities = controlCapabilities,
                controlError = controlError,
                onStateChange = cameraController::updateControls,
            )
            Spacer(Modifier.height(CameraSpacing.Medium))
            if (hdrMode == HdrMode.ON || hdrStatus !is HdrStatus.Idle) {
                HdrCaptureMessage(hdrStatus)
            } else {
                CaptureMessage(captureStatus)
            }
            Spacer(Modifier.height(CameraSpacing.Medium))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Spacer(Modifier.size(CameraDimensions.TouchTarget))
                ShutterButton(
                    enabled = captureEnabled,
                    description = stringResource(R.string.take_photo),
                    onClick = cameraController::capture,
                )
                CameraIconButton(
                    icon = R.drawable.ic_switch_camera,
                    description = stringResource(R.string.switch_camera),
                    enabled = cameras.size > 1,
                    onClick = cameraController::switchCamera,
                )
            }
        }
    }
}

@Composable
private fun HdrControl(mode: HdrMode, enabled: Boolean, onClick: () -> Unit) {
    val selected = mode == HdrMode.ON
    Surface(
        color = if (selected) CameraColors.ControlSurfaceSelected else CameraColors.ControlSurface,
        shape = CameraShapes.Control,
        modifier = Modifier
            .height(CameraDimensions.TouchTarget)
            .alpha(if (enabled) 1f else 0.52f)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.hdr),
                color = if (selected) Color.Black else Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
            )
            Text(
                stringResource(if (selected) R.string.on else R.string.off),
                color = if (selected) Color.Black.copy(alpha = 0.72f) else {
                    Color.White.copy(alpha = 0.72f)
                },
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
private fun CameraIconButton(
    icon: Int,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        color = CameraColors.ControlSurface,
        shape = CircleShape,
        modifier = Modifier
            .size(CameraDimensions.TouchTarget)
            .alpha(if (enabled) 1f else 0.38f),
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                painter = painterResource(icon),
                contentDescription = description,
                tint = CameraColors.PrimaryContent,
                modifier = Modifier.size(CameraDimensions.Icon),
            )
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(CameraDimensions.Shutter)
            .semantics { contentDescription = description }
            .border(4.dp, Color.White.copy(alpha = if (enabled) 1f else 0.45f), CircleShape),
    ) {
        Box(
            Modifier
                .size(CameraDimensions.ShutterInner)
                .background(Color.White.copy(alpha = if (enabled) 1f else 0.38f), CircleShape),
        )
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
        shape = CameraShapes.Panel,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.permission_required), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    when {
                        cameraMissing && storageMissing -> R.string.permission_camera_storage
                        cameraMissing -> R.string.permission_camera
                        else -> R.string.permission_storage
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = requestPermissions) { Text(stringResource(R.string.grant_access)) }
        }
    }
}

@Composable
private fun CaptureMessage(status: CaptureStatus) {
    val message = stringResource(
        when (status) {
            CaptureStatus.Idle -> R.string.capture_ready
            is CaptureStatus.Capturing -> R.string.capture_capturing
            is CaptureStatus.Saving -> R.string.capture_saving
            is CaptureStatus.Saved -> R.string.capture_saved
            is CaptureStatus.PartialSuccess -> R.string.capture_partial
            is CaptureStatus.Failed -> R.string.capture_failed
        },
    )
    Text(
        message,
        color = if (status is CaptureStatus.Failed || status is CaptureStatus.PartialSuccess) {
            MaterialTheme.colorScheme.error
        } else CameraColors.SecondaryContent,
        fontSize = 12.sp,
    )
}

@Composable
private fun HdrCaptureMessage(status: HdrStatus) {
    val message = stringResource(
        when (status) {
            HdrStatus.Idle -> R.string.hdr_on
            HdrStatus.Planning, is HdrStatus.Capturing -> R.string.hdr_capturing
            is HdrStatus.Converting, HdrStatus.Aligning, HdrStatus.Merging,
            HdrStatus.ToneMapping,
            -> R.string.hdr_processing
            is HdrStatus.Completed -> R.string.hdr_completed
            is HdrStatus.Failed -> R.string.hdr_failed
            HdrStatus.Cancelled -> R.string.hdr_cancelled
        },
    )
    Text(
        message,
        color = if (status is HdrStatus.Failed) MaterialTheme.colorScheme.error else {
            CameraColors.SecondaryContent
        },
        fontSize = 12.sp,
    )
}

private fun HdrStatus.isBusy(): Boolean = when (this) {
    HdrStatus.Planning,
    is HdrStatus.Capturing,
    is HdrStatus.Converting,
    HdrStatus.Aligning,
    HdrStatus.Merging,
    HdrStatus.ToneMapping,
    -> true
    else -> false
}

@Composable
private fun stateLabel(state: CameraState): String = stringResource(
    when (state) {
        CameraState.PermissionRequired -> R.string.camera_status_permission
        CameraState.Closed -> R.string.camera_status_closed
        is CameraState.Opening, is CameraState.Opened -> R.string.camera_status_opening
        is CameraState.Configuring -> R.string.camera_status_configuring
        is CameraState.Previewing -> R.string.camera_status_ready
        is CameraState.Capturing -> R.string.camera_status_capturing
        is CameraState.Error -> R.string.camera_status_error
    },
)
