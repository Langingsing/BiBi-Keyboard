package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.store.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AsrOnlineSettingsFieldsTest {
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        prefs = Prefs(context)
    }

    @Test
    fun tencentModelDraftRejectsIncompatibleModelsWithoutLosingLastValidValue() {
        val fields = AsrOnlineSettingsFields(prefs)
        assertEquals("16k_zh", fields.tencentEngineModelType)
        fields.updateTencentEngineModelType("16k_zh-TW")
        assertEquals("16k_zh-TW", prefs.tencentEngineModelType)

        fields.updateTencentEngineModelType("8k_zh")
        assertEquals("8k_zh", fields.tencentEngineModelType)
        assertEquals("16k_zh-TW", prefs.tencentEngineModelType)

        fields.refreshFromPrefs()
        assertEquals("16k_zh-TW", fields.tencentEngineModelType)
        fields.updateTencentEngineModelType("")
        assertEquals("", fields.tencentEngineModelType)
        assertEquals("16k_zh", prefs.tencentEngineModelType)
    }

    @Test
    fun refreshReloadsAllTencentFieldsAfterSettingsImport() {
        val fields = AsrOnlineSettingsFields(prefs)
        prefs.tencentAppId = "123456"
        prefs.tencentSecretId = "test-id"
        prefs.tencentSecretKey = "test-key"
        prefs.tencentEngineModelType = "16k_en"

        fields.refreshFromPrefs()

        assertEquals("123456", fields.tencentAppId)
        assertEquals("test-id", fields.tencentSecretId)
        assertEquals("test-key", fields.tencentSecretKey)
        assertEquals("16k_en", fields.tencentEngineModelType)
    }

    @Test
    fun constructorNormalizesAndPersistsCohereLanguage() {
        prefs.cohereAsrModel = Prefs.COHERE_ARABIC_ASR_MODEL
        prefs.cohereAsrLanguage = "zh"

        val fields = AsrOnlineSettingsFields(prefs)

        assertEquals("ar", fields.cohereLanguage)
        assertEquals("ar", prefs.cohereAsrLanguage)
    }

    @Test
    fun refreshNormalizesAndPersistsCohereLanguage() {
        val fields = AsrOnlineSettingsFields(prefs)
        prefs.cohereAsrModel = Prefs.COHERE_ARABIC_ASR_MODEL
        prefs.cohereAsrLanguage = "zh"

        fields.refreshFromPrefs()

        assertEquals("ar", fields.cohereLanguage)
        assertEquals("ar", prefs.cohereAsrLanguage)
    }

    @Test
    fun selectingCustomModelKeepsCurrentModelUntilDraftIsNonBlank() {
        prefs.cohereApiKey = "test-key"
        prefs.cohereAsrModel = Prefs.DEFAULT_COHERE_ASR_MODEL
        val fields = AsrOnlineSettingsFields(prefs)

        fields.showCohereCustomModelInput()
        fields.updateCohereCustomModelDraft("")

        assertEquals(Prefs.DEFAULT_COHERE_ASR_MODEL, fields.cohereModel)
        assertEquals(Prefs.DEFAULT_COHERE_ASR_MODEL, prefs.cohereAsrModel)
        assertTrue(prefs.hasCohereKeys())

        fields.updateCohereCustomModelDraft("custom-transcribe-model")

        assertEquals("custom-transcribe-model", fields.cohereModel)
        assertEquals("custom-transcribe-model", prefs.cohereAsrModel)
    }
}
