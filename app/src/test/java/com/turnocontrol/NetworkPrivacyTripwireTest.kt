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
        // Exact allowlist, not just a denylist of INTERNET/ACCESS_NETWORK_STATE: debug, release and verification
        // each request exactly this one permission (confirmed by reading each build type's own merged manifest
        // under app/build/intermediates/merged_manifest/<variant>/.../AndroidManifest.xml), which manifest merging
        // adds automatically and which carries no privacy risk by itself. Any other permission added to any of
        // these three build types - network or not - must fail this test until it is reviewed and added here.
        assertEquals(setOf("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"), requested)
    }

    // Unit tests run once per build type (testDebugUnitTest, testReleaseUnitTest, testVerificationUnitTest, ...),
    // each loading that build type's own merged manifest into `info`; unlike androidTest, testBuildType has no
    // effect here. The isolated OCR proof of concept is only ever declared in the "verification" build type's own
    // manifest overlay (app/src/verification/AndroidManifest.xml), so it is the only packageName allowed to see it.
    private val isVerificationBuildType = context.packageName == "com.turnocontrol.verification"
    private val isolatedOcrServiceName = "com.turnocontrol.ocr.IsolatedOcrService"

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
        // No production component runs in an isolated process. The one exact, named exception is the OCR proof of
        // concept, which only exists in the "verification" build type's own manifest overlay (ML Kit could not run
        // there on an emulator anyway, phase 5D.1); every other build type must have zero isolated services.
        val isolated = info.services.orEmpty().filter { (it.flags and android.content.pm.ServiceInfo.FLAG_ISOLATED_PROCESS) != 0 }
        if (isVerificationBuildType) assertEquals(setOf(isolatedOcrServiceName), isolated.map { it.name }.toSet())
        else assertTrue(isolated.isEmpty())
    }

    @Test fun productionManifestHasNoNetworkConfigurationOrExtraProcesses() {
        val main = java.io.File("src/main/AndroidManifest.xml").readText()
        listOf("networkSecurityConfig", "usesCleartextTraffic", "android:process", "isolatedProcess").forEach { assertFalse(it, main.contains(it)) }
        assertTrue(main.contains("android.permission.INTERNET\" tools:node=\"remove\"") && main.contains("android.permission.ACCESS_NETWORK_STATE\" tools:node=\"remove\""))
        // Exact, named exception: only the verification build type's own IsolatedOcrService may run in an extra
        // process (":ocr"); no other extra process is allowed in any build type, including verification.
        val extraProcesses = info.services.orEmpty().filter { it.processName != null && it.processName != context.packageName }
        if (isVerificationBuildType) assertEquals(setOf(isolatedOcrServiceName), extraProcesses.map { it.name }.toSet())
        else assertTrue(extraProcesses.isEmpty())
    }

    @Test fun isolatedOcrProofOfConceptStaysInTheVerificationBuildWithoutNetwork() {
        val verification = java.io.File("src/verification/AndroidManifest.xml").readText()
        assertTrue(verification.contains("android:name=\"com.turnocontrol.ocr.IsolatedOcrService\""))
        assertTrue(verification.contains("android:isolatedProcess=\"true\"") && verification.contains("android:exported=\"false\""))
        assertFalse(verification.contains("uses-permission"))
        // Present only when this build type IS verification; absent from every other build type (debug, release, ...).
        val present = info.services.orEmpty().any { it.name == isolatedOcrServiceName }
        if (isVerificationBuildType) assertTrue("IsolatedOcrService debe declararse en la build verification", present)
        else assertFalse("IsolatedOcrService no debe aparecer fuera de la build verification", present)
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
        // This class runs once per build type (testDebugUnitTest, testReleaseUnitTest, testVerificationUnitTest), so
        // `info` here is whichever of those three is currently executing, never a network-experiment variant's: the
        // latter only exists with -Pturnocontrol.netExperiment and is never part of an unqualified `./gradlew test`.
        // All three always-present build types agree on these permissions, which is what this assertion checks.
        assertFalse("android.permission.INTERNET" in info.requestedPermissions.orEmpty())
    }

    // The two network-experiment build types and testBuildType's override must only exist when the caller passes
    // -Pturnocontrol.netExperiment=<name> (docs/QA_DISPOSITIVO_0.3.0.md §7.1). Without that property, `./gradlew
    // assemble` must never produce an INTERNET-enabled APK and `./gradlew test` must never run this very file
    // against one: this is a file-level guard against someone replacing the gate with a hardcoded value by mistake.
    @Test fun networkExperimentBuildTypesStayGatedBehindAProperty() {
        val gradle = java.io.File("build.gradle.kts").readText()
        assertTrue(gradle.contains("testBuildType = netExperiment ?: \"verification\""))
        assertTrue(gradle.contains("if (netExperiment != null) {"))
        assertFalse("testBuildType no debe fijarse directamente a una variante de experimento",
            Regex("testBuildType\\s*=\\s*\"network").containsMatchIn(gradle))
    }
}
