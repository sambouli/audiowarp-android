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
        versionCode = 3
        versionName = "1.0.2"
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
// NO android.* SOURCE STUBS (guardrail, see assertNoFrameworkStubs).
//
// This module once carried a hand-written `android.media.AudioTrack` used only to get
// kotlinc past a constructor it could not see. A stub like that is a trap twice over: any
// framework method it forgets breaks the build, and if its .class is ever dexed the
// app ships a class that shadows the platform one. Worse, compiling against a stub lets
// the app call a constructor the device ROM does not export — that resolves at runtime as
// NoSuchMethodError, which is an Error, so it walks straight past the `catch (Exception)`
// guards and ends the process on the first play. The fix was to compile against the real
// android.jar and call the five-argument AudioTrack constructor the SDK actually exports.
//
// So: never add a stub. If the SDK looks like it is missing a member, check it against
// $ANDROID_HOME/platforms/<api>/android.jar with javap before writing anything, and reach
// older/newer overloads behind a Build.VERSION.SDK_INT check.
// ---------------------------------------------------------------------------
val assertNoFrameworkStubs by tasks.registering {
    description = "Fail the build if app/src/main sources declare android.* or java.* types."
    group = "verification"
    doLast {
        val offenders = fileTree("src/main/java") { include("**/android/**", "**/java/**") }.files
        if (offenders.isNotEmpty()) {
            error(
                "app/src/main must not declare platform types; they shadow the device ROM at runtime.\n" +
                    offenders.joinToString("\n") { "  ${it.path}" }
            )
        }
    }
}
tasks.matching { it.name.matches(Regex("(assemble|bundle|dexBuilder|mergeDebugJavaResource).*")) }.configureEach {
    dependsOn(assertNoFrameworkStubs)
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
