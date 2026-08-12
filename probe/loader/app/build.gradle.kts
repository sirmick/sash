plugins { id("com.android.application") }
android {
    namespace = "com.loader"
    compileSdk = 36
    defaultConfig { applicationId = "com.loader"; minSdk = 30; targetSdk = 36; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // Deliberately NOT depending on geckoview: every Gecko class comes from the
    // core package at runtime. That is the entire point of the experiment.
}
