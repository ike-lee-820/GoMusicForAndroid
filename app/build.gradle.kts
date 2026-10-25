import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 正式版签名：优先读环境变量（CI 用 GitHub Secrets 注入），本地可选 keystore.properties
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

fun secret(name: String, fallback: String? = null): String? =
    (System.getenv(name) ?: keystoreProps.getProperty(name))?.takeIf { it.isNotBlank() } ?: fallback

android {
    namespace = "de.myocd.gomusic"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.myocd.gomusic"
        minSdk = 26
        targetSdk = 35
        versionCode = 49
        versionName = "2.1.0"
    }

    signingConfigs {
        create("release") {
            val ksPath = secret("KEYSTORE_PATH")
            val ksStorePass = secret("KEYSTORE_PASSWORD")
            val ksAlias = secret("KEY_ALIAS")
            val ksKeyPass = secret("KEY_PASSWORD")
            if (ksPath != null && ksStorePass != null && ksAlias != null && ksKeyPass != null) {
                storeFile = rootProject.file(ksPath)
                storePassword = ksStorePass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 正式版用固定签名；缺少密钥时回退到 debug 签名，保证本地/CI 都不会构建失败
            signingConfig = if (signingConfigs.findByName("release")?.storeFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // 简繁转换：转换到香港繁体用 opencc4j 的 ZhHkConverterUtil
    implementation("com.github.houbb:opencc4j:1.14.0")
}
