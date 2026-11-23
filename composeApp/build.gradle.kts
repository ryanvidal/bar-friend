import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.metro)
}

kotlin {
    android {
        namespace = "com.ryanvidal.barfriend.composeapp"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources.enable = true

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.core.designsystem)
            api(projects.core.network)
            api(projects.core.database)
            api(projects.core.data)
            api(projects.core.platform)
            implementation(projects.feature.auth)
            implementation(projects.feature.cocktails)
            implementation(projects.feature.inventory)
            implementation(projects.feature.settings)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material3.adaptive)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.androidx.navigation3.runtime)
            implementation(libs.androidx.savedstate)
            implementation(libs.androidx.savedstate.compose)
            implementation(libs.jetbrains.navigation3.ui)
            implementation(libs.jetbrains.navigationevent.compose)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.jetbrains.lifecycle.viewmodel.navigation3)
            implementation(libs.jetbrains.window.core)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.okio)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
        }
    }
}
