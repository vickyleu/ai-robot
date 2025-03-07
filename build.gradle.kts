import com.airobot.DependencyRule
import org.gradle.kotlin.dsl.module

//需要判断是否是jitpack的构建，如果是jitpack的构建，需要将build目录设置到项目根目录下
if (System.getenv("JITPACK") == null) {
    /*System.getenv().forEach {
        println("key=${it.key},value=${it.value}")
    }*/
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
    id("com.netflix.nebula.ospackage") version "11.11.1" apply false
}
buildscript {
    repositories{

        gradlePluginPortal() {
            content {
                excludeGroup("org.jetbrains.kotlin")
                excludeGroupByRegex("org.jetbrains.kotlin.*")
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
                excludeGroup("org.jetbrains.kotlin")
                excludeGroupByRegex("org.jetbrains.kotlin.*")
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
            DependencyRule.rules.firstOrNull { rule ->
                requested.group == rule.group && rule.condition(requested.module.name)
            }?.let { rule ->
                useTarget(rule.targetVersion(requested.module.name))
            }
            if(
                requested.group=="org.jetbrains.kotlin"
                && requested.module.name.startsWith("kotlin")
            ){
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
subprojects {
    this.layout.buildDirectory.set(
        file("${rootProject.layout.buildDirectory.get().asFile.absolutePath}/subprojects/${project.name}")
    )
    this.gradle.afterProject {
        this.gradle.sharedServices.registrations.map {
//            it as DefaultBuildServicesRegistry
//            println("${this@subprojects.name}  this.gradle.sharedServices.registrations=${it::class.java.simpleName}")
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