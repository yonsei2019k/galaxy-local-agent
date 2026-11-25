plugins {
    id("com.android.application")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
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
        val outlookId = providers.environmentVariable("ZIPHAP_OUTLOOK_CLIENT_ID").orNull.orEmpty().trim()
        val outlookTenant = providers.environmentVariable("ZIPHAP_OUTLOOK_TENANT").orNull.orEmpty().ifBlank { "common" }
        val guid = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
        require(outlookId.isEmpty() || guid.matches(outlookId)) { "Invalid Outlook public client ID" }
        require(outlookTenant in setOf("common","consumers","organizations") || guid.matches(outlookTenant)) { "Invalid Outlook tenant" }
        manifestPlaceholders["outlookClientId"] = outlookId.ifEmpty { "unconfigured" }
        val xClient = providers.environmentVariable("ZIPHAP_X_CLIENT_ID").orNull.orEmpty().trim()
        require(xClient.isEmpty() || xClient.matches(Regex("[A-Za-z0-9_-]{8,200}")))
        manifestPlaceholders["ziphapXClientId"] = xClient.ifEmpty { "." }
        manifestPlaceholders["outlookTenant"] = outlookTenant
        manifestPlaceholders["ziphapUpdateUrl"] = "https://github.com/yonsei2019k/galaxy-local-agent/releases/latest/download/update.json"
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
    // These source files stay here, but only android-runtime compiles them.
    sourceSets.named("main") { java.setSrcDirs(emptyList<String>()); kotlin.directories.clear() }
    sourceSets.named("test") { java.setSrcDirs(emptyList<String>()); kotlin.directories.clear() }
    sourceSets.named("androidTest") { kotlin.directories += "src/sharedTest/java" }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":android-runtime"))
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
    implementation("com.google.mlkit:genai-speech-recognition:1.0.0-alpha2")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("net.zetetic:sqlcipher-android:4.20.0@aar")
    implementation("androidx.sqlite:sqlite:2.6.2")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.media3:media3-transformer:1.9.2")
    implementation("androidx.media3:media3-effect:1.9.2")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.2")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-korean:16.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Retain CI entry points while JVM tests become friends of the shared runtime.
tasks.matching { it.name.startsWith("test") && it.name.endsWith("UnitTest") }.configureEach {
    val runtimeTask = if (name == "testOfflineVerificationUnitTest") "testReleaseUnitTest" else name
    dependsOn(":android-runtime:$runtimeTask")
}
tasks.register("buildRustEngine") { dependsOn(":android-runtime:buildRustEngine") }
tasks.register("buildReactUi") { dependsOn(":android-runtime:buildReactUi") }

// Device tests retain their current target APK and may inspect internal runtime APIs.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.startsWith("compile") && name.endsWith("AndroidTestKotlin")) {
        val variant = name.removePrefix("compile").removeSuffix("AndroidTestKotlin")
        val runtimeVariant = if (variant == "OfflineVerification") "Release" else variant
        val runtimeTask = "compile${runtimeVariant}Kotlin"
        dependsOn(":android-runtime:$runtimeTask", ":android-runtime:bundleLibCompileToJar$runtimeVariant")
        val runtimeClasses = project(":android-runtime").layout.buildDirectory.dir(
            "intermediates/built_in_kotlinc/${runtimeVariant.replaceFirstChar { it.lowercase() }}/$runtimeTask/classes")
        val runtimeJar = project(":android-runtime").layout.buildDirectory.file(
            "intermediates/compile_library_classes_jar/${runtimeVariant.replaceFirstChar { it.lowercase() }}/bundleLibCompileToJar$runtimeVariant/classes.jar")
        // Add to the task's friend collection: a free -Xfriend-paths argument is
        // overwritten by KGP's own friend argument, and Android compiles against the jar.
        friendPaths.from(runtimeClasses, runtimeJar)
    }
}

