import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    id(libs.plugins.kotlin.rpc.get().pluginId)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain {
        this.languageVersion.set(JavaLanguageVersion.of(17))
        this.vendor.set(JvmVendorSpec.ADOPTIUM)
    }
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
    watchosArm32()
    watchosDeviceArm64()
    watchosArm64()
    watchosX64()
    tvosSimulatorArm64()
    tvosX64()
    tvosArm64()
    macosArm64()
    macosX64()

    jvm{
        this.compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
    linuxX64()
    linuxArm64()
    @Suppress("DEPRECATION")
    linuxArm32Hfp()

    sourceSets {

        commonMain.configure {
            kotlin.srcDir("$projectDir/generated/commonMain")
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(projects.shared.generator.protobufCodegen)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.kotlinx.datetime)
                // gRPC 多平台支持
                implementation(libs.kotlinx.rpc.krpc.serialization.json)
                implementation(libs.kotlinx.rpc.krpc.client)
                implementation(libs.kotlinx.rpc.krpc.server)
                implementation(libs.kotlinx.rpc.grpc.core)
            }
        }

        jvmMain.dependencies {
            implementation(libs.logback.classic)
            implementation(libs.grpc.netty)
            implementation(kotlin("reflect"))
        }
        androidMain{
            dependsOn(jvmMain.get())
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
    //noinspection GradleDependency
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }
}