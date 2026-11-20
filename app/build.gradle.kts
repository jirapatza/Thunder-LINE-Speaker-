plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.thunder.linespeaker"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.thunder.linespeaker"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = providers.environmentVariable("ANDROID_VERSION_NAME").orNull ?: "0.11-dev"
        // Explicit CI variables; keep Firebase disabled in unconfigured builds.
        resValue("string","firebase_android_app_id",
            providers.environmentVariable("FIREBASE_ANDROID_APP_ID").orNull ?: "")
        resValue("string","firebase_api_key",
            providers.environmentVariable("FIREBASE_API_KEY").orNull ?: "")
        resValue("string","firebase_project_id",
            providers.environmentVariable("FIREBASE_PROJECT_ID").orNull ?: "")
        resValue("string","firebase_sender_id",
            providers.environmentVariable("FIREBASE_SENDER_ID").orNull ?: "")

    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Stable signing is only enabled for explicitly configured release builds.
    // Do not check a keystore or passwords into this public repository.
    val signingPath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
    if (!signingPath.isNullOrBlank()) {
        signingConfigs {
            create("production") {
                storeFile = file(signingPath)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("production")
            }
        }
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("androidx.work:work-runtime:2.10.5")
    testImplementation("junit:junit:4.13.2")
}
