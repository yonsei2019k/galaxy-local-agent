plugins {
    id("com.android.application")
    kotlin("plugin.compose")
}
android {
    namespace = "com.user.localagent.images"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    buildToolsVersion = "37.0.0"
    ndkVersion = "29.0.14206865"
    defaultConfig {
        applicationId = "com.user.localagent.images"
        minSdk = 31
        targetSdk = 37
        versionCode = (System.getenv("RELEASE_VERSION_CODE") ?: "1000").toInt()
        versionName = System.getenv("RELEASE_VERSION_NAME") ?: "0.1.0-dev"
        ndk { abiFilters += "arm64-v8a" }
        manifestPlaceholders["ziphapUpdateUrl"] = ""
    }
    buildFeatures { compose = true; buildConfig = false }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies { implementation(project(":android-runtime")) }
