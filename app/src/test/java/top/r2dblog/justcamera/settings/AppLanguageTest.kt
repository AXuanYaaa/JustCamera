package top.r2dblog.justcamera.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import top.r2dblog.justcamera.R

class AppLanguageTest {
    @Test
    fun firstInstallDefaultsToSimplifiedChinese() {
        assertEquals(AppLanguage.ZH_CN, AppLanguage.fromStoredValue(null))
        assertEquals(AppLanguage.ZH_CN, AppLanguage.DEFAULT)
    }

    @Test
    fun savedEnglishPreferenceIsRestored() {
        assertEquals(AppLanguage.EN, AppLanguage.fromStoredValue("en"))
    }

    @Test
    fun invalidPreferenceFallsBackToSimplifiedChinese() {
        assertEquals(AppLanguage.ZH_CN, AppLanguage.fromStoredValue("unsupported"))
    }

    @Test
    fun languagesMapToStableTagsAndResources() {
        assertEquals("zh-CN", AppLanguage.ZH_CN.languageTag)
        assertEquals(R.string.language_simplified_chinese, AppLanguage.ZH_CN.displayNameResource)
        assertEquals("en", AppLanguage.EN.languageTag)
        assertEquals(R.string.language_english, AppLanguage.EN.displayNameResource)
    }
}
