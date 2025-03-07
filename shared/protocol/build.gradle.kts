import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
//    alias(libs.plugins.jetbrains.compose)
//    alias(libs.plugins.jetbrains.compose.compiler)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.rpc)
}

kotlin {
    androidTarget()
    js(IR) {
        browser()
        binaries.executable()
    }
//    @OptIn(ExperimentalWasmDsl::class)
//    wasmJs {
//        browser()
//        binaries.executable()
//    }
    jvm()
    linuxX64()
    linuxArm64()
//    mingwX64()

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared.core)
//            implementation(projects.shared.protobufCodegen)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            // gRPC 多平台支持
            implementation(libs.kotlinx.rpc.krpc.serialization.json)
            implementation(libs.kotlinx.rpc.krpc.client)
            implementation(libs.kotlinx.rpc.krpc.server)
        }

        androidMain.dependencies {
            implementation(libs.androidx.lifecycle.viewmodel.ktx)
        }

        jsMain.dependencies {
            implementation(libs.kotlin.react)
            implementation(libs.kotlin.react.dom)
        }
    }
}

android {
    namespace = "com.airobot.protocol"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }
}