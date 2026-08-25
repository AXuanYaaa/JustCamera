package top.r2dblog.justcamera.ui.camera

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.r2dblog.justcamera.R
import top.r2dblog.justcamera.camera.control.CameraControlState
import top.r2dblog.justcamera.camera.control.ExposureCompensation
import top.r2dblog.justcamera.camera.control.ExposureMode
import top.r2dblog.justcamera.camera.control.ShutterSpeedFormatter
import top.r2dblog.justcamera.camera.model.CameraControlCapabilities
import top.r2dblog.justcamera.camera.model.CameraError
import top.r2dblog.justcamera.camera.model.CaptureMode
import top.r2dblog.justcamera.camera.model.FocusMode
import top.r2dblog.justcamera.camera.model.WhiteBalanceMode
import top.r2dblog.justcamera.ui.theme.CameraColors
import top.r2dblog.justcamera.ui.theme.CameraShapes
import java.util.Locale

private enum class ControlPanel { ISO, SHUTTER, EV, FOCUS, WB, ZOOM }

@Composable
fun ProControlPanel(
    state: CameraControlState,
    capabilities: CameraControlCapabilities,
    controlError: CameraError?,
    onStateChange: (CameraControlState) -> Unit,
) {
    var selectedPanel by remember { mutableStateOf<ControlPanel?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        controlError?.let {
            Text(
                text = stringResource(R.string.control_failed),
                color = Color(0xFFFFB4AB),
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (capabilities.manualSensor) {
                ProButton(
                    stringResource(R.string.pro_iso),
                    isoLabel(state),
                    selectedPanel == ControlPanel.ISO,
                ) { selectedPanel = toggle(selectedPanel, ControlPanel.ISO) }
                ProButton(
                    stringResource(R.string.pro_shutter_short),
                    shutterLabel(state),
                    selectedPanel == ControlPanel.SHUTTER,
                ) { selectedPanel = toggle(selectedPanel, ControlPanel.SHUTTER) }
            }
            if (capabilities.hasUsefulExposureCompensation()) {
                ProButton(
                    stringResource(R.string.pro_ev),
                    ExposureCompensation.label(
                        state.exposureCompensationSteps,
                        capabilities.exposureCompensationStep!!,
                    ),
                    selectedPanel == ControlPanel.EV,
                ) { selectedPanel = toggle(selectedPanel, ControlPanel.EV) }
            }
            if (capabilities.focusModes.isNotEmpty()) {
                ProButton(
                    stringResource(R.string.pro_focus),
                    focusLabel(state),
                    selectedPanel == ControlPanel.FOCUS,
                ) { selectedPanel = toggle(selectedPanel, ControlPanel.FOCUS) }
            }
            if (capabilities.whiteBalanceModes.isNotEmpty()) {
                ProButton(
                    stringResource(R.string.pro_white_balance),
                    whiteBalanceLabel(state.whiteBalanceMode),
                    selectedPanel == ControlPanel.WB,
                ) { selectedPanel = toggle(selectedPanel, ControlPanel.WB) }
            }
            val zoomRange = capabilities.effectiveZoomRange
            if (zoomRange.upper > zoomRange.lower) {
                ProButton(
                    stringResource(R.string.pro_zoom),
                    String.format(Locale.US, "%.1f×", state.zoomRatio),
                    selectedPanel == ControlPanel.ZOOM,
                ) { selectedPanel = toggle(selectedPanel, ControlPanel.ZOOM) }
            }
            ProButton(
                stringResource(R.string.pro_format),
                captureModeLabel(state.captureMode),
                false,
            ) {
                onStateChange(
                    state.copy(captureMode = nextCaptureMode(state.captureMode, capabilities)),
                )
            }
            if (capabilities.aeLockAvailable && state.exposureMode == ExposureMode.AUTO) {
                ProButton(
                    stringResource(R.string.pro_ae_lock),
                    onOffLabel(state.aeLocked),
                    state.aeLocked,
                ) { onStateChange(state.copy(aeLocked = !state.aeLocked)) }
            }
            if (FocusMode.AUTO in capabilities.focusModes) {
                ProButton(
                    stringResource(R.string.pro_af_lock),
                    onOffLabel(state.afLockRequested),
                    state.afLockRequested,
                ) { onStateChange(state.copy(afLockRequested = !state.afLockRequested)) }
            }
            if (capabilities.awbLockAvailable && state.whiteBalanceMode == WhiteBalanceMode.AUTO) {
                ProButton(
                    stringResource(R.string.pro_awb_lock),
                    onOffLabel(state.awbLocked),
                    state.awbLocked,
                ) { onStateChange(state.copy(awbLocked = !state.awbLocked)) }
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
    PanelRow(stringResource(R.string.iso_range, manual.iso!!, range.lower, range.upper)) {
        AutoChip(state.exposureMode == ExposureMode.AUTO) {
            onStateChange(state.copy(exposureMode = ExposureMode.AUTO))
        }
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
        stringResource(
            R.string.shutter_range,
            ShutterSpeedFormatter.format(manual.exposureTimeNs!!),
            ShutterSpeedFormatter.format(range.lower),
            ShutterSpeedFormatter.format(range.upper),
        ),
    ) {
        AutoChip(state.exposureMode == ExposureMode.AUTO) {
            onStateChange(state.copy(exposureMode = ExposureMode.AUTO))
        }
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
            onValueChange = { onStateChange(state.copy(exposureCompensationSteps = it.toInt())) },
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
    PanelSurface {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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
                                } else null,
                                afLockRequested = false,
                            ),
                        )
                    },
                    label = { Text(focusModeLabel(mode)) },
                )
            }
        }
        if (state.focusMode == FocusMode.MANUAL && capabilities.manualFocusAvailable) {
            val maximum = capabilities.minimumFocusDistanceDiopters ?: return@PanelSurface
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
    PanelSurface {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            capabilities.whiteBalanceModes.forEach { mode ->
                FilterChip(
                    selected = state.whiteBalanceMode == mode,
                    onClick = { onStateChange(state.copy(whiteBalanceMode = mode)) },
                    label = { Text(whiteBalanceLabel(mode)) },
                )
            }
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
    PanelRow(
        stringResource(
            R.string.zoom_range,
            String.format(Locale.US, "%.2f×", state.zoomRatio),
            String.format(Locale.US, "%.2f×", range.lower),
            String.format(Locale.US, "%.2f×", range.upper),
        ),
    ) {
        Slider(
            value = state.zoomRatio,
            onValueChange = { onStateChange(state.copy(zoomRatio = it)) },
            valueRange = range.lower..range.upper,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AutoChip(selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(stringResource(R.string.automatic)) },
    )
}

@Composable
private fun PanelRow(label: String, content: @Composable RowScope.() -> Unit) {
    PanelSurface {
        Text(label, color = Color.White, fontSize = 11.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun PanelSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        color = CameraColors.ControlSurface,
        shape = CameraShapes.Control,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), content = content)
    }
}

@Composable
private fun ProButton(label: String, value: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) CameraColors.ControlSurfaceSelected else CameraColors.ControlSurface,
        shape = CameraShapes.Control,
        modifier = Modifier
            .widthIn(min = 58.dp)
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val color = if (selected) Color.Black else Color.White
            Text(label, fontSize = 9.sp, color = color.copy(alpha = 0.72f))
            Text(value, fontSize = 11.sp, color = color, maxLines = 1)
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

@Composable
private fun isoLabel(state: CameraControlState): String =
    if (state.exposureMode == ExposureMode.AUTO) stringResource(R.string.automatic) else {
        state.iso?.toString() ?: "—"
    }

@Composable
private fun shutterLabel(state: CameraControlState): String =
    if (state.exposureMode == ExposureMode.AUTO) stringResource(R.string.automatic) else {
        state.exposureTimeNs?.let(ShutterSpeedFormatter::format) ?: "—"
    }

@Composable
private fun focusLabel(state: CameraControlState): String =
    if (state.focusMode == FocusMode.MANUAL) {
        focusDistanceLabel(state.focusDistanceDiopters ?: 0f)
    } else focusModeLabel(state.focusMode)

@Composable
private fun focusDistanceLabel(diopters: Float): String = when {
    diopters <= 0.0001f -> stringResource(R.string.focus_infinity)
    else -> stringResource(R.string.focus_distance_meters, 1f / diopters)
}

@Composable
private fun focusModeLabel(mode: FocusMode): String = stringResource(
    when (mode) {
        FocusMode.CONTINUOUS_PICTURE -> R.string.focus_continuous
        FocusMode.AUTO -> R.string.focus_auto
        FocusMode.MACRO -> R.string.focus_macro
        FocusMode.CONTINUOUS_VIDEO -> R.string.focus_video
        FocusMode.EDOF -> R.string.focus_edof
        FocusMode.MANUAL -> R.string.focus_manual
    },
)

@Composable
private fun whiteBalanceLabel(mode: WhiteBalanceMode): String = stringResource(
    when (mode) {
        WhiteBalanceMode.AUTO -> R.string.wb_auto
        WhiteBalanceMode.INCANDESCENT -> R.string.wb_incandescent
        WhiteBalanceMode.FLUORESCENT -> R.string.wb_fluorescent
        WhiteBalanceMode.WARM_FLUORESCENT -> R.string.wb_warm_fluorescent
        WhiteBalanceMode.DAYLIGHT -> R.string.wb_daylight
        WhiteBalanceMode.CLOUDY_DAYLIGHT -> R.string.wb_cloudy
        WhiteBalanceMode.TWILIGHT -> R.string.wb_twilight
        WhiteBalanceMode.SHADE -> R.string.wb_shade
    },
)

@Composable
private fun captureModeLabel(mode: CaptureMode): String = stringResource(
    when (mode) {
        CaptureMode.JPEG_ONLY -> R.string.format_jpeg
        CaptureMode.RAW_ONLY -> R.string.format_raw
        CaptureMode.JPEG_AND_RAW -> R.string.format_jpeg_raw
    },
)

@Composable
private fun onOffLabel(on: Boolean): String = stringResource(if (on) R.string.on else R.string.off)

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
