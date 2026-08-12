plugins { id("com.android.application") }
android {
    namespace = "com.manager"
    compileSdk = 36
    defaultConfig { applicationId = "com.manager"; minSdk = 30; targetSdk = 36; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    androidResources { noCompress += "apk" }
}
