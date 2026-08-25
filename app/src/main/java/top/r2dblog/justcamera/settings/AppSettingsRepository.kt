package top.r2dblog.justcamera.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import top.r2dblog.justcamera.R

enum class AppLanguage(
    val storedValue: String,
    val languageTag: String,
    @StringRes val displayNameResource: Int,
) {
    ZH_CN("zh_cn", "zh-CN", R.string.language_simplified_chinese),
    EN("en", "en", R.string.language_english),
    ;

    fun localeList(): LocaleListCompat = LocaleListCompat.forLanguageTags(languageTag)

    companion object {
        val DEFAULT = ZH_CN

        fun fromStoredValue(value: String?): AppLanguage =
            entries.firstOrNull { it.storedValue == value } ?: DEFAULT
    }
}

class AppSettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun language(): AppLanguage = AppLanguage.fromStoredValue(
        preferences.getString(KEY_LANGUAGE, null),
    )

    fun setLanguage(language: AppLanguage) {
        preferences.edit().putString(KEY_LANGUAGE, language.storedValue).apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "justcamera_settings"
        private const val KEY_LANGUAGE = "application_language"
    }
}

object AppLanguageManager {
    fun apply(language: AppLanguage) {
        if (AppCompatDelegate.getApplicationLocales() != language.localeList()) {
            AppCompatDelegate.setApplicationLocales(language.localeList())
        }
    }
}
