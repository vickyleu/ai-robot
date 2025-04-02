import com.android.utils.TraceUtils.simpleId
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
//    alias(libs.plugins.jetbrains.compose)
//    alias(libs.plugins.jetbrains.compose.compiler)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}
@Suppress("DEPRECATION")
kotlin {

    compilerOptions{
        freeCompilerArgs.addAll(
            listOf(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlin.ExperimentalStdlibApi",
                "-opt-in=kotlin.ExperimentalMultiplatform",
                "-opt-in=kotlin.native.internal.InternalForKotlinNative",
                "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
            )
        )
    }
    jvmToolchain {
        this.languageVersion.set(JavaLanguageVersion.of(17))
        this.vendor.set(JvmVendorSpec.ADOPTIUM)
    }
    jvm{
        this.compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
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
    watchosArm32()
    watchosDeviceArm64()
    watchosArm64()
    watchosX64()
    tvosSimulatorArm64()
    tvosX64()
    tvosArm64()
    macosArm64()
    macosX64()

    jvm()
    listOf(linuxX64(),
            linuxArm64(),
            linuxArm32Hfp()
    ).forEach {
        // 配置cinterop以绑定Python方法
        it.compilations["main"].cinterops {
            @Suppress("unused")
            val pthreadInterop by creating {
                defFile("src/nativeInterop/cinterop/pthread.def")
                packageName("com.airobot.pthread")
                includeDirs(
                    file("src/nativeInterop/cpp/include/pthread"),
                )
                compilerOpts(
                    "-D__USE_GNU",
                    "-D__THROWNL=__attribute__((__nothrow__))",
                )
            }
        }
    }

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
        androidMain{
            dependsOn(jvmMain.get())
        }
        androidMain.dependencies {
            api(libs.ktor.client.android)
        }
        jvmMain.dependencies {
            api(libs.ktor.client.jvm)
            implementation(kotlin("reflect"))
            //noinspection UseTomlInstead
            api("${libs.androidx.annotation.get().module}-jvm:${libs.versions.annotation.get()}")
        }

        appleMain.dependencies {
            api(libs.ktor.client.darwin)
        }
        linuxMain.dependencies {
            api(libs.ktor.client.curl.get().toString()){
                isChanging=true
            }
        }
        listOf(iosArm64Main,iosX64Main,iosSimulatorArm64Main,
            macosArm64Main,macosX64Main,
            tvosArm64Main,tvosX64Main,tvosSimulatorArm64Main,
            watchosX64Main,watchosArm32Main,watchosArm64Main,watchosDeviceArm64Main,
            linuxArm32HfpMain,linuxArm64Main,linuxX64Main
        ).forEach {
            it.dependencies {
                //noinspection UseTomlInstead
                api("${libs.androidx.annotation.get().module}-${it.name.lowercase().replace("main","")
                }:${libs.versions.annotation.get()}")
            }
        }

        wasmJsMain.dependencies {
            api(libs.ktor.client.js)
            //noinspection UseTomlInstead
            api("${libs.androidx.annotation.get().module}-wasm-js:${libs.versions.annotation.get()}")
        }
        jsMain.dependencies {
            api(libs.ktor.client.js)
            //noinspection UseTomlInstead
            api("${libs.androidx.annotation.get().module}-js:${libs.versions.annotation.get()}")
        }


    }


}

android {
    namespace = "com.airobot.core"
    //noinspection GradleDependency
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }

}