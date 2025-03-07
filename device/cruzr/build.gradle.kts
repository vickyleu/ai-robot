plugins {
    kotlin("multiplatform")
    id("com.android.library")
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
        val commonMain by getting {
            dependencies {
                implementation(projects.shared.core)
                implementation(projects.shared.protocol)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        
        val androidMain by getting {
            dependencies {
                // Android特定依赖
//                implementation(libs.androidx.core.ktx)
            }
        }
    }
}

android {
    namespace = "com.airobot.device.cruzr"
    compileSdk = 34
    
    defaultConfig {
        minSdk = 25
        targetSdk = 33
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}