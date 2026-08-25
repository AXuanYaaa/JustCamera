package top.r2dblog.justcamera.nativecore

object NativeCore {
    init {
        System.loadLibrary("justcamera_native")
    }

    fun version(): String = nativeVersion()

    private external fun nativeVersion(): String
}
