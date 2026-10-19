// Top-level build file where you can add configuration options common to all sub-projects/modules.
// AGP 9.4 ships Kotlin 2.2, which cannot read the Kotlin 2.4 metadata in Firebase BoM 35
// and coroutines 1.11. A newer compiler on the buildscript classpath is what built-in
// Kotlin actually runs with.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}