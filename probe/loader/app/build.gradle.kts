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
        create("wikipedia") {
            dimension = "site"
            applicationId = "com.loader.wikipedia"
            resValue("string", "app_name", "Wikipedia")
            buildConfigField("String", "SITE", "\"https://en.m.wikipedia.org/\"")
        }
        create("news") {
            dimension = "site"
            applicationId = "com.loader.news"
            resValue("string", "app_name", "Hacker News")
            buildConfigField("String", "SITE", "\"https://news.ycombinator.com/\"")
        }
        // The one that needs hardware. Its manifest, and only its manifest,
        // asks for the camera.
        create("meet") {
            dimension = "site"
            applicationId = "com.loader.meet"
            resValue("string", "app_name", "Meet")
            buildConfigField("String", "SITE", "\"https://meet.jit.si/\"")
        }
    }
}
