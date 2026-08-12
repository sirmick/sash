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
        create("alpha") {
            dimension = "site"
            applicationId = "com.loader.alpha"
            resValue("string", "app_name", "Alpha")
            buildConfigField("String", "SITE", "\"https://example.com/\"")
        }
        create("beta") {
            dimension = "site"
            applicationId = "com.loader.beta"
            resValue("string", "app_name", "Beta")
            buildConfigField("String", "SITE", "\"https://example.org/\"")
        }
    }
}
