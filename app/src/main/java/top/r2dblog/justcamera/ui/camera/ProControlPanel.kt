package top.r2dblog.justcamera.ui.camera

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.r2dblog.justcamera.camera.control.CameraControlState
import top.r2dblog.justcamera.camera.control.ExposureCompensation
import top.r2dblog.justcamera.camera.control.ExposureMode
import top.r2dblog.justcamera.camera.control.ShutterSpeedFormatter
import top.r2dblog.justcamera.camera.model.CameraCaptureMetadata
import top.r2dblog.justcamera.camera.model.CameraControlCapabilities
import top.r2dblog.justcamera.camera.model.CameraError
import top.r2dblog.justcamera.camera.model.CaptureMode
import top.r2dblog.justcamera.camera.model.FocusMode
import top.r2dblog.justcamera.camera.model.WhiteBalanceMode
import java.util.Locale

private enum class ControlPanel { ISO, SHUTTER, EV, FOCUS, WB, ZOOM }

@Composable
fun ProControlPanel(
    state: CameraControlState,
    capabilities: CameraControlCapabilities,
    metadata: CameraCaptureMetadata,
    controlError: CameraError?,
    onStateChange: (CameraControlState) -> Unit,
) {
    var selectedPanel by remember { mutableStateOf<ControlPanel?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = metadataLabel(state, metadata),
            color = Color.White.copy(alpha = 0.76f),
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        controlError?.let {
            Text(
                text = it.message,
                color = Color(0xFFFFB4AB),
                fontSize = 10.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (capabilities.manualSensor) {
                ProButton("ISO", isoLabel(state), selectedPanel == ControlPanel.ISO) {
                    selectedPanel = toggle(selectedPanel, ControlPanel.ISO)
                }
                ProButton("S", shutterLabel(state), selectedPanel == ControlPanel.SHUTTER) {
                    selectedPanel = toggle(selectedPanel, ControlPanel.SHUTTER)
                }
            }
            if (capabilities.hasUsefulExposureCompensation()) {
                ProButton("EV", ExposureCompensation.label(
                    state.exposureCompensationSteps,
                    capabilities.exposureCompensationStep!!,
                ), selectedPanel == ControlPanel.EV) {
                    selectedPanel = toggle(selectedPanel, ControlPanel.EV)
                }
            }
            if (capabilities.focusModes.isNotEmpty()) {
                ProButton("FOCUS", focusLabel(state), selectedPanel == ControlPanel.FOCUS) {
                    selectedPanel = toggle(selectedPanel, ControlPanel.FOCUS)
                }
            }
            if (capabilities.whiteBalanceModes.isNotEmpty()) {
                ProButton("WB", state.whiteBalanceMode.label, selectedPanel == ControlPanel.WB) {
                    selectedPanel = toggle(selectedPanel, ControlPanel.WB)
                }
            }
            val zoomRange = capabilities.effectiveZoomRange
            if (zoomRange.upper > zoomRange.lower) {
                ProButton("ZOOM", String.format(Locale.US, "%.1f×", state.zoomRatio),
                    selectedPanel == ControlPanel.ZOOM) {
                    selectedPanel = toggle(selectedPanel, ControlPanel.ZOOM)
                }
            }
            if (capabilities.aeLockAvailable && state.exposureMode == ExposureMode.AUTO) {
                ProButton("AE-L", if (state.aeLocked) "ON" else "OFF", state.aeLocked) {
                    onStateChange(state.copy(aeLocked = !state.aeLocked))
                }
            }
            if (FocusMode.AUTO in capabilities.focusModes) {
                ProButton("AF-L", if (state.afLockRequested) "ON" else "OFF",
                    state.afLockRequested) {
                    onStateChange(state.copy(afLockRequested = !state.afLockRequested))
                }
            }
            if (capabilities.awbLockAvailable &&
                state.whiteBalanceMode == WhiteBalanceMode.AUTO
            ) {
                ProButton("AWB-L", if (state.awbLocked) "ON" else "OFF", state.awbLocked) {
                    onStateChange(state.copy(awbLocked = !state.awbLocked))
                }
            }
            ProButton("FORMAT", captureModeLabel(state.captureMode), false) {
                onStateChange(
                    state.copy(captureMode = nextCaptureMode(state.captureMode, capabilities)),
                )
            }
        }

        when (selectedPanel) {
            ControlPanel.ISO -> IsoPanel(state, capabilities, onStateChange)
            ControlPanel.SHUTTER -> ShutterPanel(state, capabilities, onStateChange)
            ControlPanel.EV -> EvPanel(state, capabilities, onStateChange)
            ControlPanel.FOCUS -> FocusPanel(state, capabilities, onStateChange)
            ControlPanel.WB -> WhiteBalancePanel(state, capabilities, onStateChange)
            ControlPanel.ZOOM -> ZoomPanel(state, capabilities, onStateChange)
            null -> Unit
        }
    }
}

@Composable
private fun IsoPanel(
    state: CameraControlState,
    capabilities: CameraControlCapabilities,
    onStateChange: (CameraControlState) -> Unit,
) {
    val range = capabilities.sensitivityRange ?: return
    val manual = state.manualExposure(capabilities)
    PanelRow("ISO ${manual.iso} · range ${range.lower}–${range.upper}") {
        FilterChip(
            selected = state.exposureMode == ExposureMode.AUTO,
            onClick = { onStateChange(state.copy(exposureMode = ExposureMode.AUTO)) },
            label = { Text("AUTO") },
        )
        Slider(
            value = manual.iso!!.toFloat(),
            onValueChange = {
                onStateChange(manual.copy(iso = it.toInt(), exposureMode = ExposureMode.MANUAL))
            },
            valueRange = range.lower.toFloat()..range.upper.toFloat(),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ShutterPanel(
    state: CameraControlState,
    capabilities: CameraControlCapabilities,
    onStateChange: (CameraControlState) -> Unit,
) {
    val range = capabilities.exposureTimeRangeNanos ?: return
    val manual = state.manualExposure(capabilities)
    PanelRow(
        "Shutter ${ShutterSpeedFormatter.format(manual.exposureTimeNs!!)} · " +
            "${ShutterSpeedFormatter.format(range.lower)}–${ShutterSpeedFormatter.format(range.upper)}",
    ) {
        FilterChip(
            selected = state.exposureMode == ExposureMode.AUTO,
            onClick = { onStateChange(state.copy(exposureMode = ExposureMode.AUTO)) },
            label = { Text("AUTO") },
        )
        Slider(
            value = ShutterSpeedFormatter.toSlider(manual.exposureTimeNs, range),
            onValueChange = {
                onStateChange(
                    manual.copy(
                        exposureMode = ExposureMode.MANUAL,
                        exposureTimeNs = ShutterSpeedFormatter.fromSlider(it, range),
                    ),
                )
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EvPanel(
    state: CameraControlState,
    capabilities: CameraControlCapabilities,
    onStateChange: (CameraControlState) -> Unit,
) {
    val range = capabilities.exposureCompensationRange ?: return
    val step = capabilities.exposureCompensationStep ?: return
    PanelRow(ExposureCompensation.label(state.exposureCompensationSteps, step)) {
        Slider(
            value = state.exposureCompensationSteps.toFloat(),
            onValueChange = {
                onStateChange(state.copy(exposureCompensationSteps = it.toInt()))
            },
            valueRange = range.lower.toFloat()..range.upper.toFloat(),
            steps = (range.upper - range.lower - 1).coerceAtLeast(0),
            enabled = state.exposureMode == ExposureMode.AUTO,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FocusPanel(
    state: CameraControlState,
    capabilities: CameraControlCapabilities,
    onStateChange: (CameraControlState) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            capabilities.focusModes.forEach { mode ->
                FilterChip(
                    selected = state.focusMode == mode,
                    onClick = {
                        onStateChange(
                            state.copy(
                                focusMode = mode,
                                focusDistanceDiopters = if (mode == FocusMode.MANUAL) {
                                    state.focusDistanceDiopters ?: 0f
                                } else {
                                    null
                                },
                                afLockRequested = false,
                            ),
                        )
                    },
                    label = { Text(mode.label) },
                )
            }
        }
        if (state.focusMode == FocusMode.MANUAL && capabilities.manualFocusAvailable) {
            val maximum = capabilities.minimumFocusDistanceDiopters ?: return@Column
            val distance = state.focusDistanceDiopters ?: 0f
            Text(focusDistanceLabel(distance), color = Color.White, fontSize = 11.sp)
            Slider(
                value = distance,
                onValueChange = { onStateChange(state.copy(focusDistanceDiopters = it)) },
                valueRange = 0f..maximum,
            )
        }
    }
}

@Composable
private fun WhiteBalancePanel(
    state: CameraControlState,
    capabilities: CameraControlCapabilities,
    onStateChange: (CameraControlState) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        capabilities.whiteBalanceModes.forEach { mode ->
            FilterChip(
                selected = state.whiteBalanceMode == mode,
                onClick = { onStateChange(state.copy(whiteBalanceMode = mode)) },
                label = { Text(mode.label) },
            )
        }
    }
}

@Composable
private fun ZoomPanel(
    state: CameraControlState,
    capabilities: CameraControlCapabilities,
    onStateChange: (CameraControlState) -> Unit,
) {
    val range = capabilities.effectiveZoomRange
    PanelRow(String.format(Locale.US, "Zoom %.2f× · %.2f–%.2f×", state.zoomRatio,
        range.lower, range.upper)) {
        Slider(
            value = state.zoomRatio,
            onValueChange = { onStateChange(state.copy(zoomRatio = it)) },
            valueRange = range.lower..range.upper,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PanelRow(label: String, content: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(label, color = Color.White, fontSize = 11.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun ProButton(label: String, value: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFF8DBBFF) else Color.White.copy(alpha = 0.13f),
        ),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Column {
            Text(label, fontSize = 9.sp, color = if (selected) Color.Black else Color.White)
            Text(value, fontSize = 11.sp, color = if (selected) Color.Black else Color.White)
        }
    }
}

private fun CameraControlState.manualExposure(
    capabilities: CameraControlCapabilities,
): CameraControlState {
    val isoRange = capabilities.sensitivityRange
    val exposureRange = capabilities.exposureTimeRangeNanos
    return copy(
        exposureMode = ExposureMode.MANUAL,
        aeLocked = false,
        iso = iso ?: isoRange?.let { (it.lower + it.upper) / 2 },
        exposureTimeNs = exposureTimeNs ?: exposureRange?.let {
            ShutterSpeedFormatter.fractionToNanos(1, 60).coerceIn(it.lower, it.upper)
        },
    )
}

private fun toggle(current: ControlPanel?, requested: ControlPanel): ControlPanel? =
    if (current == requested) null else requested

private fun isoLabel(state: CameraControlState) =
    if (state.exposureMode == ExposureMode.AUTO) "AUTO" else state.iso?.toString() ?: "—"

private fun shutterLabel(state: CameraControlState) =
    if (state.exposureMode == ExposureMode.AUTO) "AUTO" else
        state.exposureTimeNs?.let(ShutterSpeedFormatter::format) ?: "—"

private fun focusLabel(state: CameraControlState) = if (state.focusMode == FocusMode.MANUAL) {
    focusDistanceLabel(state.focusDistanceDiopters ?: 0f)
} else {
    state.focusMode.label
}

private fun focusDistanceLabel(diopters: Float): String = when {
    diopters <= 0.0001f -> "∞"
    else -> String.format(Locale.US, "%.2f m", 1f / diopters)
}

private fun captureModeLabel(mode: CaptureMode): String = when (mode) {
    CaptureMode.JPEG_ONLY -> "JPEG"
    CaptureMode.RAW_ONLY -> "RAW"
    CaptureMode.JPEG_AND_RAW -> "J+R"
}

private fun nextCaptureMode(
    current: CaptureMode,
    capabilities: CameraControlCapabilities,
): CaptureMode = if (!capabilities.rawAvailable) {
    CaptureMode.JPEG_ONLY
} else {
    when (current) {
        CaptureMode.JPEG_ONLY -> CaptureMode.RAW_ONLY
        CaptureMode.RAW_ONLY -> CaptureMode.JPEG_AND_RAW
        CaptureMode.JPEG_AND_RAW -> CaptureMode.JPEG_ONLY
    }
}

private fun CameraControlCapabilities.hasUsefulExposureCompensation(): Boolean =
    exposureCompensationRange?.let { it.upper > it.lower } == true &&
        exposureCompensationStep?.numerator != 0

private fun metadataLabel(
    requested: CameraControlState,
    observed: CameraCaptureMetadata,
): String {
    val requestedExposure = if (requested.exposureMode == ExposureMode.MANUAL) {
        "requested ISO ${requested.iso} · ${requested.exposureTimeNs?.let(
            ShutterSpeedFormatter::format,
        )}"
    } else {
        "requested AUTO"
    }
    val observedExposure = listOfNotNull(
        observed.sensitivityIso?.let { "ISO $it" },
        observed.exposureTimeNanos?.let(ShutterSpeedFormatter::format),
        observed.zoomRatio?.let { String.format(Locale.US, "%.2f×", it) },
    ).joinToString(" · ").ifBlank { "waiting for metadata" }
    return "$requestedExposure | observed $observedExposure · AF ${observed.autoFocusState}"
}
