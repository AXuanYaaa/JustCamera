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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.r2dblog.justcamera.camera.application.CameraController
import top.r2dblog.justcamera.camera.model.CameraCapabilities
import top.r2dblog.justcamera.camera.model.CameraCapability

@Composable
fun CapabilityScreen(cameraController: CameraController, onBack: () -> Unit) {
    val cameras by cameraController.cameras.collectAsState()
    val selected by cameraController.selectedCamera.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Button(onClick = onBack) { Text("Back") }
            Column {
                Text("Camera capabilities", fontWeight = FontWeight.Bold)
                Text("Live Camera2 characteristics", style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            cameras.forEach { camera ->
                Button(
                    onClick = { cameraController.selectCamera(camera.cameraId) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected?.cameraId == camera.cameraId) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                ) {
                    Text("${camera.cameraId} · ${camera.facing}")
                }
            }
        }

        val camera = selected
        if (camera == null) {
            Text("No camera capabilities available", modifier = Modifier.padding(24.dp))
        } else {
            CapabilityList(camera, Modifier.weight(1f).navigationBarsPadding())
        }
    }
}

@Composable
private fun CapabilityList(camera: CameraCapabilities, modifier: Modifier = Modifier) {
    val rows = buildList {
        add("Camera ID" to camera.cameraId)
        add("Facing" to camera.facing.toString())
        add("Hardware level" to camera.hardwareLevel.toString())
        add("Sensor orientation" to "${camera.sensorOrientation}°")
        add("Pixel array" to camera.pixelArraySize?.toString().orMissing())
        add(
            "Active array" to camera.activeArray?.let {
                "${it.width}×${it.height} at (${it.left}, ${it.top})"
            }.orMissing(),
        )
        add(
            "ISO" to camera.sensitivityRange?.let { "${it.lower}–${it.upper}" }.orMissing(),
        )
        add(
            "Exposure time" to camera.exposureTimeRangeNanos?.let {
                "${formatNanos(it.lower)}–${formatNanos(it.upper)}"
            }.orMissing(),
        )
        add("Max frame duration" to camera.maxFrameDurationNanos?.let(::formatNanos).orMissing())
        add("Minimum focus distance" to camera.minimumFocusDistanceDiopters?.let {
            "$it D"
        }.orMissing())
        add("Focal lengths" to camera.focalLengthsMm.joinOrMissing(" mm"))
        add("Apertures" to camera.apertures.joinOrMissing(prefix = "f/"))
        add("AF modes" to camera.afModes.joinToString().ifBlank { "Not reported" })
        add("AE modes" to camera.aeModes.joinToString().ifBlank { "Not reported" })
        add("AWB modes" to camera.awbModes.joinToString().ifBlank { "Not reported" })
        add("FPS ranges" to camera.targetFpsRanges.joinToString().ifBlank { "Not reported" })
        add("RAW" to camera.supports(CameraCapability.RAW))
        add("Manual sensor" to camera.supports(CameraCapability.MANUAL_SENSOR))
        add("Manual post processing" to camera.supports(CameraCapability.MANUAL_POST_PROCESSING))
        add("Burst" to camera.supports(CameraCapability.BURST_CAPTURE))
        add("YUV reprocessing" to camera.supports(CameraCapability.YUV_REPROCESSING))
        add("Private reprocessing" to camera.supports(CameraCapability.PRIVATE_REPROCESSING))
        add(
            "Request capability codes" to
                camera.platformRequestCapabilities.joinToString().ifBlank { "None" },
        )
        add("Logical multi-camera" to camera.supports(CameraCapability.LOGICAL_MULTI_CAMERA))
        add("Physical camera IDs" to camera.physicalCameraIds.joinToString().ifBlank { "None" })
        add("Depth output" to camera.supports(CameraCapability.DEPTH_OUTPUT))
        add("Optical stabilization" to yesNo(camera.opticalStabilization))
        add("Video stabilization" to yesNo(camera.videoStabilization))
        add("Max digital zoom" to "${camera.maxDigitalZoom}×")
        camera.outputs.forEach { output ->
            add(
                "Output ${output.format}" to output.sizes.joinToString(limit = 8).ifBlank {
                    "Sizes not reported"
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

private fun CameraCapabilities.supports(capability: CameraCapability) =
    yesNo(capability in capabilities)

private fun yesNo(value: Boolean) = if (value) "Yes" else "No"

private fun Any?.orMissing(): String = this?.toString() ?: "Not reported"

private fun List<Float>.joinOrMissing(suffix: String = "", prefix: String = ""): String =
    if (isEmpty()) "Not reported" else joinToString { "$prefix$it$suffix" }

private fun formatNanos(value: Long): String = when {
    value >= 1_000_000_000L -> "%.2f s".format(value / 1_000_000_000.0)
    value >= 1_000_000L -> "%.2f ms".format(value / 1_000_000.0)
    value >= 1_000L -> "%.2f μs".format(value / 1_000.0)
    else -> "$value ns"
}
