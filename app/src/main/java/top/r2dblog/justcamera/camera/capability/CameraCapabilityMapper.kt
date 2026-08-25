package top.r2dblog.justcamera.camera.capability

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import top.r2dblog.justcamera.camera.model.CameraCapabilities
import top.r2dblog.justcamera.camera.model.CameraCapability
import top.r2dblog.justcamera.camera.model.CameraFacing
import top.r2dblog.justcamera.camera.model.CameraOutputFormat
import top.r2dblog.justcamera.camera.model.FrameRateRange
import top.r2dblog.justcamera.camera.model.HardwareLevel
import top.r2dblog.justcamera.camera.model.ImageSize
import top.r2dblog.justcamera.camera.model.SensorRect
import top.r2dblog.justcamera.camera.model.SupportedOutput
import top.r2dblog.justcamera.camera.model.ValueRange
import top.r2dblog.justcamera.camera.model.FocusMode
import top.r2dblog.justcamera.camera.model.RationalValue
import top.r2dblog.justcamera.camera.model.WhiteBalanceMode

object CameraCapabilityMapper {
    fun map(cameraId: String, raw: RawCameraCharacteristics): CameraCapabilities =
        CameraCapabilities(
            cameraId = cameraId,
            facing = mapFacing(raw.lensFacing),
            sensorOrientation = raw.sensorOrientation ?: 0,
            hardwareLevel = mapHardwareLevel(raw.hardwareLevel),
            activeArray = raw.activeArray?.let {
                SensorRect(it.left, it.top, it.right, it.bottom)
            },
            pixelArraySize = raw.pixelArraySize?.let { ImageSize(it.width, it.height) },
            sensitivityRange = raw.sensitivityRange?.let { ValueRange(it.lower, it.upper) },
            exposureTimeRangeNanos = raw.exposureTimeRange?.let {
                ValueRange(it.lower, it.upper)
            },
            maxFrameDurationNanos = raw.maxFrameDuration,
            aeCompensationRange = raw.aeCompensationRange?.let {
                ValueRange(it.lower, it.upper)
            },
            aeCompensationStep = raw.aeCompensationStep?.let {
                RationalValue(it.numerator, it.denominator)
            },
            minimumFocusDistanceDiopters = raw.minimumFocusDistance,
            focalLengthsMm = raw.focalLengths,
            apertures = raw.apertures,
            afModes = raw.afModes.map(::afModeName),
            focusModes = raw.afModes.mapNotNull(::mapFocusMode).filterTo(mutableSetOf()) {
                it != FocusMode.MANUAL || (raw.minimumFocusDistance ?: 0f) > 0f
            },
            aeModes = raw.aeModes.map(::aeModeName),
            awbModes = raw.awbModes.map(::awbModeName),
            whiteBalanceModes = raw.awbModes.mapNotNull(::mapWhiteBalanceMode).toSet(),
            targetFpsRanges = raw.targetFpsRanges.map { FrameRateRange(it.lower, it.upper) },
            maxDigitalZoom = raw.maxDigitalZoom?.coerceAtLeast(1f) ?: 1f,
            zoomRatioRange = raw.zoomRatioRange?.let { ValueRange(it.lower, it.upper) },
            aeLockAvailable = raw.aeLockAvailable,
            awbLockAvailable = raw.awbLockAvailable,
            maxAfMeteringRegions = raw.maxAfMeteringRegions,
            maxAeMeteringRegions = raw.maxAeMeteringRegions,
            maxAwbMeteringRegions = raw.maxAwbMeteringRegions,
            capabilities = raw.requestCapabilities.mapNotNull(::mapCapability).toSet(),
            platformRequestCapabilities = raw.requestCapabilities,
            opticalStabilization = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in
                raw.opticalStabilizationModes,
            videoStabilization = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in
                raw.videoStabilizationModes,
            physicalCameraIds = raw.physicalCameraIds,
            outputs = raw.outputs.map { output ->
                SupportedOutput(
                    format = mapOutputFormat(output.format),
                    platformFormat = output.format,
                    sizes = output.sizes.map { ImageSize(it.width, it.height) },
                    minimumFrameDurationNanos = output.minimumFrameDurationNanos.mapKeys {
                        ImageSize(it.key.width, it.key.height)
                    },
                )
            },
            syncMaxLatency = raw.syncMaxLatency,
        )

    private fun mapFacing(value: Int?): CameraFacing = when (value) {
        CameraCharacteristics.LENS_FACING_FRONT -> CameraFacing.FRONT
        CameraCharacteristics.LENS_FACING_BACK -> CameraFacing.BACK
        CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraFacing.EXTERNAL
        else -> CameraFacing.UNKNOWN
    }

    private fun mapHardwareLevel(value: Int?): HardwareLevel = when (value) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> HardwareLevel.LEGACY
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> HardwareLevel.LIMITED
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> HardwareLevel.FULL
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> HardwareLevel.LEVEL_3
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> HardwareLevel.EXTERNAL
        else -> HardwareLevel.UNKNOWN
    }

    private fun mapCapability(value: Int): CameraCapability? = when (value) {
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> CameraCapability.RAW
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR ->
            CameraCapability.MANUAL_SENSOR
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING ->
            CameraCapability.MANUAL_POST_PROCESSING
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE ->
            CameraCapability.BURST_CAPTURE
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING ->
            CameraCapability.YUV_REPROCESSING
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING ->
            CameraCapability.PRIVATE_REPROCESSING
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA ->
            CameraCapability.LOGICAL_MULTI_CAMERA
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT ->
            CameraCapability.DEPTH_OUTPUT
        else -> null
    }

    private fun mapOutputFormat(value: Int): CameraOutputFormat = when (value) {
        ImageFormat.JPEG -> CameraOutputFormat.JPEG
        ImageFormat.YUV_420_888 -> CameraOutputFormat.YUV_420_888
        ImageFormat.RAW_SENSOR -> CameraOutputFormat.RAW_SENSOR
        ImageFormat.PRIVATE -> CameraOutputFormat.PRIVATE
        ImageFormat.DEPTH16, ImageFormat.DEPTH_JPEG, ImageFormat.DEPTH_POINT_CLOUD ->
            CameraOutputFormat.DEPTH
        else -> CameraOutputFormat.OTHER
    }

    private fun afModeName(value: Int): String = when (value) {
        CameraMetadata.CONTROL_AF_MODE_OFF -> "Off"
        CameraMetadata.CONTROL_AF_MODE_AUTO -> "Auto"
        CameraMetadata.CONTROL_AF_MODE_MACRO -> "Macro"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "Continuous video"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "Continuous picture"
        CameraMetadata.CONTROL_AF_MODE_EDOF -> "EDOF"
        else -> "Unknown ($value)"
    }

    private fun mapFocusMode(value: Int): FocusMode? = when (value) {
        CameraMetadata.CONTROL_AF_MODE_OFF -> FocusMode.MANUAL
        CameraMetadata.CONTROL_AF_MODE_AUTO -> FocusMode.AUTO
        CameraMetadata.CONTROL_AF_MODE_MACRO -> FocusMode.MACRO
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> FocusMode.CONTINUOUS_VIDEO
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> FocusMode.CONTINUOUS_PICTURE
        CameraMetadata.CONTROL_AF_MODE_EDOF -> FocusMode.EDOF
        else -> null
    }

    private fun aeModeName(value: Int): String = when (value) {
        CameraMetadata.CONTROL_AE_MODE_OFF -> "Off"
        CameraMetadata.CONTROL_AE_MODE_ON -> "On"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH -> "Auto flash"
        CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> "Always flash"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> "Red-eye flash"
        else -> "Unknown ($value)"
    }

    private fun awbModeName(value: Int): String = when (value) {
        CameraMetadata.CONTROL_AWB_MODE_OFF -> "Off"
        CameraMetadata.CONTROL_AWB_MODE_AUTO -> "Auto"
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "Incandescent"
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "Fluorescent"
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "Warm fluorescent"
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "Daylight"
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "Cloudy daylight"
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> "Twilight"
        CameraMetadata.CONTROL_AWB_MODE_SHADE -> "Shade"
        else -> "Unknown ($value)"
    }

    private fun mapWhiteBalanceMode(value: Int): WhiteBalanceMode? = when (value) {
        CameraMetadata.CONTROL_AWB_MODE_AUTO -> WhiteBalanceMode.AUTO
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> WhiteBalanceMode.INCANDESCENT
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> WhiteBalanceMode.FLUORESCENT
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> WhiteBalanceMode.WARM_FLUORESCENT
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> WhiteBalanceMode.DAYLIGHT
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> WhiteBalanceMode.CLOUDY_DAYLIGHT
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> WhiteBalanceMode.TWILIGHT
        CameraMetadata.CONTROL_AWB_MODE_SHADE -> WhiteBalanceMode.SHADE
        else -> null
    }
}
