plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeystore = providers.environmentVariable("MEDIAREMOTE_KEYSTORE").orNull
val releaseKeystorePassword = providers.environmentVariable("MEDIAREMOTE_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("MEDIAREMOTE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("MEDIAREMOTE_KEY_PASSWORD").orNull

android {
    namespace = "dev.mediaremote"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.mediaremote"
        minSdk = 28
        targetSdk = 37
        versionCode = providers.gradleProperty("buildNumber").orNull?.toIntOrNull() ?: 20
        versionName = "0.6.16"
    }

    signingConfigs {
        create("release") {
            if (!releaseKeystore.isNullOrBlank()) {
                storeFile = file(releaseKeystore)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            isDebuggable = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)

    implementation("androidx.core:core:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.9.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
