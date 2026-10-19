package com.brycewg.asrkb.asr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TencentRoutingTest {
    @Test
    fun tencentIsAlwaysStreamingAcrossAllConstructionPaths() {
        val descriptor = AsrVendorRegistry.descriptorFor(AsrVendor.Tencent)
        assertTrue(AsrVendorCapability.StreamingRecognition in descriptor.capabilities)
        assertTrue(AsrVendorCapability.NativePushPcmInput in descriptor.capabilities)
        assertTrue(AsrVendorCapability.BackupCandidate in descriptor.capabilities)
        assertFalse(AsrVendorCapability.FileRecognition in descriptor.capabilities)
        assertFalse(AsrVendorCapability.LocalRecognition in descriptor.capabilities)
        AsrEngineConstructionSource.entries.forEach { source ->
            val direct = AsrDirectMicrophoneEngineFactory().resolvePlan(
                AsrVendor.Tencent,
                AsrEngineModePreferences(),
                source
            )
            assertSame(AsrDirectMicrophoneEngineConstructorKey.TencentStream, direct.constructorKey)
            assertSame(AsrResolvedEngineMode.DirectStream, direct.resolution.mode)
            assertNull(direct.fileRecognizerKey)
            AsrEngineInvocationMode.entries.filter { it.consumesPushedPcm }.forEach { mode ->
                val push = AsrPushPcmEngineFactory().resolvePlan(
                    AsrVendor.Tencent,
                    mode,
                    AsrEngineModePreferences(),
                    source
                )
                assertSame(AsrPushPcmEngineConstructorKey.TencentStream, push.constructorKey)
                assertSame(AsrResolvedEngineMode.PushPcmNativeStream, push.resolution.mode)
                assertNull(push.wrappedFileRecognizerKey)
                assertTrue(push.externalPcmMode)
            }
        }
    }

    @Test
    fun tencentAvailabilityUsesItsOwnCredentials() {
        val checks = AsrOnlineConfigurationChecks(
            hasSfKeys = { true },
            hasVendorKeys = { false }
        )
        assertFalse(isOnlineAsrVendorConfigured(AsrVendor.Tencent, checks))
        assertTrue(
            isOnlineAsrVendorConfigured(
                AsrVendor.Tencent,
                checks.copy(hasVendorKeys = { it == AsrVendor.Tencent })
            )
        )
    }
}
