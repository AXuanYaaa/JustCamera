package top.r2dblog.justcamera

import android.app.Application
import top.r2dblog.justcamera.settings.AppLanguageManager
import top.r2dblog.justcamera.settings.AppSettingsRepository

class JustCameraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguageManager.apply(AppSettingsRepository(this).language())
    }
}
