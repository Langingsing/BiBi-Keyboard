package com.brycewg.asrkb.asr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.store.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TencentRecordedAudioRouteTest {
    @Test
    fun recordedAudioDoesNotFallThroughToNonexistentFileRecognizer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(context)
        prefs.tencentAppId = "12345678"
        prefs.tencentSecretId = "fake-secret-id"
        prefs.tencentSecretKey = "fake-secret-key"
        prefs.backupAsrEnabled = false

        val route = AsrRecordedAudioRouteResolver.resolve(context, prefs, AsrVendor.Tencent)

        assertSame(AsrRecordedAudioRouteKind.Unsupported, route.kind)
        assertFalse(route.canContinue)
        assertEquals(AsrRecordedAudioRouteResolver.REASON_UNSUPPORTED_NO_FILE_FALLBACK, route.reasonCode)
        assertSame(AsrVendor.Tencent, route.vendor)
    }

    @Test
    fun recordedAudioNeverStartsTencentAsBackupButLiveRecordingStillCan() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(context)
        prefs.tencentAppId = "12345678"
        prefs.tencentSecretId = "fake-secret-id"
        prefs.tencentSecretKey = "fake-secret-key"
        prefs.backupAsrEnabled = true
        prefs.backupAsrVendor = AsrVendor.Tencent

        val route = AsrRecordedAudioRouteResolver.resolve(context, prefs, AsrVendor.SiliconFlow)

        assertSame(AsrBackupPolicyDecision.UsePrimaryOnly, route.backupPolicy)
        assertSame(
            AsrBackupPolicyDecision.UseParallel,
            resolveBackupAsrDecision(context, prefs, AsrVendor.SiliconFlow, AsrVendor.Tencent)
        )
    }
}
