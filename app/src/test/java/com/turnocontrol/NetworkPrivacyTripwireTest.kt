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
        // No production component runs in an isolated process: the OCR proof of concept lives only in the verification
        // build, and ML Kit could not run there on an emulator (phase 5D.1).
        assertTrue(info.services.orEmpty().none { (it.flags and android.content.pm.ServiceInfo.FLAG_ISOLATED_PROCESS) != 0 })
    }

    @Test fun productionManifestHasNoNetworkConfigurationOrExtraProcesses() {
        val main = java.io.File("src/main/AndroidManifest.xml").readText()
        listOf("networkSecurityConfig", "usesCleartextTraffic", "android:process", "isolatedProcess").forEach { assertFalse(it, main.contains(it)) }
        assertTrue(main.contains("android.permission.INTERNET\" tools:node=\"remove\"") && main.contains("android.permission.ACCESS_NETWORK_STATE\" tools:node=\"remove\""))
        assertTrue(info.services.orEmpty().none { it.processName != null && it.processName != context.packageName })
    }

    @Test fun isolatedOcrProofOfConceptStaysInTheVerificationBuildWithoutNetwork() {
        val verification = java.io.File("src/verification/AndroidManifest.xml").readText()
        assertTrue(verification.contains("android:name=\"com.turnocontrol.ocr.IsolatedOcrService\""))
        assertTrue(verification.contains("android:isolatedProcess=\"true\"") && verification.contains("android:exported=\"false\""))
        assertFalse(verification.contains("uses-permission"))
        assertTrue(info.services.orEmpty().none { it.name == "com.turnocontrol.ocr.IsolatedOcrService" }) // Absent from the debug/release build.
    }

    // Phase 4B.5 network experiments (docs/PRIVACIDAD_RED_MLKIT_0.3.0.md §4) must stay confined to their own two
    // disposable build types, never leak into debug, release or the default verification build, and only ever add
    // permissions through their own source-set overlay, never by editing build.gradle.kts' shared buildTypes block
    // or app/src/main or app/src/verification.
    @Test fun networkExperimentsOnlyAddPermissionsInTheirOwnOverlayAndNeverTouchSharedManifests() {
        val gradle = java.io.File("build.gradle.kts").readText()
        listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "uses-permission").forEach {
            assertFalse("No debe declararse un permiso directamente en build.gradle.kts: $it", gradle.contains(it))
        }
        val baseline = java.io.File("src/networkBaselineVerification/AndroidManifest.xml").readText()
        val strategyB = java.io.File("src/networkStrategyBVerification/AndroidManifest.xml").readText()
        listOf(baseline, strategyB).forEach {
            assertTrue(it.contains("android.permission.INTERNET") && it.contains("android.permission.ACCESS_NETWORK_STATE"))
        }
        // Only strategy B removes the telemetry scheduling/sending components; the baseline leaves them untouched.
        assertFalse(baseline.contains("tools:node=\"remove\""))
        listOf("TransportBackendDiscovery", "JobInfoSchedulerService", "AlarmManagerSchedulerBroadcastReceiver").forEach {
            assertTrue(it, strategyB.contains(it) && strategyB.contains("tools:node=\"remove\""))
        }
        // The merged manifest Robolectric actually loads (testBuildType = "verification") is never one of these.
        assertFalse("android.permission.INTERNET" in info.requestedPermissions.orEmpty())
    }
}
