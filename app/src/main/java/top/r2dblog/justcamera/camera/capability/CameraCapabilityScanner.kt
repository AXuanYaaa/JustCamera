package top.r2dblog.justcamera.camera.capability

import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import top.r2dblog.justcamera.camera.model.CameraCapabilities
import top.r2dblog.justcamera.camera.model.CameraError
import top.r2dblog.justcamera.camera.model.CameraErrorCode
import top.r2dblog.justcamera.logging.JcLog
import top.r2dblog.justcamera.logging.LogCategory

data class CameraDiscoveryResult(
    val cameras: List<CameraCapabilities>,
    val errors: List<CameraError>,
)

class CameraCapabilityScanner(private val cameraManager: CameraManager) {
    fun discover(): CameraDiscoveryResult {
        val cameras = mutableListOf<CameraCapabilities>()
        val errors = mutableListOf<CameraError>()
        val ids = try {
            cameraManager.cameraIdList.toList()
        } catch (error: CameraAccessException) {
            return CameraDiscoveryResult(
                cameras = emptyList(),
                errors = listOf(accessError("Unable to enumerate cameras", error)),
            )
        }

        ids.forEach { cameraId ->
            try {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                cameras += CameraCapabilityMapper.map(cameraId, characteristics.toRaw())
            } catch (error: CameraAccessException) {
                val mapped = accessError("Unable to read camera $cameraId capabilities", error)
                JcLog.warn(LogCategory.CAMERA, mapped.message, error)
                errors += mapped
            } catch (error: IllegalArgumentException) {
                val mapped = CameraError(
                    CameraErrorCode.CAMERA_UNAVAILABLE,
                    "Camera $cameraId disappeared during discovery",
                    error,
                )
                JcLog.warn(LogCategory.CAMERA, mapped.message, error)
                errors += mapped
            }
        }
        return CameraDiscoveryResult(cameras, errors)
    }

    private fun CameraCharacteristics.toRaw(): RawCameraCharacteristics {
        val streamMap = get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outputs = (streamMap?.outputFormats ?: intArrayOf()).map { format ->
            val platformSizes = streamMap?.getOutputSizes(format) ?: emptyArray()
            RawOutput(
                format,
                platformSizes.map { RawSize(it.width, it.height) },
                platformSizes.mapNotNull { size ->
                    val duration = try {
                        streamMap?.getOutputMinFrameDuration(format, size) ?: 0L
                    } catch (_: IllegalArgumentException) {
                        0L
                    }
                    duration.takeIf { it > 0L }?.let { RawSize(size.width, size.height) to it }
                }.toMap(),
            )
        }

        return RawCameraCharacteristics(
            lensFacing = get(CameraCharacteristics.LENS_FACING),
            sensorOrientation = get(CameraCharacteristics.SENSOR_ORIENTATION),
            hardwareLevel = get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            activeArray = get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let {
                RawRect(it.left, it.top, it.right, it.bottom)
            },
            pixelArraySize = get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let {
                RawSize(it.width, it.height)
            },
            sensitivityRange = get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
                RawIntRange(it.lower, it.upper)
            },
            exposureTimeRange = get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
                RawLongRange(it.lower, it.upper)
            },
            maxFrameDuration = get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION),
            aeCompensationRange = get(
                CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE,
            )?.let { RawIntRange(it.lower, it.upper) },
            aeCompensationStep = get(
                CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP,
            )?.let { RawRational(it.numerator, it.denominator) },
            minimumFocusDistance = get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
            focalLengths = (get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?: floatArrayOf()).toList(),
            apertures = (get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?: floatArrayOf()).toList(),
            afModes = (get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?: intArrayOf()).toList(),
            aeModes = (get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
                ?: intArrayOf()).toList(),
            awbModes = (get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                ?: intArrayOf()).toList(),
            targetFpsRanges = (get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,
            ) ?: emptyArray()).map { RawFpsRange(it.lower, it.upper) },
            maxDigitalZoom = get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM),
            zoomRatioRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                    RawFloatRange(it.lower, it.upper)
                }
            } else {
                null
            },
            aeLockAvailable = get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true,
            awbLockAvailable = get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true,
            maxAfMeteringRegions = get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
            maxAeMeteringRegions = get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
            maxAwbMeteringRegions = get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB) ?: 0,
            requestCapabilities = (get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: intArrayOf()).toList(),
            opticalStabilizationModes =
                (get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                    ?: intArrayOf()).toList(),
            videoStabilizationModes =
                (get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                    ?: intArrayOf()).toList(),
            physicalCameraIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                physicalCameraIds
            } else {
                emptySet()
            },
            outputs = outputs,
            syncMaxLatency = get(CameraCharacteristics.SYNC_MAX_LATENCY),
        )
    }

    private fun accessError(message: String, cause: CameraAccessException): CameraError =
        CameraError(
            code = if (cause.reason == CameraAccessException.CAMERA_DISABLED ||
                cause.reason == CameraAccessException.CAMERA_IN_USE ||
                cause.reason == CameraAccessException.MAX_CAMERAS_IN_USE
            ) {
                CameraErrorCode.CAMERA_UNAVAILABLE
            } else {
                CameraErrorCode.ACCESS_FAILURE
            },
            message = message,
            cause = cause,
        )
}
