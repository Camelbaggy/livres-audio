plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.camelbaggy.livresaudio.core.library"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
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
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
