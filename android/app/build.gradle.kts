plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Número da versão vem do GitHub Actions (cada build = versão nova)
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val githubRepo = System.getenv("GITHUB_REPOSITORY") ?: ""
val keystoreFile = System.getenv("KEYSTORE_FILE")
// Servidor fixo (modelo revenda): escreva o endereço no arquivo android/servidor.txt
val fixedServer = rootProject.file("servidor.txt").takeIf { it.exists() }?.readText()?.trim().orEmpty()
val updateUrl = if (githubRepo.isNotEmpty())
    "https://github.com/$githubRepo/releases/latest/download/update.json" else ""

android {
    namespace = "com.gs.iptv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gs.iptv"
        minSdk = 23
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
        buildConfigField("String", "XTREAM_SERVER", "\"$fixedServer\"")
    }

    signingConfigs {
        create("release") {
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "gs"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystoreFile != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
}
