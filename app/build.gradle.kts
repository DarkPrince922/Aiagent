plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.jarvis"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0"
        ndk {
            // Собираем llama.cpp только под 64-битный ARM: приложение личное, целевое
            // устройство одно, а каждая лишняя ABI — это ещё несколько минут сборки.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    signingConfigs {
        create("jarvisDebug") {
            storeFile = file("jarvis-debug.keystore")
            storePassword = "android"
            keyAlias = "jarvisdebug"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("jarvisDebug") }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.github.mwiede:jsch:2.28.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // В android.jar для unit-тестов org.json — заглушка, каждый метод бросает Stub!.
    // Подкладываем настоящую реализацию, иначе всё, что строит JSON, «падает» мимо логики.
    testImplementation("org.json:json:20240303")
}
