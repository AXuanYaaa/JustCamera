package top.r2dblog.justcamera.filter.processing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import top.r2dblog.justcamera.filter.api.FilterExecutionContext
import top.r2dblog.justcamera.filter.builtin.ExposureFilter
import top.r2dblog.justcamera.filter.model.FilterExecutionMode
import top.r2dblog.justcamera.filter.model.FilterParameterValue
import top.r2dblog.justcamera.filter.model.FilterParameters
import top.r2dblog.justcamera.filter.processing.backend.JniNativeProcessingBridge
import top.r2dblog.justcamera.filter.processing.backend.NativeOperationProvider
import top.r2dblog.justcamera.imaging.frame.RgbChannelLayout
import top.r2dblog.justcamera.imaging.frame.RgbFloatFrame

@RunWith(AndroidJUnit4::class)
@Ignore("Manual PH4 profiling diagnostic; timings are not correctness assertions")
class NativeProcessingBenchmarkTest {
    @Test
    fun compareKotlinReferenceAndNativeScalar() = runBlocking {
        val filter = ExposureFilter()
        val parameters = FilterParameters(
            mapOf("exposure" to FilterParameterValue.FloatValue(0.5f)),
        ).validateAndClamp(filter.descriptor).parameters
        val context = FilterExecutionContext(FilterExecutionMode.FINAL_CAPTURE)
        listOf(640 to 480, 1920 to 1080, 4000 to 3000).forEach { (width, height) ->
            val frame = RgbFloatFrame.create(
                width,
                height,
                RgbChannelLayout.RGB,
                FloatArray(width * height * 3) { 0.18f },
            )
            val kotlinStart = System.nanoTime()
            filter.process(frame, parameters, context)
            val kotlinMillis = (System.nanoTime() - kotlinStart) / 1_000_000.0
            val nativeStart = System.nanoTime()
            JniNativeProcessingBridge.process(
                frame,
                listOf((filter as NativeOperationProvider).nativeOperation(parameters)),
            )
            val nativeMillis = (System.nanoTime() - nativeStart) / 1_000_000.0
            Log.i("JC-NativeBenchmark", "${width}x$height Kotlin=$kotlinMillis ms native=$nativeMillis ms")
        }
    }
}
