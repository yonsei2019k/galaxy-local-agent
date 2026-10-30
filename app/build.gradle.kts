plugins {
    id("com.android.application")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.user.localagent"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    buildToolsVersion = "37.0.0"
    ndkVersion = "29.0.14206865"
    defaultConfig {
        applicationId = "com.user.localagent"
        minSdk = 31
        targetSdk = 37
        versionCode = (System.getenv("RELEASE_VERSION_CODE") ?: "1000").toInt()
        versionName = System.getenv("RELEASE_VERSION_NAME") ?: "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
        buildConfigField("String", "UPDATE_URL", "\"https://github.com/yonsei2019k/galaxy-local-agent/releases/latest/download/update.json\"")
    }
    // Verification APK retains the production package/data and release native flags.
    // It is unsigned here; the release owner applies the existing private signing key.
    buildTypes {
        create("offlineVerification") {
            initWith(getByName("release"))
            signingConfig = null
            matchingFallbacks += listOf("release")
            versionNameSuffix = "-offlinecheck"
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    sourceSets["main"].assets.srcDir("../public")
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2026.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("net.zetetic:sqlcipher-android:4.20.0@aar")
    implementation("androidx.sqlite:sqlite:2.6.2")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.media3:media3-transformer:1.9.2")
    implementation("androidx.media3:media3-effect:1.9.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

