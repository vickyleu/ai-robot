@file:Suppress("DEPRECATION")

import org.gradle.internal.extensions.stdlib.capitalized
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import kotlin.text.replace

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}


kotlin {
    jvmToolchain {
        this.languageVersion.set(JavaLanguageVersion.of(17))
        this.vendor.set(JvmVendorSpec.ADOPTIUM)
    }
    applyDefaultHierarchyTemplate()
    js(IR) {
        browser()
        binaries.executable()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
    androidTarget()
    jvm{
        this.compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    // Apple
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    macosArm64()
    macosX64()

    tvosX64()
    tvosArm64()
    tvosArm64()
    tvosSimulatorArm64()


    watchosArm32()
    watchosArm64()
    watchosX64()
    watchosDeviceArm64()
    // Apple
    linuxX64()
    linuxArm64()
    linuxArm32Hfp()

    mingwX64()

    val generateJvm = project(projects.shared.generator.generateJvm.path)
    // 添加任务依赖关系，确保在编译前先生成proto代码
    sourceSets.matching {
        val sourceSet = it.name.capitalized().replace("Main","")
        sourceSet.endsWith("Test").not()
                && (sourceSet==("Native")).not()
                && (sourceSet.startsWith("Android")).not()
                && (sourceSet==("Apple")).not()
                && (sourceSet==("Common")).not()
                && (sourceSet==("Ios")).not()
                && (sourceSet==("Linux")).not()
                && (sourceSet==("Macos")).not()
                && (sourceSet==("Mingw")).not()
                && (sourceSet==("Tvos")).not()
                && (sourceSet==("Watchos")).not()
    }.all {
        val sourceSet = this.name.capitalized().replace("Main","")
        tasks.named("compileKotlin$sourceSet") {
            dependsOn("${generateJvm.path}:generateProto")
        }
    }

    sourceSets {
        commonMain {
            val source = generateJvm.layout.projectDirectory.asFile.resolve("src/main/source")
            kotlin.srcDir(source)
            dependencies {
                // Add dependencies required by the generated Kotlin code
                implementation(libs.pbandk.runtime)
                implementation(libs.kotlinx.rpc.grpc.core)
            }
        }
    }
}

// 需要添加一个任务, 当kotlin编译的时候, 需要执行service-gen中的jar生成jar包
android {
    namespace = "com.airobot.protocol"
    //noinspection GradleDependency
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }

}