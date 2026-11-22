package com.turnocontrol

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tripwires on the merged manifest Robolectric loads (see docs/PRIVACIDAD_RED_MLKIT_0.3.0.md). They are NOT evidence of
 * zero network traffic: that needs measurements on Android. They make two changes impossible to merge unnoticed:
 *  - requesting network permissions before the privacy prerequisites are met;
 *  - a dependency update that adds, removes or renames telemetry components (forces a fresh privacy review).
 */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [35])
class NetworkPrivacyTripwireTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Suppress("DEPRECATION")
    private val info = context.packageManager.getPackageInfo(context.packageName,
        PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS)

    @Test fun noNetworkPermissionIsRequested() {
        val requested = info.requestedPermissions.orEmpty().toSet()
        // Proof that this is the merged manifest: this permission is only added by manifest merging.
        assertTrue("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" in requested)
        // Enabling these requires the measured telemetry controls of PRIVACIDAD_RED_MLKIT_0.3.0.md §10 first.
        assertFalse("INTERNET pedido antes de cumplir los requisitos de privacidad", "android.permission.INTERNET" in requested)
        assertFalse("ACCESS_NETWORK_STATE pedido antes de cumplir los requisitos de privacidad", "android.permission.ACCESS_NETWORK_STATE" in requested)
    }

    @Test fun telemetryComponentInventoryIsUnchanged() {
        val components = (info.services.orEmpty().map { it.name } + info.receivers.orEmpty().map { it.name } + info.providers.orEmpty().map { it.name }).toSet()
        val telemetry = components.filter { it.contains("datatransport") || it.contains(".mlkit.") }.toSet()
        assertEquals(setOf(
            "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery",
            "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService",
            "com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver",
            "com.google.mlkit.common.internal.MlKitComponentDiscoveryService",
            "com.google.mlkit.common.internal.MlKitInitProvider",
        ), telemetry)
        // No component runs in an isolated process yet: the OCR isolation proof of concept is not implemented.
        assertTrue(info.services.orEmpty().none { (it.flags and android.content.pm.ServiceInfo.FLAG_ISOLATED_PROCESS) != 0 })
    }
}
