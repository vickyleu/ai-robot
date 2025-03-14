import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
//    alias(libs.plugins.jetbrains.compose)
//    alias(libs.plugins.jetbrains.compose.compiler)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.rpc)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    applyDefaultHierarchyTemplate()
    androidTarget {
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

        commonMain.configure {
            kotlin.srcDir("$projectDir/generated/commonMain")
            dependencies {

                implementation(projects.shared.generator.protobufCodegen)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.kotlinx.datetime)
                implementation(project.dependencies.enforcedPlatform(libs.kotlinx.rpc.bom))
                // gRPC 多平台支持
                implementation(libs.kotlinx.rpc.krpc.serialization.json)
                implementation(libs.kotlinx.rpc.krpc.client)
                implementation(libs.kotlinx.rpc.krpc.server)
                implementation(libs.kotlinx.rpc.grpc.core)
            }
        }

        jvmMain.dependencies {
            implementation("ch.qos.logback:logback-classic:1.5.16")
            implementation("io.grpc:grpc-netty:1.69.0")
        }
        androidMain.dependencies {
            implementation(libs.androidx.lifecycle.viewmodel.ktx)
        }

        jsMain.dependencies {
//            implementation(libs.kotlin.react)
//            implementation(libs.kotlin.react.dom)
        }
    }
}
rpc {
    grpc {
        enabled = true
    }
}


android {
    namespace = "com.airobot.protocol"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }
}