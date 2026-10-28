plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// সংস্করণ: GitHub বিল্ডের নম্বর অনুযায়ী 1.1, 1.2, 1.3 ...
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val buildOffset = System.getenv("BUILD_OFFSET")?.toIntOrNull() ?: 0
val buildNo = maxOf(runNumber - buildOffset, 1)

android {
    namespace = "com.example.familytree"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.familytree"
        minSdk = 24
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.$buildNo"
    }

    signingConfigs {
        create("release") {
            storeFile = file("family-release.jks")
            storePassword = "familytree123"
            keyAlias = "family"
            keyPassword = "familytree123"
        }
    }
    buildTypes {
        release {
            // কোড অস্পষ্ট (obfuscate) ও ছোট করা হবে, যাতে রিভার্স করা কঠিন হয়
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    lint {
        checkReleaseBuilds = false
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
}
