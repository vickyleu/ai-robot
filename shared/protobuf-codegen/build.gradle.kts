plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.wire)
}


kotlin {
//    js(IR) {
//        browser()
//        binaries.executable()
//    }
    androidTarget()
    jvm()

    /*// Apple
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    tvosX64()
    tvosArm64()
    tvosSimulatorArm64()
    macosArm64()
    macosX64()
    // Apple

    linuxArm64()
    linuxX64()

    mingwX64()*/

    sourceSets {
        commonMain {
            dependencies {
                implementation(project.dependencies.enforcedPlatform(libs.wire.bom))
                api(libs.wire.runtime)
            }
        }
        jsMain.dependencies {
            api(libs.wire.schema.js)
            api(libs.wire.grpc.client.js)
        }
        androidMain.dependencies {
            api(libs.wire.schema)
            api(libs.wire.grpc.client)
        }

        jvmMain.dependencies {
            api(libs.wire.schema.jvm)
            api(libs.wire.grpc.client.jvm)
        }
//        appleMain.dependencies {
//            api(libs.squareup.wire.schema)
//            api(libs.squareup.wire.grpc.client)
//        }
//
//
//        linuxX64Main.dependencies {
//            api(libs.wire.schema.linuxx64)
//            api(libs.wire.grpc.client.linuxx64)
//        }
//        mingwX64Main.dependencies {
//            api("com.squareup.wire:wire-schema-mingwx64:5.3.1")
//            api("com.squareup.wire:wire-grpc-client-mingwx64:5.3.1")
//        }

    }
}
wire {
    protoPath {
        srcDir("src/commonMain/proto")
    }
    sourcePath {
        srcDir("src/commonMain/proto")
    }

    kotlin {
        javaInterop = true
        out = "${project.layout.buildDirectory.get()}/generated/source/wire"
    }
}

android {
    compileSdk = 34
    namespace = "com.airobot.protocol"
    defaultConfig {
        minSdk = 24
    }
}