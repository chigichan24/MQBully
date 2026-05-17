plugins {
    id("com.android.application")
}

android {
    namespace = "com.chigichan24.messagequeuebully"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.chigichan24.messagequeuebully"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
