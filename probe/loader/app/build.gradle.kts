plugins { id("com.android.application") }
android {
    namespace = "com.loader"
    compileSdk = 36
    defaultConfig { minSdk = 30; targetSdk = 36; versionCode = 1; versionName = "1" }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

    // Minting a site app is a build variant, not a project. Same code, different
    // package, different label, different permission set.
    flavorDimensions += "site"
    productFlavors {
        // The control. Identical code; the engine is compiled in the ordinary
        // way instead of grafted from another package, so the only variable
        // left is where the engine came from.
        create("direct") {
            dimension = "site"
            applicationId = "com.loader.direct"
            resValue("string", "app_name", "Direct")
            buildConfigField("boolean", "EMBEDDED", "true")
        }
        create("wikipedia") {
            dimension = "site"
            applicationId = "com.loader.wikipedia"
            buildConfigField("boolean", "EMBEDDED", "false")
            resValue("string", "app_name", "Wikipedia")
        }
        create("news") {
            dimension = "site"
            applicationId = "com.loader.news"
            buildConfigField("boolean", "EMBEDDED", "false")
            resValue("string", "app_name", "Hacker News")
        }
        // The one that needs hardware. Its manifest, and only its manifest,
        // asks for the camera.
        create("meet") {
            dimension = "site"
            applicationId = "com.loader.meet"
            buildConfigField("boolean", "EMBEDDED", "false")
            resValue("string", "app_name", "Meet")
        }
    }
}

dependencies {
    // Only the control carries an engine. The rest borrow one at runtime.
    "directImplementation"("org.mozilla.geckoview:geckoview-omni:153.0.20260803132010")
}
