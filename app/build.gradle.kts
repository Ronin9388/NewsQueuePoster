plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.nqp"
    compileSdk = 34
    defaultConfig { applicationId = "com.nqp.newsqueueposter"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "1.5.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("androidx.work:work-runtime-ktx:2.9.0"); implementation("androidx.core:core-ktx:1.13.1") }
