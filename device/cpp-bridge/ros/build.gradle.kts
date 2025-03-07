plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // 配置目标平台
    linuxX64 { // 针对Linux x64平台
        /*compilations.getByName("main") {
            cinterops {
                create("ros") {
                    packageName("ros")
                    // ROS C++ 头文件路径
                    includeDirs.allHeaders("/opt/ros/noetic/include")
                }
            }
        }
        */
        binaries {
            executable {
                entryPoint = "main"
            }
        }
    }
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.shared.core)
                implementation(projects.shared.protocol)
            }
        }
        
        val linuxX64Main by getting {
            dependencies {
                // 平台特定依赖
            }
        }
    }
}

dependencies {
    // 通用依赖配置
}