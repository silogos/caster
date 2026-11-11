import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The product has one version, in the VERSION file at the repository root
// (docs/development/release.md, "Versioning"); the Mac receiver carries the same.
val productVersion: String = rootDir.resolve("../../VERSION").readText().trim()

/**
 * Android's update order, derived from the version so it cannot be forgotten:
 * 0.2.0 → 200, 0.2.1 → 201, 1.0.0 → 10000. MINOR and PATCH stay below 100.
 */
fun versionCodeOf(version: String): Int {
    val parts = version.split(".").map { it.toIntOrNull() }
    require(parts.size == 3 && parts.all { it != null } && parts[1]!! < 100 && parts[2]!! < 100) {
        "VERSION holds '$version'; expected MAJOR.MINOR.PATCH with MINOR and PATCH below 100"
    }
    return parts[0]!! * 10_000 + parts[1]!! * 100 + parts[2]!!
}

android {
    namespace = "com.zerofriction.localcast"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zerofriction.localcast"
        minSdk = 29
        targetSdk = 36
        versionName = productVersion
        versionCode = versionCodeOf(productVersion)
    }

    buildTypes {
        release {
            // Shrinking/obfuscation is deliberately off until a release build actually matters.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Scan screen gates its debug-only manual payload input on BuildConfig.DEBUG.
        buildConfig = true
    }

    testOptions {
        // android.util.Log is a stub in JVM unit tests; pairing/signaling log via it.
        unitTests.isReturnDefaultValues = true
        // Robolectric Compose tests resolve strings/themes from the merged resources.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    // WebRTC media transport (Phase 5): prebuilt libwebrtc, org.webrtc API (ADR-001).
    implementation(libs.stream.webrtc.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
