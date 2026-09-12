plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.novaura.music"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.novaura.music"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.0.4"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val name = if (variant.buildType.name == "release") {
                "Novaura-v${variant.versionName}.apk"
            } else {
                "Novaura-v${variant.versionName}-${variant.buildType.name}.apk"
            }
            output.outputFileName = name
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

tasks.register("copyApkToRoot") {
    doLast {
        val rootApkDir = file("${rootDir}/apk")
        if (!rootApkDir.exists()) {
            rootApkDir.mkdirs()
        }
        val releaseApk = file("${layout.buildDirectory.get()}/outputs/apk/release/Novaura-v1.0.3.apk")
        val debugApk = file("${layout.buildDirectory.get()}/outputs/apk/debug/Novaura-v1.0.3-debug.apk")
        
        if (releaseApk.exists()) {
            releaseApk.copyTo(file("${rootApkDir}/Novaura-v1.0.3.apk"), overwrite = true)
            releaseApk.copyTo(file("${rootApkDir}/Novaura-v1.0.2.apk"), overwrite = true)
            releaseApk.copyTo(file("${rootApkDir}/Novaura-v1.0.1.apk"), overwrite = true)
            releaseApk.copyTo(file("${rootApkDir}/Novaura-v1.0.0.apk"), overwrite = true)
        } else if (debugApk.exists()) {
            debugApk.copyTo(file("${rootApkDir}/Novaura-v1.0.3.apk"), overwrite = true)
            debugApk.copyTo(file("${rootApkDir}/Novaura-v1.0.2.apk"), overwrite = true)
            debugApk.copyTo(file("${rootApkDir}/Novaura-v1.0.1.apk"), overwrite = true)
            debugApk.copyTo(file("${rootApkDir}/Novaura-v1.0.0.apk"), overwrite = true)
        }
        
        if (debugApk.exists()) {
            debugApk.copyTo(file("${rootApkDir}/Novaura-v1.0.3-debug.apk"), overwrite = true)
            debugApk.copyTo(file("${rootApkDir}/Novaura-v1.0.2-debug.apk"), overwrite = true)
            debugApk.copyTo(file("${rootApkDir}/Novaura-v1.0.1-debug.apk"), overwrite = true)
            debugApk.copyTo(file("${rootApkDir}/Novaura-v1.0.0-debug.apk"), overwrite = true)
        }
    }
}

tasks.configureEach {
    if (name.startsWith("assemble")) {
        finalizedBy("copyApkToRoot")
    }
}