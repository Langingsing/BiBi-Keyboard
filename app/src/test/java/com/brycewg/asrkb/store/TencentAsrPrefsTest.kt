package com.brycewg.asrkb.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.asr.AsrVendor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TencentAsrPrefsTest {
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        prefs = Prefs(context)
    }

    @Test
    fun requiresEveryCredentialAndHasNoEmbeddedCredentials() {
        assertEquals("", prefs.tencentAppId)
        assertEquals("", prefs.tencentSecretId)
        assertEquals("", prefs.tencentSecretKey)
        assertEquals("16k_zh", prefs.tencentEngineModelType)
        for (mask in 0..7) {
            prefs.tencentAppId = if (mask and 1 != 0) "123456" else "  "
            prefs.tencentSecretId = if (mask and 2 != 0) "test-secret-id" else "  "
            prefs.tencentSecretKey = if (mask and 4 != 0) "test-secret-key" else "  "
            assertEquals("credential mask $mask", mask == 7, prefs.hasVendorKeys(AsrVendor.Tencent))
        }
    }

    @Test
    fun modelNormalizationAcceptsOnly16kModelsIncludingTraditionalAndVersionedNames() {
        listOf("16k_zh", "16k_zh-TW", "16k_en", "16k_zh_en_2.0").forEach { model ->
            assertTrue(Prefs.isTencentEngineModelTypeSupported(model))
            prefs.tencentEngineModelType = " $model "
            assertEquals(model, prefs.tencentEngineModelType)
        }
        listOf("", " ", "8k_zh", "16k_", "16k_zh&voice_format=4", "16k_zh hello").forEach { model ->
            assertFalse(Prefs.isTencentEngineModelTypeSupported(model))
            prefs.tencentEngineModelType = model
            assertEquals(Prefs.DEFAULT_TENCENT_ENGINE_MODEL_TYPE, prefs.tencentEngineModelType)
        }
    }

    @Test
    fun schemaIncludesAllCredentialsAndDefaultModel() {
        val credentials = PrefsAsrVendorFields.requiredCredentialFields(AsrVendor.Tencent)
        assertEquals(
            listOf(KEY_TENCENT_APP_ID, KEY_TENCENT_SECRET_ID, KEY_TENCENT_SECRET_KEY),
            credentials.map { it.key }
        )
        val models = PrefsAsrVendorFields.fieldsByRole(AsrVendor.Tencent, VendorFieldRole.Model)
        assertEquals(KEY_TENCENT_ENGINE_MODEL_TYPE, models.single().key)
        assertEquals(Prefs.DEFAULT_TENCENT_ENGINE_MODEL_TYPE, models.single().default)
        assertTrue(PrefsAsrVendorFields.backupFields.containsAll(credentials + models))
    }

    @Test
    fun settingsExportAndImportRoundTripsTencentConfiguration() {
        prefs.asrVendor = AsrVendor.Tencent
        prefs.tencentAppId = "123456"
        prefs.tencentSecretId = "test-secret-id"
        prefs.tencentSecretKey = "test-secret-key"
        prefs.tencentEngineModelType = "16k_zh-TW"

        val exported = prefs.exportJsonString()
        val json = JSONObject(exported)
        assertEquals("123456", json.getString(KEY_TENCENT_APP_ID))
        assertEquals("test-secret-id", json.getString(KEY_TENCENT_SECRET_ID))
        assertEquals("test-secret-key", json.getString(KEY_TENCENT_SECRET_KEY))
        assertEquals("16k_zh-TW", json.getString(KEY_TENCENT_ENGINE_MODEL_TYPE))

        prefs.tencentAppId = ""
        prefs.tencentSecretId = ""
        prefs.tencentSecretKey = ""
        prefs.tencentEngineModelType = "16k_en"
        assertTrue(prefs.importJsonString(exported))
        assertEquals(AsrVendor.Tencent, prefs.asrVendor)
        assertTrue(prefs.hasTencentKeys())
        assertEquals("123456", prefs.tencentAppId)
        assertEquals("test-secret-id", prefs.tencentSecretId)
        assertEquals("test-secret-key", prefs.tencentSecretKey)
        assertEquals("16k_zh-TW", prefs.tencentEngineModelType)
    }

    @Test
    fun importNormalizesIncompatibleModelAndPreservesOmittedCredentials() {
        prefs.tencentAppId = "123456"
        prefs.tencentSecretId = "test-secret-id"
        prefs.tencentSecretKey = "test-secret-key"
        assertTrue(prefs.importJsonString("""{"tencent_engine_model_type":"8k_zh"}"""))
        assertEquals("16k_zh", prefs.tencentEngineModelType)
        assertEquals("16k_zh", prefs.getPrefString(KEY_TENCENT_ENGINE_MODEL_TYPE))
        assertTrue(prefs.hasTencentKeys())
    }
}
