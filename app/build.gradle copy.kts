import java.util.Properties
import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(keystorePropertiesFile.inputStream())
    }
}

android {
    namespace = "com.coderred.andclaw"
    compileSdk = 35

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
//        create("release") {
//            storeFile = file(keystoreProperties.getProperty("storeFile", "../andclaw-release.keystore"))
//            storePassword = keystoreProperties.getProperty("storePassword", System.getenv("KEYSTORE_PASSWORD") ?: "")
//            keyAlias = keystoreProperties.getProperty("keyAlias", "andclaw")
//            keyPassword = keystoreProperties.getProperty("keyPassword", System.getenv("KEYSTORE_PASSWORD") ?: "")
//        }
    }

    defaultConfig {
        applicationId = "com.coderred.andclaw"
        minSdk = 26
        targetSdk = 35
        versionCode = 79
        versionName = "0.0.79"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    flavorDimensions += "env"
    productFlavors {
        create("prod") {
            dimension = "env"
            buildConfigField("String", "OAUTH_CALLBACK_SCHEME", "\"andclaw\"")
            buildConfigField(
                "String",
                "OPENROUTER_CALLBACK_URL",
                "\"https://andclaw.coderred.com/callback?scheme=andclaw&package=com.coderred.andclaw\"",
            )
            buildConfigField("String", "OAUTH_APP_RETURN_URI", "\"andclaw://auth/codex-callback\"")
            manifestPlaceholders["oauthCallbackScheme"] = "andclaw"
        }
        create("dev") {
            dimension = "env"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "andClaw Dev")
            buildConfigField("String", "OAUTH_CALLBACK_SCHEME", "\"andclaw-dev\"")
            buildConfigField(
                "String",
                "OPENROUTER_CALLBACK_URL",
                "\"https://andclaw.coderred.com/callback?scheme=andclaw-dev&package=com.coderred.andclaw.dev\"",
            )
            buildConfigField("String", "OAUTH_APP_RETURN_URI", "\"andclaw-dev://auth/codex-callback\"")
            manifestPlaceholders["oauthCallbackScheme"] = "andclaw-dev"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    // 调试APK：将PAD assets直接包含在APK中（对于AAB格式，由Play Asset Delivery处理）
    sourceSets {
        getByName("debug") {
            assets.srcDirs("../install_time_assets/src/main/assets")
        }
        getByName("release") {
            assets.srcDirs("../install_time_assets/src/main/assets")
        }
    }

    androidResources {
        noCompress += listOf("tar.gz", "bin")
        // 显式指定忽略规则，防止OpenClaw node_modules内部的_vendor目录在assets打包时被遗漏
        ignoreAssetsPattern = "!.svn:!.git:!*.ds_store:!*.scc:!CVS:!thumbs.db:!picasa.ini:!_*:!*~"
    }

    // 必需配置：将proot二进制文件提取到nativeLibraryDir（JNI库目录）
    // legacyPackaging模式会将jniLibs中的.so文件直接打包，而不是压缩存储
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Play Asset Delivery (PAD)：将大型assets分离到独立的asset pack中
    // 这样可以减小主APK大小，用户首次启动时再按需下载资源
    assetPacks += listOf(":install_time_assets")
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Activity & Lifecycle
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.9")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.2.0")

    // Browser (Chrome Custom Tabs)
    implementation("androidx.browser:browser:1.8.0")

    // Core
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("com.google.android.material:material:1.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // WorkManager (watchdog recovery path)
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Google Play In-App Review
    implementation("com.google.android.play:review-ktx:2.0.2")

    // Apache Commons Compress：用于解压tar.gz格式的压缩文件
    implementation("org.apache.commons:commons-compress:1.27.1")

    // ZXing：用于生成和渲染二维码（WhatsApp配对时需要扫描二维码）
    implementation("com.google.zxing:core:3.5.3")

    // OkHttp：用于与网关服务进行WebSocket通信
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Test
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

tasks.register<Test>("testDebugUnitTest") {
    group = "verification"
    description = "Compatibility alias for prod debug unit tests"
    val prodDebugTask = tasks.named<Test>("testProdDebugUnitTest").get()
    testClassesDirs = prodDebugTask.testClassesDirs
    classpath = prodDebugTask.classpath
}

tasks.register("connectedDebugAndroidTest") {
    group = "verification"
    description = "Compatibility alias for prod debug instrumentation tests"
    dependsOn("connectedProdDebugAndroidTest")
}

tasks.register("lintDebug") {
    group = "verification"
    description = "Compatibility alias for prod debug lint checks"
    dependsOn("lintProdDebug")
}
