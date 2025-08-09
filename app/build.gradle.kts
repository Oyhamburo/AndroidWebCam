plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.celsocam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.celsocam"
        minSdk = 24     // OK (WebRTC funciona 21+)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // ⚠️ Usar Java 17 (mejor compat. con AGP 8.x y toolchain moderna)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // (Opcional) Si más adelante usás viewBinding/compose
    // buildFeatures { viewBinding = true }
}

dependencies {
    // Android base (ya tenías estas con catálogo; las dejo por si tu libs.versions.toml las exporta)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // ★ WebRTC nativo
    implementation("io.github.webrtc-sdk:android:137.7151.01")

    // Si querés fijar versión exacta, podés usar por ejemplo: 1.0.51537

    // ★ Señalización por WebSocket (OkHttp)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ★ Ciclo de vida (útil para limpiar recursos en onStop/onDestroy)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
