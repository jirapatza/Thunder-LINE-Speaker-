plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.thunder.linespeaker"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.thunder.linespeaker"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = providers.environmentVariable("ANDROID_VERSION_NAME").orNull ?: "0.8-dev"

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
    testImplementation("junit:junit:4.13.2")
}
