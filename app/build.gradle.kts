plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.brbrs.zaman"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.brbrs.zaman"
        minSdk = 26
        targetSdk = 36
        versionCode = 29
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("dagger.fastInit", "enabled")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // Per-calendar cover images (user-picked photos, cached as local files) — Coil loads/decodes/
    // caches them off the main thread and handles sampling large gallery photos down to the tiny
    // sizes actually needed (a row thumbnail, a month-cell dot), which hand-rolled BitmapFactory
    // decoding would get wrong on a full-res photo (OOM risk) without real effort.
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.fragment:fragment-ktx:1.8.5") // FragmentActivity for BiometricPrompt

    // Hilt
    implementation("com.google.dagger:hilt-android:2.55")
    ksp("com.google.dagger:hilt-android-compiler:2.55")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Networking — OkHttp + org.json ONLY, no Retrofit/Gson (R8 ClassCastException on release)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Plain implementation (not debugImplementation): AppModule.kt lives in the shared main
    // source set and references this class directly, guarded by BuildConfig.DEBUG at runtime —
    // debugImplementation would leave the release compile classpath without the class at all.
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Biometric unlock (shared pattern across the suite)
    implementation("androidx.biometric:biometric:1.1.0")

    // WorkManager — enqueueUniqueWork for CalDAV sync to avoid 423 Locked races
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // Home-screen agenda widget
    implementation("androidx.glance:glance-appwidget:1.1.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
