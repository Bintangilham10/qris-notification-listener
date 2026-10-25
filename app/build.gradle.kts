import java.util.Properties

plugins {
    id("com.android.application")
}

val releaseSigning = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun releaseSigningValue(name: String, environmentName: String): String? =
    releaseSigning.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: System.getenv(environmentName)?.takeIf { it.isNotBlank() }

android {
    namespace = "id.ciganstore.qrislistener"
    compileSdk = 36

    defaultConfig {
        applicationId = "id.ciganstore.qrislistener"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    buildFeatures {
        // BuildConfig.VERSION_NAME is sent as X-QRIS-App-Version and BuildConfig.DEBUG gates the
        // emulator/loopback HTTP exception. Never put the listener secret in BuildConfig.
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            releaseSigningValue("storeFile", "QRIS_RELEASE_STORE_FILE")?.let { storeFile = rootProject.file(it) }
            storePassword = releaseSigningValue("storePassword", "QRIS_RELEASE_STORE_PASSWORD")
            keyAlias = releaseSigningValue("keyAlias", "QRIS_RELEASE_KEY_ALIAS")
            keyPassword = releaseSigningValue("keyPassword", "QRIS_RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    testImplementation("junit:junit:4.13.2")
}
