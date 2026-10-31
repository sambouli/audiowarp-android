plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sambouli.audiowarp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sambouli.audiowarp"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // Personal sideloading: reuse the debug keystore so assembleRelease installs.
            signingConfig = signingConfigs.getByName("debug")
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
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// ---------------------------------------------------------------------------
// COMPILE-TIME STUB HYGIENE (critical):
// app/src/compilestubs/java only shadows android.media.AudioTrack so kotlinc can
// compile against the truncated SDK-35 surface. Those .class files MUST NEVER be
// dexed into the APK — a bundled android.media.AudioTrack shadows the real
// framework class on some devices and crashes playback with NoSuchMethodError.
// The stub dir is NOT registered as any sourceSet, so AGP never compiles or dexes
// it; we compile it ourselves here and place it ONLY on the Kotlin compile
// classpath (kotlinOptions.freeCompilerArgs += -Xtype-precedence... not needed:
// bootclasspath android.jar wins for resolution of real members, while the stub
// supplies the missing ones).
// ---------------------------------------------------------------------------
val stubSrcDir = file("src/compilestubs/java")
val stubOutDir = layout.buildDirectory.dir("stub-classes")

val compileStubClasses by tasks.registering(JavaCompile::class) {
    description = "Compile android.media.AudioTrack compile-time stub (never dexed)."
    group = "build"
    source = fileTree(stubSrcDir)
    classpath = files(android.bootClasspath)
    destinationDir = stubOutDir.get().asFile
    options.compilerArgs.add("-nowarn")
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configureEach {
    dependsOn(compileStubClasses)
    // Append (do NOT re-wrap `libraries` — self-reference causes infinite recursion).
    libraries.from(files(stubOutDir))
}

// Belt & braces: if anything ever leaks the stub .class files into a javac output
// dir that AGP dexes, delete them from the dex input right before dexing runs.
tasks.matching { it.name.startsWith("dexBuilder") || it.name.startsWith("mergeDebugJavaResource") }.configureEach {
    doFirst {
        fileTree(layout.buildDirectory.dir("intermediates/javac")) {
            include("**/android/media/AudioTrack*.class")
        }.files.forEach { it.delete() }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
