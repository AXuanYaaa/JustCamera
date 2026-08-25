package top.r2dblog.justcamera.camera.control

import top.r2dblog.justcamera.camera.model.CameraControlCapabilities
import top.r2dblog.justcamera.camera.model.FocusMode
import top.r2dblog.justcamera.camera.model.WhiteBalanceMode
import kotlin.math.max
import kotlin.math.min

object CameraControlValidator {
    fun defaults(capabilities: CameraControlCapabilities): CameraControlState {
        val focusMode = when {
            FocusMode.CONTINUOUS_PICTURE in capabilities.focusModes ->
                FocusMode.CONTINUOUS_PICTURE
            FocusMode.AUTO in capabilities.focusModes -> FocusMode.AUTO
            FocusMode.MANUAL in capabilities.focusModes -> FocusMode.MANUAL
            else -> capabilities.focusModes.firstOrNull() ?: FocusMode.CONTINUOUS_PICTURE
        }
        val whiteBalance = if (WhiteBalanceMode.AUTO in capabilities.whiteBalanceModes) {
            WhiteBalanceMode.AUTO
        } else {
            capabilities.whiteBalanceModes.firstOrNull() ?: WhiteBalanceMode.AUTO
        }
        return CameraControlState(
            focusMode = focusMode,
            focusDistanceDiopters = if (focusMode == FocusMode.MANUAL) 0f else null,
            whiteBalanceMode = whiteBalance,
            zoomRatio = capabilities.effectiveZoomRange.lower,
        )
    }

    fun validate(
        candidate: CameraControlState,
        capabilities: CameraControlCapabilities,
    ): CameraControlUpdate {
        val errors = mutableListOf<String>()
        val adjustments = mutableListOf<String>()

        if (candidate.exposureMode == ExposureMode.MANUAL && !capabilities.manualSensor) {
            errors += "Manual exposure is not supported by this camera"
        }
        val iso = if (candidate.exposureMode == ExposureMode.MANUAL) {
            val range = capabilities.sensitivityRange
            when {
                range == null -> {
                    errors += "The camera does not report an ISO range"
                    null
                }
                candidate.iso == null -> {
                    errors += "Manual exposure requires an ISO value"
                    null
                }
                else -> candidate.iso.coerceIn(range.lower, range.upper).also {
                    if (it != candidate.iso) adjustments += "ISO clamped to $it"
                }
            }
        } else {
            null
        }
        val exposureTime = if (candidate.exposureMode == ExposureMode.MANUAL) {
            val range = capabilities.exposureTimeRangeNanos
            when {
                range == null -> {
                    errors += "The camera does not report a shutter range"
                    null
                }
                candidate.exposureTimeNs == null -> {
                    errors += "Manual exposure requires a shutter value"
                    null
                }
                else -> {
                    val upper = capabilities.maxFrameDurationNanos?.let {
                        max(range.lower, min(range.upper, it))
                    } ?: range.upper
                    candidate.exposureTimeNs.coerceIn(range.lower, upper).also {
                        if (it != candidate.exposureTimeNs) {
                            adjustments += "Shutter clamped to ${ShutterSpeedFormatter.format(it)}"
                        }
                    }
                }
            }
        } else {
            null
        }

        val compensation = capabilities.exposureCompensationRange?.let { range ->
            candidate.exposureCompensationSteps.coerceIn(range.lower, range.upper).also {
                if (it != candidate.exposureCompensationSteps) {
                    adjustments += "Exposure compensation clamped to $it steps"
                }
            }
        } ?: 0.also {
            if (candidate.exposureCompensationSteps != 0) {
                adjustments += "Exposure compensation is not supported"
            }
        }

        if (capabilities.focusModes.isNotEmpty() &&
            candidate.focusMode !in capabilities.focusModes
        ) {
            errors += "Focus mode ${candidate.focusMode.label} is not supported"
        }
        val focusDistance = if (candidate.focusMode == FocusMode.MANUAL) {
            val maximum = capabilities.minimumFocusDistanceDiopters
            when {
                maximum == null || maximum <= 0f -> {
                    errors += "Manual focus is not supported"
                    null
                }
                candidate.focusDistanceDiopters == null -> {
                    errors += "Manual focus requires a distance"
                    null
                }
                else -> candidate.focusDistanceDiopters.coerceIn(0f, maximum).also {
                    if (it != candidate.focusDistanceDiopters) {
                        adjustments += "Focus distance clamped to $it diopters"
                    }
                }
            }
        } else {
            null
        }

        if (capabilities.whiteBalanceModes.isNotEmpty() &&
            candidate.whiteBalanceMode !in capabilities.whiteBalanceModes
        ) {
            errors += "White balance ${candidate.whiteBalanceMode.label} is not supported"
        }
        if (candidate.aeLocked && !capabilities.aeLockAvailable) {
            errors += "AE lock is not supported"
        }
        if (candidate.afLockRequested && FocusMode.AUTO !in capabilities.focusModes) {
            errors += "AF lock requires AUTO focus support"
        }
        if (candidate.awbLocked && !capabilities.awbLockAvailable) {
            errors += "AWB lock is not supported"
        }

        val zoomRange = capabilities.effectiveZoomRange
        val zoom = candidate.zoomRatio.coerceIn(zoomRange.lower, zoomRange.upper).also {
            if (it != candidate.zoomRatio) adjustments += "Zoom clamped to ${it}×"
        }
        val captureMode = CaptureModeResolver.resolve(candidate.captureMode, capabilities.rawAvailable)
        if (captureMode != candidate.captureMode) adjustments += "RAW is unavailable; using JPEG"

        if (errors.isNotEmpty()) {
            return CameraControlUpdate(candidate, accepted = false, messages = errors)
        }
        return CameraControlUpdate(
            state = candidate.copy(
                iso = iso,
                exposureTimeNs = exposureTime,
                exposureCompensationSteps = compensation,
                aeLocked = candidate.aeLocked && candidate.exposureMode == ExposureMode.AUTO,
                focusDistanceDiopters = focusDistance,
                awbLocked = candidate.awbLocked &&
                    candidate.whiteBalanceMode == WhiteBalanceMode.AUTO,
                zoomRatio = zoom,
                captureMode = captureMode,
            ),
            accepted = true,
            messages = adjustments,
        )
    }
}
