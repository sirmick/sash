import RunBundletoolTask.Companion.aapt2
import com.android.SdkConstants
import org.gradle.kotlin.dsl.register

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kapt)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

android {
    dependenciesInfo {
        // Disables dependency metadata when building APKs and Bundles.
        includeInApk = false
        includeInBundle = false
    }

    namespace = "s1m.hwfido2provider"
    compileSdk = 36

    defaultConfig {
        applicationId = "s1m.hwfido2provider"
        minSdk = 34
        targetSdk = 36
        versionCode = 10
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            resValue("string", "app_name", "Passchain")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            resValue("string", "app_name", "DBG - Passchain")
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        dataBinding = true
        compose = true
    }

    // Required for indispensable.cosef
    packaging {
        resources.excludes.addAll(
            listOf(
                "org/bouncycastle/pqc/crypto/picnic/lowmcL5.bin.properties",
                "org/bouncycastle/pqc/crypto/picnic/lowmcL3.bin.properties",
                "org/bouncycastle/pqc/crypto/picnic/lowmcL1.bin.properties",
                "org/bouncycastle/x509/CertPathReviewerMessages_de.properties",
                "org/bouncycastle/x509/CertPathReviewerMessages.properties",
                "org/bouncycastle/pkix/CertPathReviewerMessages_de.properties",
                "org/bouncycastle/pkix/CertPathReviewerMessages.properties",
                "/META-INF/{AL2.0,LGPL2.1}",
                "win32-x86-64/attach_hotspot_windows.dll",
                "win32-x86/attach_hotspot_windows.dll",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/licenses/*"
            )
        )
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.androidx.credentials)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.indispensable.cosef)
    implementation(libs.play.services.fido.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

val buildTools = RunBundletoolTask.Companion.BuildTools(
    SdkConstants.CURRENT_BUILD_TOOLS_VERSION,
    SdkConstants.FD_BUILD_TOOLS
)

tasks.register<RunBundletoolTask>("reproduceUniversal") {
    group = "build"
    description = "Generate universal .apks from .aab with bundletool"
    dependsOn("bundleRelease")
    aabFile = project.rootDir.resolve("app/build/outputs/bundle/release/app-release.aab")
    universalApks = project.rootDir.resolve("universal.apks")
    aapt2 = project.aapt2(androidComponents, buildTools)
    signature.set(RunBundletoolTask.Signature.UnsignedOrDebug)
}

tasks.register<RunBundletoolTask>("bundletoolBuildApks") {
    group = "build"
    description = "Generate default and universal .apks from .aab with bundletool"

    val aabPath = System.getenv("AAB") ?: error("AAB not set")
    aabFile = project.rootDir.resolve(aabPath)
    universalApks = project.rootDir.resolve("universal.apks")
    defaultApks = project.rootDir.resolve("app.apks")
    aapt2 = project.aapt2(androidComponents, buildTools)

    val ks = System.getenv("KS") ?: error("KS not set")
    val ksPass = System.getenv("KS_PASS") ?: error("KS_PASS not set")
    val keyAlias = System.getenv("KEY_ALIAS") ?: error("KEY_ALIAS not set")

    signature.set(RunBundletoolTask.Signature.Signed(ks, ksPass, keyAlias))
}
