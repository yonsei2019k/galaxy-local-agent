plugins {
    id("com.android.library")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
}
// Use the AGP 9 public DSL explicitly. The legacy generated `android` accessor
// types library sourceSets as com.android.build.gradle.api.AndroidLibrarySourceSet,
// which the AGP 9 DefaultAndroidLibrarySourceSet implementation no longer implements.
extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "com.user.localagent.runtime"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    buildToolsVersion = "37.0.0"
    ndkVersion = "29.0.14206865"
    defaultConfig {
        minSdk = 31
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }
    buildFeatures { compose = true; buildConfig = false }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    sourceSets.named("main") {
        java.srcDir("../app/src/main/java")
        kotlin.directories += file("../app/src/main/java").absolutePath
        res.srcDir("../app/src/main/res")
        assets.srcDir("../public")
        assets.srcDir(layout.buildDirectory.dir("generated/react-assets").get().asFile)
        jniLibs.srcDir(layout.buildDirectory.dir("generated/rust/jniLibs").get().asFile)
        kotlin.directories += layout.buildDirectory.dir("generated/rust/kotlin").get().asFile.absolutePath
    }
    sourceSets.named("test") {
        java.srcDirs("../app/src/test/java", "../app/src/sharedTest/java")
        kotlin.directories += file("../app/src/test/java").absolutePath
        kotlin.directories += file("../app/src/sharedTest/java").absolutePath
    }
    externalNativeBuild { cmake { path = file("../app/src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "${rootProject.projectDir}/app/schemas") }
dependencies {
    api("net.java.dev.jna:jna:5.17.0@aar")
    api(project(":core"))
    api(platform("androidx.compose:compose-bom:2026.02.00"))
    api("androidx.activity:activity-compose:1.10.1")
    api("androidx.compose.material3:material3")
    api("androidx.compose.ui:ui")
    api("androidx.compose.ui:ui-tooling-preview")
    api("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    api("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    api("androidx.core:core-ktx:1.17.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    api("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    api("com.google.mlkit:genai-speech-recognition:1.0.0-alpha2")
    api("androidx.room:room-runtime:2.8.4")
    api("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    api("net.zetetic:sqlcipher-android:4.20.0@aar")
    api("androidx.sqlite:sqlite:2.6.2")
    api("androidx.work:work-runtime-ktx:2.12.0")
    api("androidx.webkit:webkit:1.14.0")
    api("androidx.media3:media3-transformer:1.9.2")
    api("androidx.media3:media3-effect:1.9.2")
    api("com.google.android.gms:play-services-auth:22.0.0")
    api("com.google.android.gms:play-services-mlkit-text-recognition:19.0.2")
    api("com.google.android.gms:play-services-mlkit-text-recognition-korean:16.0.2")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    api("com.squareup.okhttp3:okhttp:5.3.2")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("net.java.dev.jna:jna:5.17.0@jar")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
val buildRustEngine by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine(providers.environmentVariable("ZIPHAP_PYTHON").orElse("python").get(), "scripts/build-rust-android.py")
    inputs.files(fileTree("../rust") { include("Cargo.toml", "Cargo.lock", "rust-toolchain.toml", "crates/**"); exclude("target/**") })
    inputs.file("../scripts/build-rust-android.py")
    outputs.dir(layout.buildDirectory.dir("generated/rust"))
}
tasks.withType<Test>().configureEach {
    workingDir(rootProject.file("app"))
    dependsOn(buildRustEngine)
    val hostLibrary = layout.buildDirectory.file("generated/rust/host/${System.mapLibraryName("ziphap_android")}")
    inputs.file(hostLibrary)
    systemProperty("uniffi.component.ziphap_android.libraryOverride", hostLibrary.get().asFile.absolutePath)
}
val buildReactUi by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine(providers.environmentVariable("ZIPHAP_PYTHON").orElse("python").get(), "scripts/build-react-ui.py")
    inputs.files(fileTree("../web-ui") { exclude("node_modules/**", "dist/**") })
    inputs.file("../scripts/build-react-ui.py")
    outputs.dir(layout.buildDirectory.dir("generated/react-assets/react"))
}
tasks.named("preBuild").configure { dependsOn(buildRustEngine,buildReactUi) }

// KSP reads the generated UniFFI Kotlin sources before Android preBuild ordering
// can establish that dependency for a library variant.
tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("Kotlin") }.configureEach {
    dependsOn(buildRustEngine)
}
