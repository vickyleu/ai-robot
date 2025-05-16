@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import com.airobot.DependencyRule
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

//需要判断是否是jitpack的构建，如果是jitpack的构建，需要将build目录设置到项目根目录下
if (System.getenv("JITPACK") == null) {
    val realRootProject = rootProject.rootDir
    rootProject.layout.buildDirectory.set(file("${realRootProject.absolutePath}/build/${rootProject.name}"))
}

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.js) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.rpc) apply false
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.ospackage) apply false
}
buildscript {
    repositories {

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
                excludeGroup("org.jetbrains.kotlin")
                excludeGroupByRegex("org.jetbrains.kotlin.*")
                excludeGroupByRegex("com.vickyleu.*")
            }
        }
    }
    configurations
        .all {
//            if(name.endsWith("NpmAggregated").not()){
                resolutionStrategy.eachDependency {

                    if (requested.group.startsWith("org.jetbrains.kotlinx.rpc.plugin") && requested.module.name.startsWith(
                            "org.jetbrains.kotlinx.rpc.plugin"
                        )
                    ) {
                        useVersion("0.6.1")
                    } else if ((requested.group == "org.jetbrains.kotlin" || requested.group == "org.jetbrains")
                        && requested.module.name.startsWith("kotlin")
                    ) {
                        useVersion(libs.versions.kotlin.asProvider().get())
                    } else if (requested.group == "org.jetbrains.kotlinx" && requested.module.name.startsWith(
                            "kotlinx-metadata"
                        )
                    ) {
                        useVersion("0.9.0")
                    }
                }
//            }
        }

}
allprojects {
    configurations
        .all {
//            if(name.endsWith("NpmAggregated").not()){
                // 所有group是org.jetbrains.kotlinx并且module包含coroutines的都替换成com.vickyleu.kotlinx.coroutines:原来的module:版本号
                resolutionStrategy.eachDependency {
                    DependencyRule.rules.firstOrNull { rule ->
                        requested.group == rule.group && rule.condition(requested.module.name)
                    }?.let { rule ->
                        useTarget(rule.targetVersion(requested.module.name))
                    } ?: run {
                        if (
                            requested.group == "org.jetbrains.kotlin"
                            && requested.module.name.startsWith("kotlin")
                        ) {
                            useVersion(libs.versions.kotlin.asProvider().get())
                        }
                    }
                }
//            }
        }
}
configurations
    .all {
//        if(name.endsWith("NpmAggregated").not()){
            resolutionStrategy {
                eachDependency {
                    if (
                        requested.group == "org.jetbrains.kotlin"
                        && requested.module.name.startsWith("kotlin")
                    ) {
                        useVersion(libs.versions.kotlin.asProvider().get())
                    }
                }
            }
//        }
    }

subprojects {
    this.layout.buildDirectory.set(
        file("${rootProject.layout.buildDirectory.get().asFile.absolutePath}/subprojects/${project.name}")
    )
    this.findProperty("kotlin")?.apply {
        if (this is org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension) {
            this.apply {
                kotlinDaemonJvmArgs = listOf("-Dkotlin.daemon.build.cache.dir=${this@subprojects.layout.buildDirectory.get().asFile.absolutePath}/cache")
            }
        }
    }
}


// 禁用不需要的模块的构建
gradle.taskGraph.whenReady {
    tasks.forEach { task ->
        if (task.project.name == "server" && !project.hasProperty("buildServer")) {
            task.enabled = false
        }
    }
}