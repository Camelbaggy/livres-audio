plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Signature release : variables d'environnement (jamais dans le dépôt, CLAUDE.md §12).
val keystoreFile: String? = System.getenv("LA_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val keystorePassword: String? = System.getenv("LA_KEYSTORE_PASSWORD")
val keyAliasName: String? = System.getenv("LA_KEY_ALIAS")
val keyPasswordValue: String? = System.getenv("LA_KEY_PASSWORD")
val hasReleaseSigning = keystoreFile != null && keystorePassword != null &&
    keyAliasName != null && keyPasswordValue != null

android {
    namespace = "io.github.camelbaggy.livresaudio"
    compileSdk = 37

    defaultConfig {
        // NE JAMAIS CHANGER : lié au client OAuth Google (CLAUDE.md §3).
        applicationId = "io.github.camelbaggy.livresaudio"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(keystoreFile))
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:drive"))
    implementation(project(":core:library"))
    implementation(project(":core:playback"))
    implementation(project(":core:metadata"))
    implementation(project(":core:sync"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
