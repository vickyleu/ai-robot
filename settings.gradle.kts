@file:Suppress("UnstableApiUsage")



rootProject.name = "ai-robot"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// GO 后端
include(":backend")
// 核心模块
include(":shared:core")
// 协议模块
include(":shared:protocol")
// 协议模块 - 代码生成
include(":shared:protobuf-codegen")
// Web 前端
include(":web")
// 设备端
include(":device")
// 设备端 - CPP 桥接
include(":device:cpp-bridge")
// 设备端 - CPP 桥接 - ROS
include(":device:cpp-bridge:ros")
// 设备端 - CPP 桥接 - Unitree
include(":device:cpp-bridge:unitree")
// 设备端 - Cruzr android
include(":device:cruzr")
// 设备端 - Yan API
include(":device:yan-api")

pluginManagement {
    repositories.apply {
        removeAll(this)
    }
    dependencyResolutionManagement.repositories.apply {
        removeAll(this)
    }
    listOf(repositories, dependencyResolutionManagement.repositories).forEach {
        it.apply {
            gradlePluginPortal()
            google()
            mavenCentral()

            maven(url = "https://androidx.dev/storage/compose-compiler/repository") {
                isAllowInsecureProtocol = true
                content {
                    excludeGroupByRegex("com.github.*")
                }
            }
            maven(url = "https://maven.pkg.jetbrains.space/public/p/compose/dev") {
                isAllowInsecureProtocol = true
                content {
                    excludeGroupByRegex("com.github.*")
                }
            }
            maven(url = "https://maven.pkg.jetbrains.space/kotlin/p/kotlin/dev"){
                isAllowInsecureProtocol = true
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}


dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        maven{
            isAllowInsecureProtocol = true
            setUrl("https://repo1.maven.org/maven2")
        }
        maven{
            isAllowInsecureProtocol = true
            setUrl("https://plugins.gradle.org/m2")
        }
        google {
            isAllowInsecureProtocol = true
            content {
                includeGroupByRegex(".*google.*")
                includeGroupByRegex(".*android.*")
            }
        }
        maven("https://packages.jetbrains.team/maven/p/firework/dev") {
            isAllowInsecureProtocol = true
            mavenContent {
                includeVersionByRegex("org.jetbrains.*", ".*", ".*firework.*")
            }
        }
        maven {
            setUrl("https://jitpack.io")
            isAllowInsecureProtocol = true
        }

        maven{
            setUrl("http://maven.aliyun.com/nexus/content/repositories/releases/")
            isAllowInsecureProtocol = true
        }
        maven(url = "https://maven.aliyun.com/repository/public"){
            isAllowInsecureProtocol = true
        }
        maven {
            setUrl("https://maven.aliyun.com/repository/public/")
            isAllowInsecureProtocol = true
        }
        maven {
            setUrl("https://maven.aliyun.com/nexus/content/repositories/jcenter/")
            isAllowInsecureProtocol = true
        }
        maven {
            setUrl("https://maven.pkg.jetbrains.space/public/p/compose/dev")
            isAllowInsecureProtocol = true
        }

        maven {
            setUrl("https://dl.bintray.com/kotlin/kotlin-dev")
            isAllowInsecureProtocol = true
        }
        maven {
            setUrl("https://dl.bintray.com/kotlin/kotlin-eap")
            isAllowInsecureProtocol = true
        }

        maven("https://maven.pkg.jetbrains.space/kotlin/p/wasm/experimental"){
            isAllowInsecureProtocol = true
        }
        maven(url = "https://maven.pkg.jetbrains.space/kotlin/p/kotlin/dev"){
            isAllowInsecureProtocol = true
        }

        ivy {
            name = "Node.js"
            setUrl("https://nodejs.org/dist")
            patternLayout {
                artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]")
            }
            metadataSources {
                artifact()
            }
            content {
                includeModule("org.nodejs", "node")
            }
            isAllowInsecureProtocol = false
        }
        ivy {
            name = "Yarn"
            setUrl("https://github.com/yarnpkg/yarn/releases/download/")
            patternLayout {
                artifact("v[revision]/[artifact]-v[revision].[ext]")
            }
            metadataSources {
                artifact()
            }
            content {
                includeModule("com.yarnpkg", "yarn")
            }
            isAllowInsecureProtocol = false
        }

    }
}