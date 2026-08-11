// Java, not Kotlin. K2's type checker crashes outright on GeckoView's annotated
// API — "Internal compiler error … Exception in type checkers" — and the cheap
// way past a toolchain bug is to not use the toolchain. Revisit when the app is
// worth the fight.
plugins {
    id("com.android.application")
}

android {
    namespace = "com.pane"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pane"
        // GeckoView's floor, not ours.
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // geckoview-omni bundles the native libraries; the plain artifact expects
    // them to be supplied.
    implementation("org.mozilla.geckoview:geckoview-omni:153.0.20260803132010")
}
