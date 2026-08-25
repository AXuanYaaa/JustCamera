package top.r2dblog.justcamera.logging

import android.util.Log

enum class LogCategory(val tag: String) {
    CAMERA("JC-Camera"),
    CAPTURE("JC-Capture"),
    STORAGE("JC-Storage"),
    NATIVE("JC-Native"),
    PLUGIN("JC-Plugin"),
    PIPELINE("JC-Pipeline"),
    UI("JC-UI"),
}

object JcLog {
    fun debug(category: LogCategory, message: () -> String) {
        if (Log.isLoggable(category.tag, Log.DEBUG)) Log.d(category.tag, message())
    }

    fun info(category: LogCategory, message: String) = Log.i(category.tag, message)

    fun warn(category: LogCategory, message: String, cause: Throwable? = null) =
        Log.w(category.tag, message, cause)

    fun error(category: LogCategory, message: String, cause: Throwable? = null) =
        Log.e(category.tag, message, cause)
}
