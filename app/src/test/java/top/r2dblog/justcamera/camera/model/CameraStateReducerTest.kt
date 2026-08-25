package top.r2dblog.justcamera.camera.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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

    @Test
    fun recoverableSessionFailureLeavesAnErrorReadyStateThatCanReopen() {
        val failure = CameraError(
            CameraErrorCode.SESSION_CONFIGURATION_FAILED,
            "Session configuration failed",
            recoverable = true,
        )
        val failed = CameraStateReducer.reduce(
            CameraState.Configuring("0"),
            CameraEvent.Failed(failure),
        )
        assertEquals(CameraState.Error(failure), failed)

        val reopening = CameraStateReducer.reduce(failed, CameraEvent.Open("0"))
        assertEquals(CameraState.Opening("0"), reopening)
    }

    @Test
    fun nonRecoverableFailureCannotReopenWithoutAnExplicitCloseOrCameraChange() {
        val failure = CameraError(
            CameraErrorCode.UNSUPPORTED_CAPABILITY,
            "JPEG is unsupported",
            recoverable = false,
        )
        val failed: CameraState = CameraState.Error(failure)
        assertSame(failed, CameraStateReducer.reduce(failed, CameraEvent.Open("0")))
    }

    @Test
    fun closeTransitionIsIdempotent() {
        val closed = CameraState.Closed
        assertSame(closed, CameraStateReducer.reduce(closed, CameraEvent.Close))
    }
}
