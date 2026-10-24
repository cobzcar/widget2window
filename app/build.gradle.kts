plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.widgetflottant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.widgetflottant"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Clé de signature fixe : fournie par le workflow GitHub via des variables d'environnement.
    // Sans ces variables (compilation locale), on retombe sur la clé de débogage.
    val keystorePath = System.getenv("KEYSTORE_FILE")
    val hasReleaseKey = keystorePath != null && file(keystorePath).exists()

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
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
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/versions/**"
        }
    }
}

dependencies {
    // Client ADB en Kotlin : association par code (Débogage sans fil) et commandes shell
    implementation("com.flyfishxu:kadb:2.1.5")
}
