plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // 配置目标平台
    linuxArm64 { // 针对Linux x64平台
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

        linuxArm64Main.dependencies {
            // 平台特定依赖
        }
    }
}