plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.wire)
}


kotlin {
    applyDefaultHierarchyTemplate()
//    js(IR) {
//        browser()
//        binaries.executable()
//    }
    androidTarget()
    jvm()

    // Apple
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    tvosX64()
    tvosArm64()
    tvosSimulatorArm64()
    macosArm64()
    macosX64()
    // Apple
    linuxX64()

    mingwX64()

    sourceSets {
        commonMain {
            dependencies {
                api(project.dependencies.enforcedPlatform(libs.wire.bom))
                api(libs.wire.runtime)
                implementation(libs.wire.schema)
                implementation(libs.wire.grpc.client)
            }
        }
        jsMain.dependencies {
            api(libs.wire.schema.js)
            api(libs.wire.grpc.client.js)
        }
        androidMain.dependencies {
            api(libs.wire.schema.jvm)
            api(libs.wire.grpc.client.jvm)
        }

        jvmMain.dependencies {
            api(libs.wire.schema.jvm)
            api(libs.wire.grpc.client.jvm)
        }
        appleMain.dependencies {
            api(libs.squareup.wire.schema)
            api(libs.squareup.wire.grpc.client)
        }


        linuxX64Main.dependencies {
            api(libs.wire.schema.linuxx64)
            api(libs.wire.grpc.client.linuxx64)
        }

        mingwX64Main.dependencies {
            api(libs.wire.schema.mingwx64)
            api(libs.wire.grpc.client.mingwx64)
        }

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