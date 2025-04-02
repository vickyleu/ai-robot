@file:OptIn(InternalKotlinGradlePluginApi::class)

import org.gradle.kotlin.dsl.support.serviceOf
import org.jetbrains.kotlin.gradle.InternalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink
import org.jetbrains.kotlin.konan.target.CompilerOutputKind.PROGRAM
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.util.Properties
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("com.netflix.nebula.ospackage")
}

kotlin {
    // 配置目标平台
    @Suppress("DEPRECATION")
    linuxArm32Hfp {
        compilerOptions {
            this.freeCompilerArgs.addAll(
                "-linker-option",
                "--allow-shlib-undefined",
            )

        }
        binaries {
            executable {
                entryPoint = "main"
                baseName = "yanshee"
                // 如有需要，可添加链接器选项：
                val libYanPath =
                    project.layout.buildDirectory.get().asFile.resolve("cmake").absolutePath
                val libThiredpartyPath = file("src/nativeInterop/cpp/libs").absolutePath
                val sharedLibrary = file("src/linuxMain/resources/linuxArm32Hfp").absolutePath
                linkerOpts(
                    "-L${sharedLibrary}",
                    "-L${libYanPath}",
                    "-L${libThiredpartyPath}",
                    "-l:libyanapi.a",  // 使用静态库
                    "-lpython3.5m",
//                    "-fuse-ld=bfd",//强制使用 GNU ld 而非 LLD
                    "-lz",
                    "-lcrypto",
                    "-lssl",
                    "-lcurl",
                    "-lexpat",
                    "-Wl,-rpath,/usr/local/lib",
//                    "-static"         // 全静态链接（视情况可加）

                )
            }
        }
        // 配置cinterop以绑定Python方法
        compilations["main"].cinterops {
            // cinterop配置
            val pythonInterop by creating {
                defFile("src/nativeInterop/cinterop/pythonInterop.def")
                packageName("com.airobot.pythoninterop")
                // 从上面四个目录中查找所有的头文件,添加到headers.files
                includeDirs(
                    file("src/nativeInterop/cpp"),
                    file("src/nativeInterop/cpp/include/python3.5m"),
                    file("src/nativeInterop/cpp/include/python3.5m/numpy"),
                    file("src/nativeInterop/cpp/include")
                )
                compilerOpts(
//                    "-fsanitize=address",
                    "-fPIC",
//                    "-std=gnu99",//C模式,C++不能使用
                    // 指定硬浮点和 ARMv7 指令集
                    "-mfloat-abi=hard",
//                    "-march=armv7-a",
                    "-DCYTHON_EXTERN_C=",
//                    "-L~/.konan/dependencies/llvm-16.0.0-x86_64-macos-dev-56/lib/clang/16.0.0/lib/darwin", // 根据实际路径调整
                )
            }
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared.core)
            implementation(projects.shared.protocol)
            api(libs.androidx.annotation)
        }
        val nativeInterop by creating {
            this.kotlin.srcDir("src/nativeInterop/cinterop")
        }
        val pythonMain by creating {
            this.kotlin.srcDir("src/pythonMain/python")
        }
    }


//    tasks.named("linkDebugExecutableLinuxArm32Hfp").configure {
//        doFirst {
//            val link = (this@configure as KotlinNativeLink)
//            println("KotlinNativeLinkArtifactTask===${link.name}==${link.linkerOpts.joinToString("  ")}")
//            println("KotlinNativeLinkArtifactTask===${link.name}==${link.outputKind::class.java.simpleName }")
////            println("KotlinNativeLinkArtifactTask===${link.name}==${link.libraries.files.map{it.absolutePath}.joinToString("  ")}")
//        }
//    }


    tasks.named("linkDebugExecutableLinuxArm32Hfp").configure {
        val task = this as DefaultTask
        doFirst {
        }
    }
    tasks.named("linkReleaseExecutableLinuxArm32Hfp") {

    }
//    tasks.names.onEach {
//        tasks.named(it).configure {
//            println("taskName===>${it} simpleName=>${this::class.java.simpleName}")
//        }
//    }

//    tasks.withType<KotlinNativeBinaryContainer>().configureEach {
//
//    }

    tasks.withType<KotlinNativeLink>().configureEach {
        val link: KotlinNativeLink = this@configureEach
        when (this.outputKind) {
            PROGRAM -> {
                doFirst {
                    val allFields = link::class.memberProperties
                    println("allFields::${allFields.map { it.name }.joinToString()} ")
                    try {
                        val kFiled =
                            allFields.find { it.name == "apiFiles" } ?: return@doFirst
                        kFiled.isAccessible = true
                        val realField = (kFiled.call(link) ?: return@doFirst)
                        println(
                            "KotlinNativeLinkArtifactTask=====${
                                realField::class.java.simpleName
                            }"
                        )
                    } catch (e: Exception) {
                    }
                }
                val binary = link.binary
                val binaryDir = binary.outputDirectory
                doLast {
                    val originalFile = File(binaryDir, "${binary.baseName}.kexe")
                    if (originalFile.exists()) {
                        val renamedFile = File(binaryDir, binary.baseName)
                        originalFile.renameTo(renamedFile)
                    }
                }
            }

            else -> return@configureEach
        }
    }
    // 定义一个任务检查 CMake 是否存在
    tasks.register("checkCMake") {
        group = "小工具"
        @Suppress("DEPRECATION")
        doLast {
            val output = ByteArrayOutputStream()
            val result = exec {
                commandLine("/usr/local/bin/cmake", "--version")
                standardOutput = output
                // 如果 cmake 不存在，不要让任务直接失败，后面我们自己抛异常
                isIgnoreExitValue = true
            }
            if (result.exitValue != 0) {
                throw GradleException("CMake 未找到，请安装 CMake 并确保其在 PATH 中。")
            } else {
                println("检测到 CMake: " + output.toString().trim())
            }
        }
    }
    tasks.register<Exec>("configureNativeLib") {
        group = "小工具"
        dependsOn("checkCMake")
        workingDir = file("src/nativeInterop/cpp")
        // 向CMake传递Python路径参数
        commandLine(
            "/usr/local/bin/cmake",
            "-S", ".",
            "-B", "${project.layout.buildDirectory.asFile.get().resolve("cmake").absolutePath}",
            "-DCMAKE_TOOLCHAIN_FILE=${file("src/nativeInterop/cpp/toolchain.cmake").absolutePath}",
        )
    }
    // 定义一个任务，用于调用 CMake 编译 native 库
    tasks.create<Exec>("buildNativeLib") {
        group = "小工具"
        dependsOn("configureNativeLib")
        workingDir(file("src/nativeInterop/cpp"))// 指定 CMakeLists.txt 所在目录
        // 获取 Python 环境路径
        commandLine(
            "/usr/local/bin/cmake",
            "--build",
            "${project.layout.buildDirectory.asFile.get().resolve("cmake").absolutePath}",
        )
    }
    // 让 Kotlin/Native 编译任务依赖 buildNativeLib 任务
    tasks.withType<KotlinNativeCompile>().all {
        dependsOn(tasks["buildNativeLib"])
    }
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

val piHost: String by lazy { localProperties["piHost"] as String? ?: error("piHost is missing in local.properties") }
val piPort: String by lazy { localProperties["piPort"] as String? ?: error("piPort is missing in local.properties") }
tasks.register<com.netflix.gradle.plugins.deb.Deb>("distDeb") {
    dependsOn("linkReleaseExecutableLinuxArm32Hfp") // 构建Linux ARM32 HFP二进制文件的任务
    // 基本包信息
    packageName = "yanshee"
    version = "1.0.0"
    summary = project.name
    release = "1"
    setArch("armhf") // 修改为armv7架构，因为实际是armv7使用,换了gcc编译器的
    packageGroup = "utils"
    maintainer = "Vicky Leu <yu6564172@gmail.com>" // 替换为您的信息
    url = "https://github.com/vickyleu/ai-robot" // 替换为您的项目URL
    // 依赖项（如果需要特定的运行时）
//    requires("libpython3")
    @Suppress("UNUSED_VARIABLE")
    val fs = project.serviceOf<FileSystemOperations>()
    // 复制二进制文件
    from(project.layout.buildDirectory.get().asFile.resolve("bin/linuxArm32Hfp/releaseExecutable/${packageName}")) {
        into("/usr/local/bin")
//        rename("${packageName}.kexe", "${packageName}")
        @Suppress("DEPRECATION")
        this.fileMode = 0x755
//        filePermissions.set(fs.permissions("rwxr-xr-x")) // rwxr-xr-x (755)
    }
    from("src/linuxMain/resources/linuxArm32Hfp") {// 这个就是动态库的操作, 但是我需要LD_LIBRARY_PATH自动处理
        into("/usr/local/lib")
//        into("/usr/local/lib/${packageName}")
        @Suppress("DEPRECATION")
        this.fileMode = 0x755
//        filePermissions.set(fs.permissions("rwxr-xr-x")) // rwxr-xr-x (755)
    }
    // 复制资源文件（如果有）
    from("src/nativeMain/resources") {
        into("/usr/share/${packageName}/resources")
    }
    // 复制README和其他文档
    from("README.md") {
        into("/usr/share/doc/${packageName}")
    }
    // 复制桌面图标和.desktop文件（如果是GUI应用）
    from("src/nativeMain/resources/icons") {
        into("/usr/share/icons/hicolor/scalable/apps")
        include("*.svg")
    }
    from("src/nativeMain/resources") {
        into("/usr/share/applications")
        include("*.desktop")
    }
    // 如果需要配置文件
    from("src/nativeMain/resources/config") {
        into("/etc/${packageName}")
    }
    // 创建符号链接（可选）
    link("/usr/bin/${packageName}", "/usr/local/bin/${packageName}")

    doLast {
        // 直接scp将安装包发送到树莓派上面并运行, scp pi@192.168.1.11:/root/yanshee/ 密码是raspberry
        val deb = project.layout.buildDirectory.get().asFile.resolve(
            "distributions/${
                packageName
            }_${version}-${release}_${archStr}.deb"
        )
        println("deb ===>> ${deb.absolutePath}")
        if (deb.exists()) {
            // 使用sshpass传输deb包到树莓派
            println("piHost::$piHost  piPort:$piPort")
            val result0 = exec {
                commandLine(
                    "bash", "-c", "ssh-keyscan -p $piPort  $piHost  > /tmp/hostkey && sed -i '' '/ $piHost" +
                            "/d' ~/.ssh/known_hosts && cat /tmp/hostkey >> ~/.ssh/known_hosts && rm /tmp/hostkey"
                ).apply {
                    println("this:::${this.commandLine.joinToString("    ")}")
                }
            }
            val result = exec {
                commandLine(
                    "/usr/local/bin/sshpass", "-p",
                    "raspberry",
                    "scp",
                    "-P",
                    "$piPort", //"22",
                    "-o", "UserKnownHostsFile=/dev/null",
                    "-o", "StrictHostKeyChecking=no",
                    deb.absolutePath,
                    (
                            "pi@$piHost"
                                    +
                                    ":/tmp/"
                            )  // 确保路径可写
                ).apply {
                    println("this:::${this.commandLine.joinToString(" ")}")
                }
            }
            if (result.exitValue == 0) {
                // 在树莓派上安装deb包
                val result2 = exec {
                    commandLine(
                        "/usr/local/bin/sshpass", "-p",
                        "raspberry",
                        "ssh",
                        "-p", "$piPort", //"22",
                        "-o", "UserKnownHostsFile=/dev/null",
                        "-o", "StrictHostKeyChecking=no",
                        "pi@$piHost",
//                            "pi@192.168.1.11",
                        "sudo", "dpkg", "-i", "/tmp/${deb.name}"
                    )
                    // 确保标准输出和错误输出被重定向到控制台
                    standardOutput = System.out
                    errorOutput = System.err
                }
                val result3 = exec {
                    commandLine(
                        "/usr/local/bin/sshpass", "-p",
                        "raspberry",
                        "ssh",
                        "-p", "$piPort", //"22",
                        "-o", "UserKnownHostsFile=/dev/null",
                        "-o", "StrictHostKeyChecking=no",
                        "pi@$piHost",
//                            "pi@192.168.1.11",
                        "gdb", "/usr/local/bin/$packageName", "-ex", "run"
                    )
                    // 确保标准输出和错误输出被重定向到控制台
                    standardOutput = System.out
                    errorOutput = System.err
                }
                println("result:::${result3}")
            }
        } else {
            println("Deb file not found: ${deb.absolutePath}")
        }
    }
}




// 添加Cython任务
val cythonize by tasks.creating {
    group = "小工具"
    description = "Generate C++ code and header files from YanAPI.py using Cython"
    @Suppress("DEPRECATION")
    doLast {
        // 检查Cython是否已安装
        val result = exec {
            commandLine("/Users/vickyleu/.pyenv/versions/3.5.10/bin/python", "-m", "pip", "show", "cython")
            isIgnoreExitValue = true
        }

        // 如果Cython未安装，则安装它
        if (result.exitValue != 0) {
            exec {
                commandLine("/Users/vickyleu/.pyenv/versions/3.5.10/bin/python", "-m", "pip", "install", "cython")
            }
            // 安装后刷新环境
            exec {
                commandLine("/Users/vickyleu/.pyenv/versions/3.5.10/bin/python", "-m", "pip", "show", "cython")
            }
        }
        // 执行Cython命令，生成C++代码和头文件
        exec {
            commandLine(
                "/Users/vickyleu/.pyenv/versions/3.5.10/bin/python",
                "-m",
                "cython",
                "--gdb",
                "--annotate",
                "--cplus",
                "--force",
                "-X language_level=3",
                "-X binding=True ",
                "--3str",
                "--verbose",  // 打印详细过程
                "-o",
                "src/nativeInterop/cpp/source/YanAPI.cpp",
                "src/pythonMain/python/YanAPI.pyx"
            )
        }
    }
}
