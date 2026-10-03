plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Database settings. The password is NEVER stored in the code:
// it comes from the DB_PASS environment variable (a GitHub Actions secret).
fun env(name: String, default: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

fun quoted(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.paypeico.chat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.paypeico.chat"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "DB_HOST", quoted(env("DB_HOST", "62.171.158.102")))
        buildConfigField("String", "DB_PORT", quoted(env("DB_PORT", "3306")))
        buildConfigField("String", "DB_NAME", quoted(env("DB_NAME", "payp_admindb")))
        buildConfigField("String", "DB_USER", quoted(env("DB_USER", "chatapp")))
        buildConfigField("String", "DB_PASS", quoted(env("DB_PASS", "")))
    }

    // Fixed signing key so every new APK installs as an update over the old one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // MySQL/MariaDB driver (5.1.x is the line that works on Android)
    implementation("mysql:mysql-connector-java:5.1.49")
    // Verifies Laravel's bcrypt password hashes
    implementation("org.mindrot:jbcrypt:0.4")
}
