plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "jp.pocketnas.server"
    compileSdk = 34

    defaultConfig {
        applicationId = "jp.pocketnas.server"
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

    // ★ 追加：特殊なディレクトリ名をソースとして認識させる
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

    // Ktor（既存で使っていたもの）
    implementation("io.ktor:ktor-server-core:2.3.7")
    implementation("io.ktor:ktor-server-cio:2.3.7")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")

    // SAF（DocumentFile）
    implementation("androidx.documentfile:documentfile:1.0.1")

    // kotlinx.serialization（@Serializable に必須）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    implementation("io.ktor:ktor-server-compression:2.3.7")

    // ★Index: アイドル時インデックス用 WorkManager（Worker API のみ使用、ktx なし）
    implementation("androidx.work:work-runtime:2.9.0")
}