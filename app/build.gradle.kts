plugins {
    id("com.android.application")
}

android {
    namespace = "id.ciganstore.qrislistener"
    compileSdk = 36

    defaultConfig {
        applicationId = "id.ciganstore.qrislistener"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

