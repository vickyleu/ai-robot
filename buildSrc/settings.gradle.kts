@file:Suppress("UnstableApiUsage")

import com.moandjiezana.toml.Toml
import org.gradle.kotlin.dsl.version


rootProject.name = "buildSrc"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

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
            gradlePluginPortal{
                content {
                    includeGroupByRegex("com.*")
                    includeGroupByRegex("org.*")
                    excludeGroupByRegex("com.vickyleu.*")
                }
            }

            google() {
                content {
                    excludeGroupByRegex("com.vickyleu.*")
                }
            }
            mavenCentral() {
                content {
                    excludeGroupByRegex("com.vickyleu.*")
                }
            }
        }
    }

    resolutionStrategy {
        eachPlugin {
            val namespace = requested.id.namespace?:return@eachPlugin
            val id = requested.id.name
            val toml = TomlQuery(file("../gradle/libs.versions.toml"))
            val kotlinVersion = toml.queryVersion("kotlin")
            if (namespace.startsWith("org.jetbrains.kotlinx.rpc.plugin") && id.startsWith("org.jetbrains.kotlinx.rpc.plugin")) {
                useVersion("0.6.1")
            }else if(namespace=="org.jetbrains"&& id.startsWith("kotlin")==true){
                useVersion(kotlinVersion)
            }
        }
    }
}

buildscript{
    repositories{
        mavenCentral()
        dependencies{
            classpath("io.hotmoka:toml4j:0.7.3")
        }
    }
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
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}