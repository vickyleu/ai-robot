package com.airobot

data class DependencyRule(
    val group: String,
    val condition: (moduleName: String) -> Boolean,
    val targetVersion: (moduleName: String) -> String
){
    companion object{
        private val annotationMapping = hashMapOf(
            "android" to "jvm",
            "iosx64" to "uikitx64",
            "iosarm64" to "uikitarm64",
            "iossimulatorarm64" to "uikitsimarm64",
        )
        val rules = listOf(
            DependencyRule("androidx.annotation", { it.startsWith("annotation") }) {
                val module = if(it.startsWith("annotation-")){
                    annotationMapping.get(it.substring(startIndex = "annotation-".length))?.let {
                        "annotation-$it"
                    }?:it
                }else {it}
                "com.vickyleu.annotation:${module}:9999.0.0-SNAPSHOT"
            },
            DependencyRule("org.jetbrains.kotlinx", { it.startsWith("kotlinx-coroutines") }) {
                "com.vickyleu.kotlinx.coroutines:${it}:1.10.1-SNAPSHOT"
            },
            DependencyRule("org.jetbrains.kotlinx", { it.startsWith("kotlinx-html") }) {
                "com.vickyleu.kotlinx:${it}:0.12.0"
            },
            DependencyRule("org.jetbrains.kotlinx", { moduleName ->
                moduleName.startsWith("kotlinx-rpc") &&
                        moduleName != "kotlinx-rpc-bom" &&
                        !moduleName.startsWith("kotlinx-rpc-compiler")
            }) {
                "com.vickyleu.kotlinx:${it}:0.5.1"
            },
            DependencyRule("io.ktor", { it.startsWith("ktor") }) {
                "com.vickyleu.ktor:${it}:3.1.2-SNAPSHOT"
            },
            DependencyRule("pro.streem.pbandk", { true }) {
                "com.vickyleu.pbandk:${it}:0.16.1-SNAPSHOT"
            },
            DependencyRule("io.github.oshai", { true }) {
                "com.vickyleu.oshai:${it}:7.0.6"
            }
        )
    }
}
