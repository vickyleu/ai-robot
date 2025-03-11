plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // 配置目标平台
    linuxX64 { // 针对Linux x64平台
        /*compilations.getByName("main") {
            cinterops {
                create("unitree") {
                    packageName("unitree")
                    // Unitree SDK头文件路径
                    includeDirs.allHeaders("${project.projectDir}/sdk/include")
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
        commonMain.dependencies {
            implementation(projects.shared.core)
            implementation(projects.shared.protocol)
        }

        linuxX64Main.dependencies {
            // 平台特定依赖
        }
    }
}

dependencies {
    // 通用依赖配置
}