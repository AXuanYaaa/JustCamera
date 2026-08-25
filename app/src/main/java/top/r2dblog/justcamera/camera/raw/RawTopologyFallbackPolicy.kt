package top.r2dblog.justcamera.camera.raw

enum class RawTopologyFailure {
    CONFIGURATION_REJECTED,
    OUTPUT_COMBINATION_REJECTED,
    TRANSIENT_CAMERA_ACCESS,
    LIFECYCLE_INTERRUPTION,
}

/** RAW fallback is scoped to one selected-camera tenure, not the whole process. */
class RawTopologyFallbackPolicy {
    private var selectedCameraId: String? = null
    private var rejectedForSelection = false

    fun select(cameraId: String) {
        if (selectedCameraId != cameraId) {
            selectedCameraId = cameraId
            rejectedForSelection = false
        }
    }

    fun record(cameraId: String, failure: RawTopologyFailure): Boolean {
        if (cameraId != selectedCameraId) return false
        if (failure == RawTopologyFailure.CONFIGURATION_REJECTED ||
            failure == RawTopologyFailure.OUTPUT_COMBINATION_REJECTED
        ) {
            rejectedForSelection = true
        }
        return rejectedForSelection
    }

    fun isDisabled(cameraId: String): Boolean =
        cameraId == selectedCameraId && rejectedForSelection
}
