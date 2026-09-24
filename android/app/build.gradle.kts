import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.chaquo.python")
}

// Подпись. Ключ НИКОГДА не лежит в репозитории: путь и пароли приходят
// из окружения (в CI — из GitHub Secrets). Если их нет, релизная сборка
// подписывается временным ключом, который CI создаёт на месте; такой APK
// ставится, но следующий такой же APK поверх него уже не встанет.
val noxKeystorePath: String? = System.getenv("NOX_KEYSTORE_PATH")
val noxKeystorePassword: String? = System.getenv("NOX_KEYSTORE_PASSWORD")
val noxKeyAlias: String? = System.getenv("NOX_KEY_ALIAS")
val noxKeyPassword: String? = System.getenv("NOX_KEY_PASSWORD")
val hasReleaseKey = !noxKeystorePath.isNullOrBlank() &&
    File(noxKeystorePath).exists() &&
    !noxKeystorePassword.isNullOrBlank() &&
    !noxKeyAlias.isNullOrBlank() &&
    !noxKeyPassword.isNullOrBlank()

android {
    namespace = "com.nox.offline"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nox.offline"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Chaquopy требует явного списка ABI: под каждый кладётся свой
        // рантайм Python. Для Python 3.12 у Chaquopy есть только 64-битные
        // сборки: arm64-v8a (все современные телефоны) и x86_64 (эмулятор).
        // yt-dlp — чистый Python, ему всё равно.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = File(noxKeystorePath!!)
                storePassword = noxKeystorePassword
                keyAlias = noxKeyAlias
                keyPassword = noxKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Stage 01: без минификации. ProGuard поверх Chaquopy и
            // Media3 — отдельная работа, а не условие первого APK.
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    // Схемы Room хранятся в репозитории: по ним пишутся и проверяются миграции.
    arg("room.schemaLocation", "$projectDir/schemas")
}

chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            // Разбор ссылок. Только это — вся передача файла идёт в Kotlin.
            install("yt-dlp")
            install("certifi")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
    // Настоящие org.json и SQLite для JVM-тестов (в android.jar это заглушки).
    testImplementation("org.json:json:20240303")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
