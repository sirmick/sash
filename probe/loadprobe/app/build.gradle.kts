plugins { id("com.android.application") }
android {
    namespace = "com.probe"
    compileSdk = 36
    defaultConfig { applicationId = "com.probe"; minSdk = 30; targetSdk = 36; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
