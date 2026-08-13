plugins { id("com.android.application") }
android {
    namespace = "com.wvprobe"
    compileSdk = 36
    defaultConfig { applicationId = "com.wvprobe"; minSdk = 30; targetSdk = 36; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
