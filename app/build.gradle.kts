plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.thunder.linespeaker"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.thunder.linespeaker"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5-dev"
        // GitHub Actions supplies Firebase app identifiers at release time.
        // Empty values keep development builds functional but push DISABLED.
        resValue("string", "firebase_android_app_id",
            providers.environmentVariable("FIREBASE_ANDROID_APP_ID").orNull ?: "")
        resValue("string", "firebase_api_key",
            providers.environmentVariable("FIREBASE_API_KEY").orNull ?: "")
        resValue("string", "firebase_project_id",
            providers.environmentVariable("FIREBASE_PROJECT_ID").orNull ?: "")
        resValue("string", "firebase_sender_id",
            providers.environmentVariable("FIREBASE_SENDER_ID").orNull ?: "")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
    implementation("com.google.firebase:firebase-messaging")
    testImplementation("junit:junit:4.13.2")
}
