package top.r2dblog.justcamera.camera.control

import android.graphics.Rect
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build
import top.r2dblog.justcamera.camera.model.AutoExposureState
import top.r2dblog.justcamera.camera.model.AutoFocusState
import top.r2dblog.justcamera.camera.model.AutoWhiteBalanceState
import top.r2dblog.justcamera.camera.model.CameraCapabilities
import top.r2dblog.justcamera.camera.model.CameraCaptureMetadata
import top.r2dblog.justcamera.camera.model.CameraControlCapabilities
import top.r2dblog.justcamera.camera.model.FocusMode
import top.r2dblog.justcamera.camera.model.SensorRect
import top.r2dblog.justcamera.camera.model.WhiteBalanceMode
import kotlin.math.max

internal class CameraRequestController(
    private val camera: CameraCapabilities,
) {
    private var effectiveCapabilities = camera.controlCapabilities

    var requestedState: CameraControlState = CameraControlValidator.defaults(effectiveCapabilities)
        private set

    val capabilities: CameraControlCapabilities get() = effectiveCapabilities

    fun update(candidate: CameraControlState): CameraControlUpdate {
        val update = CameraControlValidator.validate(candidate, effectiveCapabilities)
        if (update.accepted) requestedState = update.state
        return update
    }

    fun setRawAvailable(available: Boolean): CameraControlUpdate {
        effectiveCapabilities = effectiveCapabilities.copy(rawAvailable = available)
        return update(
            requestedState.copy(
                captureMode = CaptureModeResolver.resolve(requestedState.captureMode, available),
            ),
        )
    }

    fun apply(builder: CaptureRequest.Builder) {
        val state = requestedState
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        applyExposure(builder, state)
        applyFocus(builder, state)
        applyWhiteBalance(builder, state)
        applyZoom(builder, state)
        applyMetering(builder, state.meteringRegion)
    }

    fun metadata(result: TotalCaptureResult): CameraCaptureMetadata {
        val crop = result.get(CaptureResult.SCALER_CROP_REGION)?.toSensorRect()
        val observedZoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            result.get(CaptureResult.CONTROL_ZOOM_RATIO)
        } else {
            crop?.let { camera.activeArray?.let { active -> ZoomCropCalculator.ratio(active, it) } }
        }
        return CameraCaptureMetadata(
            timestampNanos = result.get(CaptureResult.SENSOR_TIMESTAMP),
            sensitivityIso = result.get(CaptureResult.SENSOR_SENSITIVITY),
            exposureTimeNanos = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
            frameDurationNanos = result.get(CaptureResult.SENSOR_FRAME_DURATION),
            focusDistanceDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
            autoFocusState = mapAfState(result.get(CaptureResult.CONTROL_AF_STATE)),
            autoExposureState = mapAeState(result.get(CaptureResult.CONTROL_AE_STATE)),
            autoWhiteBalanceState = mapAwbState(result.get(CaptureResult.CONTROL_AWB_STATE)),
            exposureCompensationSteps = result.get(
                CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION,
            ),
            aperture = result.get(CaptureResult.LENS_APERTURE),
            focalLengthMm = result.get(CaptureResult.LENS_FOCAL_LENGTH),
            zoomRatio = observedZoom,
            cropRegion = crop,
        )
    }

    private fun applyExposure(builder: CaptureRequest.Builder, state: CameraControlState) {
        if (state.exposureMode == ExposureMode.MANUAL) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            state.iso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            state.exposureTimeNs?.let { exposureTime ->
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime)
                effectiveCapabilities.maxFrameDurationNanos?.let { maximum ->
                    builder.set(
                        CaptureRequest.SENSOR_FRAME_DURATION,
                        max(DEFAULT_PREVIEW_FRAME_DURATION_NS, exposureTime).coerceAtMost(maximum),
                    )
                }
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            if (effectiveCapabilities.exposureCompensationRange != null) {
                builder.set(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    state.exposureCompensationSteps,
                )
            }
            if (effectiveCapabilities.aeLockAvailable) {
                builder.set(CaptureRequest.CONTROL_AE_LOCK, state.aeLocked)
            }
        }
    }

    private fun applyFocus(builder: CaptureRequest.Builder, state: CameraControlState) {
        val requestedMode = if (state.afLockRequested &&
            FocusMode.AUTO in effectiveCapabilities.focusModes
        ) {
            FocusMode.AUTO
        } else {
            state.focusMode
        }
        if (requestedMode !in effectiveCapabilities.focusModes) return
        builder.set(CaptureRequest.CONTROL_AF_MODE, requestedMode.toPlatformAfMode())
        if (requestedMode == FocusMode.MANUAL &&
            effectiveCapabilities.manualFocusAvailable
        ) {
            state.focusDistanceDiopters?.let {
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it)
            }
        }
    }

    private fun applyWhiteBalance(builder: CaptureRequest.Builder, state: CameraControlState) {
        if (state.whiteBalanceMode in effectiveCapabilities.whiteBalanceModes) {
            builder.set(
                CaptureRequest.CONTROL_AWB_MODE,
                state.whiteBalanceMode.toPlatformAwbMode(),
            )
        }
        if (effectiveCapabilities.awbLockAvailable) {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, state.awbLocked)
        }
    }

    private fun applyZoom(builder: CaptureRequest.Builder, state: CameraControlState) {
        val zoomRange = effectiveCapabilities.zoomRatioRange
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && zoomRange != null) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, state.zoomRatio)
            return
        }
        effectiveCapabilities.activeArray?.let { active ->
            val crop = ZoomCropCalculator.crop(active, state.zoomRatio)
            builder.set(CaptureRequest.SCALER_CROP_REGION, crop.toRect())
        }
    }

    private fun applyMetering(builder: CaptureRequest.Builder, region: SensorRect?) {
        if (region == null) return
        val metering = arrayOf(MeteringRectangle(region.toRect(), MeteringRectangle.METERING_WEIGHT_MAX))
        if (effectiveCapabilities.maxAfMeteringRegions > 0) {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, metering)
        }
        if (effectiveCapabilities.maxAeMeteringRegions > 0) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, metering)
        }
        if (effectiveCapabilities.maxAwbMeteringRegions > 0) {
            builder.set(CaptureRequest.CONTROL_AWB_REGIONS, metering)
        }
    }

    private fun FocusMode.toPlatformAfMode(): Int = when (this) {
        FocusMode.CONTINUOUS_PICTURE -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        FocusMode.AUTO -> CameraMetadata.CONTROL_AF_MODE_AUTO
        FocusMode.MACRO -> CameraMetadata.CONTROL_AF_MODE_MACRO
        FocusMode.CONTINUOUS_VIDEO -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        FocusMode.EDOF -> CameraMetadata.CONTROL_AF_MODE_EDOF
        FocusMode.MANUAL -> CameraMetadata.CONTROL_AF_MODE_OFF
    }

    private fun WhiteBalanceMode.toPlatformAwbMode(): Int = when (this) {
        WhiteBalanceMode.AUTO -> CameraMetadata.CONTROL_AWB_MODE_AUTO
        WhiteBalanceMode.INCANDESCENT -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
        WhiteBalanceMode.FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
        WhiteBalanceMode.WARM_FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT
        WhiteBalanceMode.DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
        WhiteBalanceMode.CLOUDY_DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        WhiteBalanceMode.TWILIGHT -> CameraMetadata.CONTROL_AWB_MODE_TWILIGHT
        WhiteBalanceMode.SHADE -> CameraMetadata.CONTROL_AWB_MODE_SHADE
    }

    private fun Rect.toSensorRect() = SensorRect(left, top, right, bottom)
    private fun SensorRect.toRect() = Rect(left, top, right, bottom)

    private fun mapAfState(value: Int?): AutoFocusState = when (value) {
        CaptureResult.CONTROL_AF_STATE_INACTIVE -> AutoFocusState.INACTIVE
        CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> AutoFocusState.PASSIVE_SCAN
        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> AutoFocusState.PASSIVE_FOCUSED
        CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> AutoFocusState.ACTIVE_SCAN
        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> AutoFocusState.FOCUSED_LOCKED
        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ->
            AutoFocusState.NOT_FOCUSED_LOCKED
        CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> AutoFocusState.PASSIVE_UNFOCUSED
        else -> AutoFocusState.UNKNOWN
    }

    private fun mapAeState(value: Int?): AutoExposureState = when (value) {
        CaptureResult.CONTROL_AE_STATE_INACTIVE -> AutoExposureState.INACTIVE
        CaptureResult.CONTROL_AE_STATE_SEARCHING -> AutoExposureState.SEARCHING
        CaptureResult.CONTROL_AE_STATE_CONVERGED -> AutoExposureState.CONVERGED
        CaptureResult.CONTROL_AE_STATE_LOCKED -> AutoExposureState.LOCKED
        CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> AutoExposureState.FLASH_REQUIRED
        CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> AutoExposureState.PRECAPTURE
        else -> AutoExposureState.UNKNOWN
    }

    private fun mapAwbState(value: Int?): AutoWhiteBalanceState = when (value) {
        CaptureResult.CONTROL_AWB_STATE_INACTIVE -> AutoWhiteBalanceState.INACTIVE
        CaptureResult.CONTROL_AWB_STATE_SEARCHING -> AutoWhiteBalanceState.SEARCHING
        CaptureResult.CONTROL_AWB_STATE_CONVERGED -> AutoWhiteBalanceState.CONVERGED
        CaptureResult.CONTROL_AWB_STATE_LOCKED -> AutoWhiteBalanceState.LOCKED
        else -> AutoWhiteBalanceState.UNKNOWN
    }

    private companion object {
        const val DEFAULT_PREVIEW_FRAME_DURATION_NS = 33_333_333L
    }
}
