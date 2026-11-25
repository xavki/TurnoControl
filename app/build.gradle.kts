import java.util.Properties

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.compose)
    alias(libs.plugins.kapt)
    alias(libs.plugins.hilt)
}
android {
    namespace = "com.turnocontrol"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.turnocontrol"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        javaCompileOptions { annotationProcessorOptions { arguments["room.schemaLocation"] = "$projectDir/schemas" } }
    }
    buildFeatures { compose = true }
    // Phase 4B.5 network-experiment build types (docs/QA_DISPOSITIVO_0.3.0.md §7.1) exist ONLY when this property is
    // passed, e.g. `-Pturnocontrol.netExperiment=networkBaselineVerification`. Without it, `./gradlew assemble` and
    // `./gradlew test` never see them: no INTERNET-enabled APK is ever part of a default build, and
    // NetworkPrivacyTripwireTest never runs against one. Omitting the property (the default for every normal build)
    // falls back to "verification" for testBuildType, exactly as before this mechanism existed.
    val netExperiment = (project.findProperty("turnocontrol.netExperiment") as String?)
        ?.takeIf { it == "networkBaselineVerification" || it == "networkStrategyBVerification" }
    // Release signing reads an untracked properties file (release-signing.properties at the repo root, or the path in
    // -Pturnocontrol.signingProperties): storeFile, storePassword, keyAlias, keyPassword. Since 0.3.0 it points at the
    // dedicated release key kept outside the repo (docs/LISTA_PUBLICACION.md §5); later releases must use the same key so
    // `install -r` keeps the user's data. Without the file, release stays unsigned. The key is only read, never changed.
    val signingFile = rootProject.file(project.findProperty("turnocontrol.signingProperties") as String? ?: "release-signing.properties")
    val releaseSigning = if (signingFile.isFile) Properties().apply { signingFile.inputStream().use { load(it) } } else null
    if (releaseSigning != null) signingConfigs.create("release") {
        storeFile = file(releaseSigning.getProperty("storeFile"))
        storePassword = releaseSigning.getProperty("storePassword")
        keyAlias = releaseSigning.getProperty("keyAlias")
        keyPassword = releaseSigning.getProperty("keyPassword")
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
        }
        create("verification") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".verification"
            matchingFallbacks += "debug"
        }
        if (netExperiment != null) {
            // Disposable, emulator-only. Each re-adds INTERNET and ACCESS_NETWORK_STATE only in its own source-set
            // overlay (app/src/<name>/AndroidManifest.xml), never here.
            create("networkBaselineVerification") {
                initWith(getByName("verification"))
                applicationIdSuffix = ".netbaseline"
                matchingFallbacks += "debug"
            }
            create("networkStrategyBVerification") {
                initWith(getByName("verification"))
                applicationIdSuffix = ".netstrategyb"
                matchingFallbacks += "debug"
            }
        }
    }
    testBuildType = netExperiment ?: "verification"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["androidTest"].assets.srcDir("schemas")
    if (netExperiment != null) {
        // The network-experiment build types reuse "verification"'s Kotlin fixtures (CalendarTestDatabase, the
        // isolated OCR proof of concept, etc.) so the existing androidTest suite compiles against them too. Their
        // own AndroidManifest.xml overlay (not "verification"'s) still decides what each one actually declares.
        sourceSets["networkBaselineVerification"].java.srcDir("src/verification/java")
        sourceSets["networkBaselineVerification"].res.srcDir("src/verification/res")
        sourceSets["networkStrategyBVerification"].java.srcDir("src/verification/java")
        sourceSets["networkStrategyBVerification"].res.srcDir("src/verification/res")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2") }
    }
}
kapt { correctErrorTypes = true }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Compose UI tests on the JVM (Robolectric); test-only, no network capability.
    testImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // Aligned with the instrumented-test versions already in the local cache (no new downloads).
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.6.1")
    androidTestImplementation("androidx.room:room-testing:2.7.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // "verification" does not inherit "debug"'s dependency configurations just because it initWith(debug)'s build
    // type settings: AGP keeps each build type's own *Implementation config separate. AccountUiTest/LinkingUiTest
    // (Robolectric, createComposeRule()) need the test-only ComponentActivity this artifact adds to the manifest;
    // without it, testVerificationUnitTest fails those tests with RoboMonitoringInstrumentation RuntimeExceptions.
    add("verificationImplementation", "androidx.compose.ui:ui-test-manifest")
}
// AccountUiTest/LinkingUiTest use createComposeRule() (Robolectric), which needs the test-only ComponentActivity
// that androidx.compose.ui:ui-test-manifest adds to the merged manifest. That artifact is wired as debugImplementation
// and verificationImplementation above (both disposable/never-released build types), deliberately NOT as
// releaseImplementation: unlike "debug" and "verification", a "release" build type can actually become a real
// release APK, and shipping test scaffolding into that manifest would be a regression this project does not accept
// just to make an unqualified `./gradlew test` run go green. So these Compose UI classes are excluded from
// testReleaseUnitTest specifically; they still run under testDebugUnitTest and testVerificationUnitTest.
tasks.withType<Test>().configureEach {
    if (name == "testReleaseUnitTest") {
        filter {
            excludeTestsMatching("com.turnocontrol.ui.AccountUiTest")
            excludeTestsMatching("com.turnocontrol.ui.LinkingUiTest")
            // Same reason (createComposeRule/createAndroidComposeRule need that test-only activity).
            excludeTestsMatching("com.turnocontrol.ui.SummaryCardsTest")
            excludeTestsMatching("com.turnocontrol.ui.AppNavigationTest")
            excludeTestsMatching("com.turnocontrol.ui.AccessibilityChecksTest")
            excludeTestsMatching("com.turnocontrol.ui.ConflictsUiTest")
        }
    }
}
