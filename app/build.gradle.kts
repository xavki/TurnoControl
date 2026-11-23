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
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        javaCompileOptions { annotationProcessorOptions { arguments["room.schemaLocation"] = "$projectDir/schemas" } }
    }
    buildFeatures { compose = true }
    buildTypes {
        create("verification") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".verification"
            matchingFallbacks += "debug"
        }
        // Disposable, emulator-only network experiments for phase 4B.5 (docs/PRIVACIDAD_RED_MLKIT_0.3.0.md §4). Never
        // the default build: debug, release and "verification" above stay network-free. Each re-adds INTERNET and
        // ACCESS_NETWORK_STATE only in its own source-set overlay (app/src/<name>/AndroidManifest.xml), never here.
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
    testBuildType = "verification"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["androidTest"].assets.srcDir("schemas")
    // The network-experiment build types reuse "verification"'s Kotlin fixtures (CalendarTestDatabase, the isolated
    // OCR proof of concept, etc.) so the existing androidTest suite compiles against them too; see testBuildType
    // switch instructions in docs/PRIVACIDAD_RED_MLKIT_0.3.0.md §4. Their own AndroidManifest.xml overlay (not
    // "verification"'s) still decides what each one actually declares.
    sourceSets["networkBaselineVerification"].java.srcDir("src/verification/java")
    sourceSets["networkBaselineVerification"].res.srcDir("src/verification/res")
    sourceSets["networkStrategyBVerification"].java.srcDir("src/verification/java")
    sourceSets["networkStrategyBVerification"].res.srcDir("src/verification/res")
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
}
