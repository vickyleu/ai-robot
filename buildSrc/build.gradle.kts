import org.gradle.kotlin.dsl.module

val realRootProject = rootProject.rootDir.parentFile
rootProject.layout.buildDirectory.set(file("${realRootProject.absolutePath}/${realRootProject.name}/subprojects/build/${rootProject.name}"))
plugins {
    // org.gradle.kotlin.kotlin-dsl:org.gradle.kotlin.kotlin-dsl.gradle.plugin:5.2.0
    `kotlin-dsl`
}
buildscript {
    repositories{
        gradlePluginPortal() {
            content {
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
    this.configurations.all {
        resolutionStrategy.eachDependency {
            if ((requested.group == "org.jetbrains.kotlin" || requested.group == "org.jetbrains")
                && requested.module.name.startsWith("kotlin")){
                useVersion(libs.versions.kotlin.asProvider().get())
            }
        }
    }
}

allprojects{
    this.configurations.all {
        // 所有group是org.jetbrains.kotlinx并且module包含coroutines的都替换成com.vickyleu.kotlinx.coroutines:原来的module:版本号
        resolutionStrategy.eachDependency {
            if ((requested.group == "org.jetbrains.kotlin" || requested.group == "org.jetbrains")
                && requested.module.name.startsWith("kotlin")){
                useVersion(libs.versions.kotlin.asProvider().get())
            }
        }
    }
}
configurations.all {
    resolutionStrategy {
        eachDependency {
            if(
                requested.group=="org.jetbrains.kotlin"
                && requested.module.name.startsWith("kotlin")
                ){
                useVersion(libs.versions.kotlin.asProvider().get())
            }
        }
    }
}