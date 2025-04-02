package com.airobot

import java.io.File

@Suppress("SpellCheckingInspection")
sealed class LinuxToolchain(override val toolchainName: String) : Toolchain(toolchainName) {
    override fun executeToolchain(
        execSpec: org.gradle.api.provider.ProviderFactory,
        externalToolchain: Boolean
    ): ToolchainAttr? {
        TODO("Not yet implemented")
    }

    override fun externalUrl(): String {
        TODO("Not yet implemented")
    }


    object Armhfp : LinuxToolchain("armv7-unknown-linux-gnueabihf") {
        private val env = System.getenv().map { it.key.lowercase() to it.value }.toMap()

        // 获取到home目录
        private val home: String by env
        private fun String.normalizeOSName(): String = when (this) {
            "macosx" -> "macos"
            else -> this
        }

        // 获取到当前操作系统
        private val os: String = System.getProperty("os.name")
            .lowercase()
            .replace(" ", "")
            .normalizeOSName()

        // 获取到当前操作系统的架构
        private val konanDependenciesDir = File(
            "$home/.konan/dependencies"
        )
        private val hostArch = when (System.getProperty("os.arch").lowercase()) {
            "aarch64" -> "arm64"
            "x86_64", "x64" -> "x64"
            "arm" -> "arm"
            "x86" -> "x86"
            else -> System.getProperty("os.arch").lowercase()
        }
        private val hostGccTag = when (hostArch) {
            "arm64", "arm" -> "arm64"
            "x64", "x86" -> "x86_64"
            else -> hostArch
        }

        override fun externalUrl(): String {
            return "https://media.githubusercontent.com/media/vickyleu/gcc_maven/refs/heads/main/$hostGccTag"
        }

        override fun executeToolchain(
            execSpec: org.gradle.api.provider.ProviderFactory,
            externalToolchain: Boolean
        ): ToolchainAttr? {
            try {
                var toolchainName =
                    if (externalToolchain) this.toolchainName else "arm-unknown-linux-gnueabihf"
                var toolchainRootDirName =
                    if (externalToolchain) this.toolchainName else "arm-unknown-linux-gnueabihf-gcc-8.3.0-glibc-2.12.1-kernel-4.9-1"
                var toolchainRoot = File(konanDependenciesDir, toolchainRootDirName)
                var isFakeSource = false
                if (externalToolchain && toolchainRoot.exists().not()) {
                    toolchainName = "arm-unknown-linux-gnueabihf"
                    toolchainRootDirName =
                        "arm-unknown-linux-gnueabihf-gcc-8.3.0-glibc-2.12.1-kernel-4.9-1"
                    toolchainRoot = File(konanDependenciesDir, toolchainRootDirName)
                    isFakeSource = true
                    println("是假的哦")
                }
                println("armhf gcc : $toolchainName")
                val toolchainDir = File(toolchainRoot, toolchainName)
                println("toolchainDir::${toolchainDir.absolutePath}")
                val toolchainBinDir = File(toolchainRoot, "bin")
                val toolchainLib = File(toolchainDir, "lib/")
                var gccRoot = File(toolchainRoot, "lib/gcc/$toolchainName/")
                val latestVersion = gccRoot.listFiles()
                    ?.filter { it.isDirectory }
                    ?.maxBy { it.name }
                    ?.name
                    ?: (if (externalToolchain) "___Please wait for the external toolchain to be downloaded!!___" else "8.3.0")
                gccRoot = gccRoot.resolve(latestVersion)


                val sysRoot = File(toolchainRoot, "$toolchainName/sysroot")
                val sysrootLib = File(sysRoot, "lib")
                val cppRoot = File("${sysRoot.parentFile.absolutePath}/include/c++/$latestVersion")
                val cppIncludeDir = File(cppRoot, toolchainName)

                val sysInclude = File("${sysRoot.absolutePath}/usr/include")
                println("gcc version: $latestVersion")
                return ToolchainAttr(
                    toolchainUrl = externalUrl(),
                    toolchainDownloadUrl = "${externalUrl()}/${this.toolchainName}.tar.gz",
                    toolchainName = toolchainName,
                    toolchainRoot = toolchainRoot,
                    toolchainBinDir = toolchainBinDir,
                    toolchainDir = toolchainDir,
                    toolchainLibDir = toolchainLib,
                    gccroot = gccRoot,
                    sysroot = sysRoot,
                    sysrootLib = sysrootLib,
                    sysInclude = sysInclude,
                    isFakeSource = isFakeSource,
                    cppRoot = cppRoot,
                    cppIncludeDir = cppIncludeDir,
                    includedDirs = listOf(
                        sysInclude,
                        cppRoot,
                        sysrootLib,
                        cppIncludeDir,
                        toolchainLib,
                        gccRoot
                    ).map { it.absolutePath },
                    cmakeConfig = {
                        listOf(
                            "-DCMAKE_ARMHF_TOOLCHAIN_CPPROOT=${cppRoot.absolutePath}",
                            "-DCMAKE_ARMHF_TOOLCHAIN_SYSROOT=${sysRoot.absolutePath}",
                            "-DCMAKE_ARMHF_TOOLCHAIN_GCCROOT=${gccRoot.absolutePath}",
                            "-DCMAKE_ARMHF_TOOLCHAIN_LIB_DIR=${toolchainLib.absolutePath}",
                            "-DCMAKE_ARMHF_TOOLCHAIN_ROOT=${toolchainRoot.absolutePath}",
                            "-DCMAKE_ARMHF_TOOLCHAIN_BIN_DIR=${toolchainBinDir.absolutePath}",
                            "-DCMAKE_ARMHF_TOOLCHAIN_DIR=${toolchainDir.absolutePath}",
                            "-DCMAKE_ARMHF_TOOLCHAIN_CPP_INCLUDE=${cppIncludeDir.absolutePath}",
                            "-DCMAKE_ARMHF_TOOLCHAIN_ARCH=${toolchainName}"
                        )
                    }
                )
            } catch (e: Exception) {
                e.printStackTrace()
                throw RuntimeException("??? ${e.message}")
            }
        }
    }
}