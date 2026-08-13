plugins { id("com.android.application") }
android {
    namespace = "com.loader"
    compileSdk = 36
    defaultConfig { minSdk = 30; targetSdk = 36; versionCode = 1; versionName = "1" }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

    // Minting a site app is a build variant, not a project. Same code, different
    // package, different label, different permission set.
    // One flavour per catalogue entry. Declared by reading the catalogue rather
    // than written out here, so adding a site is a JSON file and a PNG.
    flavorDimensions += "site"
    productFlavors {
        val catalogue = rootProject.file("../../catalogue")
        catalogue.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }?.forEach { f ->
            val id = f.name.removeSuffix(".json")
            create(id) {
                dimension = "site"
                applicationId = "com.loader.$id"
                buildConfigField("boolean", "EMBEDDED", "false")
            }
        }
    }
}

dependencies {
    // Only the control carries an engine. The rest borrow one at runtime.
    // No engine here: every site app borrows one at runtime.
}
