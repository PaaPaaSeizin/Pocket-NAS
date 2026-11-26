plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "jp.pocketnas.manager"
    compileSdk = 34

    defaultConfig {
        applicationId = "jp.pocketnas.manager"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // ★ 追加：kotlin+java フォルダをソースとして認識させる
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "src/main/kotlin+java")
            kotlin.srcDirs("src/main/kotlin", "src/main/kotlin+java")
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // ★Step3: 画像ビューアのスワイプ切替
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    // ★Step6: 指紋/PINロック
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("com.github.chrisbanes:PhotoView:2.3.0")
}