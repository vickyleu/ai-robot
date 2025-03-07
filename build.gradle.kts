//需要判断是否是jitpack的构建，如果是jitpack的构建，需要将build目录设置到项目根目录下
if (System.getenv("JITPACK") == null) {
    System.getenv().forEach {
        println("key=${it.key},value=${it.value}")
    }
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
    alias(libs.plugins.wire) apply false
}
subprojects {
    this.layout.buildDirectory.set(
        file("${rootProject.layout.buildDirectory.get().asFile.absolutePath}/subprojects/${project.name}")
    )
}


// 禁用不需要的模块的构建
gradle.taskGraph.whenReady {
    tasks.forEach { task ->
        if (task.project.name == "server" && !project.hasProperty("buildServer")) {
            task.enabled = false
        }
    }
}