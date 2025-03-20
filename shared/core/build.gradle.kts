import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
//    alias(libs.plugins.jetbrains.compose)
//    alias(libs.plugins.jetbrains.compose.compiler)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()
    androidTarget{
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
    js(IR) {
        browser()
        binaries.executable()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    tvosX64()
    tvosArm64()
    macosArm64()
    macosX64()

    jvm()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared.protocol)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
            api(libs.kotlinx.datetime)
            // 增加jetbrains 注解, 用于设置IntRange等约束
            api(libs.androidx.annotation)
        }

        androidMain.dependencies {
            api(libs.ktor.client.android)
        }

        jsMain.dependencies {
            api(libs.ktor.client.js)
        }
    }
}

android {
    namespace = "com.airobot.core"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }

}