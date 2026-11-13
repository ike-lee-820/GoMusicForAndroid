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
        // OpenRouter 密钥：不写进源码（GitHub 的 secret scanning 会拦下带密钥的提交），
        // 改从环境变量 OPENROUTER_API_KEY 或 gradle.properties 的 OR_KEY 注入
        buildConfigField(
            "String", "OR_KEY",
            "\"${System.getenv("OPENROUTER_API_KEY") ?: (project.findProperty("OR_KEY") as String? ?: "")}\"",
        )
        minSdk = 26
        targetSdk = 35
        versionCode = 123
        versionName = "26.11.21"
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
    // 无 AI 伴奏提取（2DFT / REPET）的原生模块在 app/src/main/cpp，
    // 默认不参与构建，避免 CI 环境缺 NDK 时整包失败。启用方式见该目录 README。
    // externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
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
    // FFmpeg 解码内核（libavcodec）：Google 不发布 decoder_ffmpeg 的预编译 AAR，
    // Maven 上只有 Java/Kotlin 壳，native .so 必须用 NDK 自行编译。
    // 把编好的 media3-decoder-ffmpeg-1.4.1.aar 放到 app/libs/ 并取消下一行注释，
    // DefaultRenderersFactory 会自动反射加载 FFmpeg 渲染器，播放代码零改动。
    // implementation(files("libs/media3-decoder-ffmpeg.aar"))
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
    // SimpleCache 的 DatabaseProvider 在这个模块里（边播边缓存要用）
    implementation("androidx.media3:media3-database:1.4.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // 简繁转换：转换到香港繁体用 opencc4j 的 ZhHkConverterUtil
    implementation("com.github.houbb:opencc4j:1.14.0")
}
