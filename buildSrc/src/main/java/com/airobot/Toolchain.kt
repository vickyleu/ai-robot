package com.airobot

import java.io.File

abstract class Toolchain(open val toolchainName: String) {
    abstract fun executeToolchain(
        execSpec: org.gradle.api.provider.ProviderFactory,
        externalToolchain: Boolean
    ): ToolchainAttr?

    fun File.getAllHeaderFiles(): List<File> {
        return this.listFiles()?.filter { file ->
            (file.isDirectory || file.extension == "h")
        }?.flatMap { file ->
            if (file.isDirectory) {
                file.getAllHeaderFiles()
            } else listOf(file)
        } ?: emptyList()
    }


    abstract fun externalUrl(): String

    data class ToolchainAttr(
        val toolchainUrl: String,
        val toolchainDownloadUrl: String,
        val toolchainName: String,
        val toolchainRoot: File,
        val toolchainBinDir: File,
        val toolchainDir: File,
        val toolchainLibDir: File,
        val cppRoot: File,
        val cppIncludeDir: File,
        val isFakeSource: Boolean,
        val gccroot: File,
        val sysroot: File,
        val sysrootLib: File,
        val sysInclude: File,
        val includedDirs: List<String>,
        val cmakeConfig: () -> List<String> = { emptyList() }
    ) {
        fun getCmakeConfig(): List<String> {
            return cmakeConfig().filter {
                it.trim().isNotEmpty()
                        && it.trim().startsWith("-D")
                        && it.trim().contains("=")
            }.apply {
                println("cmakeConfig: \n${this.joinToString("\n")}")
            }
        }
    }

}