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
    // To verify Case 14 OOM under the new lock-free impl WITHOUT touching
    // adb am compat (which doesn't work on release builds), swap the two
    // blocks above for the CinnamonBun preview SDK and run assembleRelease:
    //
    //   compileSdk { version = preview("CinnamonBun") }
    //   targetSdk  { version = preview("CinnamonBun") }
    //
    // Requires "platforms;android-CinnamonBun" installed via sdkmanager
    // and `adb install -t` because preview-targeted APKs are marked testOnly.

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            // Reuse the debug keystore so we can sign without a custom keystore.
            // This is only for local verification of Android 17 compat-flag behavior,
            // not for distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
