plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied (AGP 9 compiles Kotlin itself); declared to put this Kotlin version on the
    // build classpath, so the compiler matches the Compose and serialization plugins below.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
