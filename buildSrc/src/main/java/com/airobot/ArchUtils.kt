package com.airobot

import org.gradle.kotlin.dsl.get
//import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
//import org.jetbrains.kotlin.gradle.dsl.KotlinTargetContainerWithPresetFunctions
//import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

@Suppress("DEPRECATION","Unused")
object ArchUtils {
    /*val KotlinMultiplatformExtension.linuxArmhfMain: KotlinSourceSet
        get() = this.sourceSets["linuxArmhf"]

    val KotlinMultiplatformExtension.linuxArmv7Main: KotlinSourceSet
        get() = this.sourceSets.getByName("linuxArmv7Main")

    fun KotlinTargetContainerWithPresetFunctions.linuxArmhf(
        name: String = "linuxArmhf",
        configure: (org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.() -> Unit) = {}
    ){
        linuxArm32Hfp(name = name,configure=configure)
    }
    fun KotlinTargetContainerWithPresetFunctions.linuxArmv7(
        name: String = "linuxArmv7",
        configure: (org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.() -> Unit) = {}
    ){
        linuxArm32Hfp(name = name,configure={
            configure(this)
            val overridenProperties = KonanModifier.konanProperties()
            this.compilerOptions.freeCompilerArgs.addAll(
                listOf(
                    "-Xoverride-konan-properties=${overridenProperties}",
                    "-Xverbose-phases=ObjectFiles,Linker,CStubs",
                    "-Xruntime-logs=gc=info"
                )
            )
        })
    }*/
}