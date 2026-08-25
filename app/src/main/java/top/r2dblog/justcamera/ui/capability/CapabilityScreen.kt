package top.r2dblog.justcamera.ui.capability

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.r2dblog.justcamera.R
import top.r2dblog.justcamera.camera.application.CameraController
import top.r2dblog.justcamera.camera.model.CameraCapabilities
import top.r2dblog.justcamera.camera.model.CameraCapability
import top.r2dblog.justcamera.camera.model.CameraFacing
import top.r2dblog.justcamera.hdr.capture.HdrCapabilityAssessment
import top.r2dblog.justcamera.nativecore.NativeCore

@Composable
fun CapabilityScreen(cameraController: CameraController, onBack: () -> Unit) {
    val cameras by cameraController.cameras.collectAsState()
    val selected by cameraController.selectedCamera.collectAsState()
    val rawCaptureAvailable by cameraController.rawCaptureAvailable.collectAsState()
    val hdrCapability by cameraController.hdrCapability.collectAsState()
    val nativeInformation = remember {
        NativeCore.capabilities()?.let { "${it.coreVersion} · ${it.processingVersion} · ${it.abi}" }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painterResource(R.drawable.ic_back),
                    contentDescription = stringResource(R.string.back),
                )
            }
            Column {
                Text(
                    stringResource(R.string.capability_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(R.string.capability_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            cameras.forEach { camera ->
                Button(
                    onClick = { cameraController.selectCamera(camera.cameraId) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected?.cameraId == camera.cameraId) {
                            MaterialTheme.colorScheme.primary
                        } else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Text(
                        stringResource(R.string.camera_id_value, camera.cameraId) +
                            " · ${facingLabel(camera.facing)}",
                    )
                }
            }
        }

        val camera = selected
        if (camera == null) {
            Text(stringResource(R.string.capability_none), modifier = Modifier.padding(24.dp))
        } else {
            CapabilityList(
                camera = camera,
                rawCaptureAvailable = rawCaptureAvailable,
                hdrCapability = hdrCapability,
                nativeInformation = nativeInformation,
                modifier = Modifier.weight(1f).navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun CapabilityList(
    camera: CameraCapabilities,
    rawCaptureAvailable: Boolean,
    hdrCapability: HdrCapabilityAssessment?,
    nativeInformation: String?,
    modifier: Modifier = Modifier,
) {
    val missing = stringResource(R.string.not_reported)
    val none = stringResource(R.string.none)
    val yes = stringResource(R.string.yes)
    val no = stringResource(R.string.no)
    val rows = buildList {
        add(stringResource(R.string.camera_id) to camera.cameraId)
        add(stringResource(R.string.camera_facing) to facingLabel(camera.facing))
        add(stringResource(R.string.raw_capture_available) to yesNo(rawCaptureAvailable, yes, no))
        add(stringResource(R.string.hdr_capability) to hdrCapability?.let {
            "${it.level} · ${it.reason}"
        }.orMissing(missing))
        add(stringResource(R.string.native_information) to (nativeInformation ?: missing))
        add(stringResource(R.string.hardware_level) to camera.hardwareLevel.toString())
        add(stringResource(R.string.sensor_orientation) to "${camera.sensorOrientation}°")
        add(stringResource(R.string.pixel_array) to (camera.pixelArraySize?.toString() ?: missing))
        add(
            stringResource(R.string.active_array) to (camera.activeArray?.let {
                "${it.width}×${it.height} @ (${it.left}, ${it.top})"
            } ?: missing),
        )
        add(stringResource(R.string.pro_iso) to camera.sensitivityRange?.let {
            "${it.lower}–${it.upper}"
        }.orMissing(missing))
        add(stringResource(R.string.exposure_time) to camera.exposureTimeRangeNanos?.let {
            "${formatNanos(it.lower)}–${formatNanos(it.upper)}"
        }.orMissing(missing))
        add(stringResource(R.string.max_frame_duration) to
            camera.maxFrameDurationNanos?.let(::formatNanos).orMissing(missing))
        add(stringResource(R.string.ae_compensation) to camera.aeCompensationRange?.let { range ->
            "${range.lower}–${range.upper} · ${camera.aeCompensationStep?.value ?: missing} EV"
        }.orMissing(missing))
        add(stringResource(R.string.minimum_focus_distance) to
            camera.minimumFocusDistanceDiopters?.let { "$it D" }.orMissing(missing))
        add(stringResource(R.string.focal_lengths) to camera.focalLengthsMm.joinOrMissing(missing, " mm"))
        add(stringResource(R.string.apertures) to camera.apertures.joinOrMissing(missing, prefix = "f/"))
        add(stringResource(R.string.af_modes) to camera.afModes.joinToString().ifBlank { missing })
        add(stringResource(R.string.ae_modes) to camera.aeModes.joinToString().ifBlank { missing })
        add(stringResource(R.string.awb_modes) to camera.awbModes.joinToString().ifBlank { missing })
        add(stringResource(R.string.fps_ranges) to camera.targetFpsRanges.joinToString().ifBlank { missing })
        add(stringResource(R.string.format_raw) to yesNo(CameraCapability.RAW in camera.capabilities, yes, no))
        add(stringResource(R.string.raw_output) to yesNo(camera.supportsRaw, yes, no))
        add(stringResource(R.string.manual_sensor) to camera.supports(CameraCapability.MANUAL_SENSOR, yes, no))
        add(stringResource(R.string.manual_post_processing) to camera.supports(CameraCapability.MANUAL_POST_PROCESSING, yes, no))
        add(stringResource(R.string.burst) to camera.supports(CameraCapability.BURST_CAPTURE, yes, no))
        add(stringResource(R.string.yuv_reprocessing) to camera.supports(CameraCapability.YUV_REPROCESSING, yes, no))
        add(stringResource(R.string.private_reprocessing) to camera.supports(CameraCapability.PRIVATE_REPROCESSING, yes, no))
        add(stringResource(R.string.request_capability_codes) to
            camera.platformRequestCapabilities.joinToString().ifBlank { none })
        add(stringResource(R.string.logical_multi_camera) to camera.supports(CameraCapability.LOGICAL_MULTI_CAMERA, yes, no))
        add(stringResource(R.string.physical_camera_ids) to camera.physicalCameraIds.joinToString().ifBlank { none })
        add(stringResource(R.string.depth_output) to camera.supports(CameraCapability.DEPTH_OUTPUT, yes, no))
        add(stringResource(R.string.optical_stabilization) to yesNo(camera.opticalStabilization, yes, no))
        add(stringResource(R.string.video_stabilization) to yesNo(camera.videoStabilization, yes, no))
        add(stringResource(R.string.max_digital_zoom) to "${camera.maxDigitalZoom}×")
        add(stringResource(R.string.zoom_ratio_range) to camera.zoomRatioRange?.let {
            "${it.lower}–${it.upper}×"
        }.orMissing(missing))
        add(stringResource(R.string.ae_lock) to yesNo(camera.aeLockAvailable, yes, no))
        add(stringResource(R.string.awb_lock) to yesNo(camera.awbLockAvailable, yes, no))
        add(stringResource(R.string.metering_regions) to
            "${camera.maxAfMeteringRegions}/${camera.maxAeMeteringRegions}/" +
                camera.maxAwbMeteringRegions)
        camera.outputs.forEach { output ->
            add(
                stringResource(R.string.output_format, output.format) to
                    output.sizes.joinToString(limit = 8).ifBlank {
                        stringResource(R.string.sizes_not_reported)
                    },
            )
        }
    }

    LazyColumn(modifier = modifier.padding(horizontal = 12.dp)) {
        items(rows) { (label, value) -> CapabilityRow(label, value) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun facingLabel(facing: CameraFacing): String = stringResource(
    when (facing) {
        CameraFacing.FRONT -> R.string.facing_front
        CameraFacing.BACK -> R.string.facing_back
        CameraFacing.EXTERNAL -> R.string.facing_external
        CameraFacing.UNKNOWN -> R.string.facing_unknown
    },
)

@Composable
private fun CapabilityRow(label: String, value: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(3.dp))
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun CameraCapabilities.supports(capability: CameraCapability, yes: String, no: String) =
    yesNo(capability in capabilities, yes, no)

private fun yesNo(value: Boolean, yes: String, no: String) = if (value) yes else no

private fun Any?.orMissing(missing: String): String = this?.toString() ?: missing

private fun List<Float>.joinOrMissing(
    missing: String,
    suffix: String = "",
    prefix: String = "",
): String = if (isEmpty()) missing else joinToString { "$prefix$it$suffix" }

private fun formatNanos(value: Long): String = when {
    value >= 1_000_000_000L -> "%.2f s".format(value / 1_000_000_000.0)
    value >= 1_000_000L -> "%.2f ms".format(value / 1_000_000.0)
    value >= 1_000L -> "%.2f μs".format(value / 1_000.0)
    else -> "$value ns"
}
