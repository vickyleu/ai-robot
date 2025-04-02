@file:Suppress("UnstableApiUsage")

include(":shared:generator:generateJvm")



rootProject.name = "ai-robot"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// GO 后端
include(":backend")
include(":shared")
// 核心模块
include(":shared:core")
//// 协议模块
include(":shared:protocol")
//// 协议模块 - 代码生成 停止在共享代码中使用grpc, 目前没有KMM 通用方案
include(":shared:generator")
include(":shared:generator:protobuf-codegen")
include(":shared:generator:service-gen")

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
    class TomlQuery(file:File) {
        /**
         * 解析 libs.versions.toml 文件并按部分分组
         */
        fun parseLibsVersionsToml(file: File): Map<String, Map<String, String>> {
            val sections = mutableMapOf<String, MutableMap<String, String>>()
            var currentSection = ""

            // 读取文件并处理每一行
            file.readLines().forEach { line ->
                val trimmedLine = line.trim()

                // 跳过空行和注释行
                if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
                    return@forEach
                }

                // 检查是否是新的部分（如 [versions], [libraries] 等）
                if (trimmedLine.startsWith("[") && trimmedLine.endsWith("]")) {
                    currentSection = trimmedLine.substring(1, trimmedLine.length - 1)
                    sections[currentSection] = mutableMapOf()
                    return@forEach
                }

                // 如果已经在某个部分中，则解析键值对
                if (currentSection.isNotEmpty() && "=" in trimmedLine) {
                    val parts = trimmedLine.split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim().removeSurrounding("\"").removeSurrounding("'")
                        sections[currentSection]?.put(key, value)
                    }
                }
            }

            return sections
        }
        val properties:Map<String, Map<String, String>> = parseLibsVersionsToml(file)

        fun queryVersion(key: String): String{
            return properties["versions"]?.getOrElse(key) { null }?:throw Exception("Key not found: $key")
        }
    }

    repositories.apply {
        removeAll(this)
    }
    dependencyResolutionManagement.repositories.apply {
        removeAll(this)
    }
    listOf(repositories, dependencyResolutionManagement.repositories).forEach {
        it.apply {
            maven {
                url =
                    uri("https://raw.githubusercontent.com/vickyleu/kotlin_linuxarm32hfp_maven/main")
                content {
                    includeGroupByRegex("com.vickyleu.*")
                }
            }
            gradlePluginPortal{
                content {
                    includeGroupByRegex("com.*")
                    includeGroupByRegex("org.*")
                    excludeGroupByRegex("com.vickyleu.*")
                }
            }

            google {
                content {
                    excludeGroupByRegex("com.vickyleu.*")
                }
            }
            mavenCentral {
                content {
                    excludeGroupByRegex("com.vickyleu.*")
                }
            }

            maven(url = "https://androidx.dev/storage/compose-compiler/repository") {
                content {
                    excludeGroupByRegex("com.github.*")
                    excludeGroupByRegex("com.vickyleu.*")
                }
            }
            maven(url = "https://maven.pkg.jetbrains.space/public/p/compose/dev") {
                content {
                    excludeGroupByRegex("com.vickyleu.*")
                }
            }
            maven(url = "https://maven.pkg.jetbrains.space/kotlin/p/kotlin/dev") {
                content {
                    excludeGroupByRegex("com.vickyleu.*")
                }
            }
            maven("https://maven.pkg.jetbrains.space/public/p/krpc/grpc") {
                content {
                    excludeGroupByRegex("com.vickyleu.*")
                }
            }
            maven {
                url =
                    uri("https://raw.githubusercontent.com/vickyleu/kotlin_linuxarm32hfp_maven/main")
                content {
                    includeGroupByRegex("com.vickyleu.*")
                }
            }

        }
    }
    resolutionStrategy {
        eachPlugin {
            val namespace = requested.id.namespace?:return@eachPlugin
            val id = requested.id.name
            val toml = TomlQuery(file("gradle/libs.versions.toml"))
            val kotlinVersion = toml.queryVersion("kotlin")
            if (namespace.startsWith("org.jetbrains.kotlinx.rpc") && id.startsWith("plugin")) {
                useModule("com.vickyleu.kotlinx.rpc:org.jetbrains.kotlinx.rpc.plugin.gradle.plugin:0.6.1")
            }else if(namespace=="org.jetbrains"&& id.startsWith("kotlin")==true){
                useVersion(kotlinVersion)
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}


dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral {
            content {
                excludeGroup("org.jetbrains.kotlin")
                excludeGroupByRegex("org.jetbrains.kotlin.*")
                excludeGroupByRegex("com.vickyleu.*")
            }
        }
        google {
            content {
                includeGroupByRegex(".*google.*")
                includeGroupByRegex(".*android.*")
                excludeGroupByRegex("org.jetbrains.*")
                excludeGroupByRegex("com.vickyleu.*")
            }
        }
        maven("https://packages.jetbrains.team/maven/p/firework/dev") {
            content {
                excludeGroupByRegex("org.jetbrains.*rpc*")
                excludeGroupByRegex("com.vickyleu.*")
            }
        }
        maven {
            setUrl("https://jitpack.io")
            content {
                excludeGroupByRegex("org.jetbrains.*")
                excludeGroupByRegex("com.vickyleu.*")
            }
        }
        maven {
            setUrl("http://maven.aliyun.com/nexus/content/repositories/releases/")
            isAllowInsecureProtocol = true
            content {
                excludeGroupByRegex("org.jetbrains.*")
                excludeGroupByRegex("com.vickyleu.*")
            }
        }
        maven(url = "https://maven.aliyun.com/repository/public") {
            content {
                excludeGroupByRegex("org.jetbrains.*")
                excludeGroupByRegex("com.vickyleu.*")
            }
        }
        maven {
            setUrl("https://maven.aliyun.com/repository/public/")
            content {
                excludeGroupByRegex("org.jetbrains.*")
                excludeGroupByRegex("com.vickyleu.*")
            }
        }
        maven {
            setUrl("https://maven.aliyun.com/nexus/content/repositories/jcenter/")
            content {
                excludeGroupByRegex("org.jetbrains.*")
                excludeGroupByRegex("com.vickyleu.*")
            }
        }
        maven {
            setUrl("https://maven.pkg.jetbrains.space/public/p/compose/dev")
            content {
                excludeGroupByRegex("org.jetbrains.*rpc*")
                excludeGroupByRegex("com.vickyleu.*")
            }
        }
        maven {
            setUrl("https://dl.bintray.com/kotlin/kotlin-dev")
            content {
                excludeGroupByRegex("org.jetbrains.*")
                excludeGroupByRegex("com.vickyleu.*")
            }
        }
        maven {
            setUrl("https://dl.bintray.com/kotlin/kotlin-eap")
            content {
                excludeGroupByRegex("org.jetbrains.*rpc*")
                excludeGroupByRegex("com.vickyleu.*")
            }
        }
        maven("https://maven.pkg.jetbrains.space/kotlin/p/wasm/experimental") {
            content {
                excludeGroupByRegex("org.jetbrains.*rpc*")
                excludeGroupByRegex("com.vickyleu.*")
            }
        }
        maven(url = "https://maven.pkg.jetbrains.space/kotlin/p/kotlin/dev") {
            content {
                excludeGroupByRegex("org.jetbrains.*rpc*")
                excludeGroupByRegex("com.vickyleu.*")
            }
        }
        maven("https://maven.pkg.jetbrains.space/public/p/krpc/grpc") {
            content {
                excludeGroupByRegex("com.vickyleu.*")
            }
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
                excludeGroupByRegex("com.vickyleu.*")
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
                excludeGroupByRegex("com.vickyleu.*")
            }
            isAllowInsecureProtocol = false
        }
        ivy {
            name = "WebAssembly"
            setUrl("https://github.com/WebAssembly/binaryen/releases/download/")
            //https://github.com/WebAssembly/binaryen/releases/download/version_119/binaryen-version_119-arm64-macos.tar.gz
            patternLayout {
                artifact("version_[revision]/[artifact]-(version_[revision]-[classifier]).[ext]")
            }
            metadataSources {
                artifact()
            }
            content {
                includeModule("com.github.webassembly", "binaryen")
                excludeGroupByRegex("com.vickyleu.*")
            }
            isAllowInsecureProtocol = false
        }

        maven {
            url = uri("https://raw.githubusercontent.com/vickyleu/kotlin_linuxarm32hfp_maven/main")
            content {
                includeGroupByRegex("com.vickyleu.*")
            }
        }
    }
}