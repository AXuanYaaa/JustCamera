package top.r2dblog.justcamera.camera.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraStateReducerTest {
    @Test
    fun followsTheValidOpenPreviewCaptureLifecycle() {
        var state: CameraState = CameraState.Closed
        state = CameraStateReducer.reduce(state, CameraEvent.Open("0"))
        state = CameraStateReducer.reduce(state, CameraEvent.DeviceOpened("0"))
        state = CameraStateReducer.reduce(state, CameraEvent.Configure("0"))
        state = CameraStateReducer.reduce(state, CameraEvent.PreviewStarted("0"))
        state = CameraStateReducer.reduce(state, CameraEvent.CaptureStarted("0"))
        assertEquals(CameraState.Capturing("0"), state)
        state = CameraStateReducer.reduce(state, CameraEvent.PreviewStarted("0"))
        assertEquals(CameraState.Previewing("0"), state)
    }

    @Test
    fun rejectsCaptureWhenPreviewHasNotStarted() {
        val state = CameraStateReducer.reduce(CameraState.Closed, CameraEvent.CaptureStarted("0"))
        assertEquals(CameraState.Closed, state)
    }
}
