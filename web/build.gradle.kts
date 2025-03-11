import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    js(IR) {
        nodejs {
            version = libs.node.get().version.toString()
        }
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
            }
            binaries.executable()
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project.dependencies.enforcedPlatform(libs.kotlin.js.wrappers.bom))
        }
        jsMain.dependencies {
            implementation(projects.shared.protocol)
            implementation(projects.shared.core)

            // Kotlin/JS 运行时

            implementation(libs.kotlin.js.html)
            implementation(libs.kotlin.js.wrappers)
            implementation(libs.kotlin.popper)
            implementation(libs.kotlin.remix.run.router)
            implementation(libs.kotlin.extensions)


            // React 相关依赖
            implementation(libs.kotlin.react)
            implementation(libs.kotlin.react.dom)
            implementation(libs.kotlin.emotion)
            implementation(libs.kotlin.react.router)
            implementation(libs.kotlin.react.router.dom)

            // Material UI
            implementation(libs.kotlin.mui)
            implementation(libs.kotlin.mui.icons)

            // Ktor
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.js)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)

            // Kotlinx
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)

            // 依赖
            implementation(npm("@types/node", "22.13.10"))

            implementation(npm("dagre-d3", "^0.6.4"))

            implementation(npm("clsx", "^2.0.0"))
            implementation(npm("react-draggable", "^4.4.4"))
            implementation(npm("react-dropzone", "^14.2.1"))
            implementation(npm("react-mosaic-component", "^6.1.0"))
            implementation(npm("react-error-boundary", "^2.2.2"))
            implementation(npm("ace-builds", "1.28.0"))
            implementation(npm("react-ace", "10.1.0"))
            implementation(npm("markdown-it", "~11.0.0"))

            implementation(devNpm("webpack-bundle-analyzer", "4.10.2"))
        }
    }
}
// Yarn
rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    rootProject.extensions.configure<YarnRootExtension> {
        version = libs.yarn.get().version.toString()
    }
}
// Node.js
rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
    rootProject.extensions.configure<NodeJsRootExtension> {
        version = libs.node.get().version.toString()
    }
}

