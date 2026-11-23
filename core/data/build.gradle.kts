import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.metro)
}

kotlin {
    android {
        namespace = "com.ryanvidal.barfriend.core.data"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.core.network)
            api(projects.core.database)
            implementation(projects.core.platform)
            api(libs.androidx.datastore.preferences)
            implementation(libs.okio)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
