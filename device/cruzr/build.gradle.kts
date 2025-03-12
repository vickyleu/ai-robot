plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared.core)
            implementation(projects.shared.protocol)
            implementation(libs.kotlinx.coroutines.core)
        }

        androidMain.dependencies {
            // 导入jar包和aar文件, 例如: libs下面的jar包和aar文件
            implementation(fileTree("libs") {
                include("*.jar", "*.aar")
            })

            /****************************************************************/
            //The following are the other dependencies required to use google
            implementation(libs.grpc.okhttp)
            implementation(libs.grpc.stub)
            implementation(libs.grpc.api)
            implementation(libs.retrofit)
            implementation(libs.converter.gson)
            implementation(libs.okhttp)
            implementation(libs.logging.interceptor)
            implementation(libs.gson)

            implementation(libs.google.auth.library.oauth2.http)
            implementation(libs.google.cloud.texttospeech)
            implementation(libs.google.cloud.speech)
            implementation(libs.grpc.google.cloud.speech.v1)

        }
    }
}

android {
    namespace = "com.airobot.device.cruzr"
    compileSdk = 35

    defaultConfig {
        minSdk = 25
        targetSdk = 33
    }

    // 忽略默认的resources目录,只使用Android自带的res目录
    sourceSets.getByName("main").resources.exclude("src/androidMain/resources")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    dependencies{
        compileOnly(fileTree("libs") {
            include("*.jar", "*.aar")
        })
    }
}