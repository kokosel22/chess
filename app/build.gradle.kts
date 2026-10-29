plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "ru.chesscoach.overlay"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.chesscoach.overlay"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    splits {
        abi { isEnable = true; reset(); include("arm64-v8a", "x86_64"); isUniversalApk = false }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libstockfish.so" } }
    testOptions { animationsDisabled = true; unitTests.isReturnDefaultValues = true }
    lint { abortOnError = true; disable += "HardcodedText" }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
