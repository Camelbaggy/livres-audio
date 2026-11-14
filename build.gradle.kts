import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint) apply false
}

val ktlintVersion: String = libs.versions.ktlint.get()

// ktlint sur tous les modules (règles dans .editorconfig).
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    extensions.configure<KtlintExtension> {
        version.set(ktlintVersion)
        android.set(true)
        ignoreFailures.set(false)
    }
}
