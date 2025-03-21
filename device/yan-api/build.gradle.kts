import org.gradle.api.internal.file.DefaultConfigurableFilePermissions
import org.gradle.api.internal.file.DefaultFileSystemOperations
import org.gradle.kotlin.dsl.support.serviceOf
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile
import java.io.ByteArrayOutputStream
import java.nio.file.attribute.PosixFilePermissions

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("com.netflix.nebula.ospackage")
}

kotlin {
    // 配置目标平台
    linuxArm64 { // 针对Linux x64平台
        binaries {
            executable {
                entryPoint = "main"
                baseName = "yanshee"
                // 如有需要，可添加链接器选项：
                val libYanPath = project.layout.buildDirectory.get().asFile.resolve("cmake").absolutePath
                val libPythonPath = file("src/nativeInterop/cpp").absolutePath
                linkerOpts(
                    "-L${libYanPath}",
                    "-L${libPythonPath}",
                    "-l:libyanapi.a",  // 使用静态库
//                    "-lyanapi",
                    "-lpython3",
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
                    file("src/nativeInterop/cpp/include/python3.7m"),
                    file("src/nativeInterop/cpp/include/python3.7m/internal"),
                    file("src/nativeInterop/cpp/include")
                )
                compilerOpts("-DCYTHON_EXTERN_C=")
//                compilerOpts("-I${file("src/nativeInterop/cpp").absolutePath}")
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared.core)
            implementation(projects.shared.protocol)
        }

        linuxArm64Main.dependencies {
            // 平台特定依赖
        }
        val nativeInterop by creating {
            this.kotlin.srcDir("src/nativeInterop/cinterop")
        }
        val pythonMain by creating {
            this.kotlin.srcDir("src/pythonMain/python")
        }
    }


// 定义一个任务检查 CMake 是否存在
    tasks.register("checkCMake") {
        group = "小工具"
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
        commandLine("/usr/local/bin/cmake",
            "--build", "${project.layout.buildDirectory.asFile.get().resolve("cmake").absolutePath}",
        )
    }

// 让 Kotlin/Native 编译任务依赖 buildNativeLib 任务
    tasks.withType<KotlinNativeCompile>().all {
        dependsOn(tasks["buildNativeLib"])
    }

    /*afterEvaluate {
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink>().configureEach {
            if (outputKind != org.jetbrains.kotlin.konan.target.CompilerOutputKind.PROGRAM) return@configureEach
            val binary = this.binary
            val binaryDir = binary.outputDirectory
            doLast {
                val originalFile = File(binaryDir, "${binary.baseName}.kexe")
                val unusedFile = File(binaryDir, "${project.name}.kexe")
                if (originalFile.exists()) {
                    val renamedFile = File(binaryDir, binary.baseName)
                    originalFile.renameTo(renamedFile)
                }
                if (unusedFile.exists()) {
                    unusedFile.delete()
                }
            }
        }
    }*/
}


tasks.register<com.netflix.gradle.plugins.deb.Deb>("distDeb") {
    dependsOn("linuxArm64Binaries") // 假设这是构建Linux ARM64二进制文件的任务
    // 基本包信息
    packageName = "yanshee"
    version = "1.0.0"
    summary = project.name
    release = "1"
    packageGroup = "utils"
    maintainer = "Vicky Leu <yu6564172@gmail.com>" // 替换为您的信息
    url = "https://github.com/vickyleu/ai-robot" // 替换为您的项目URL
    // 依赖项（如果需要特定的运行时）
    requires("libpython3")
    // 复制二进制文件
    from(project.layout.buildDirectory.get().asFile.resolve("bin/linuxArm64/releaseExecutable/yanshee.kexe").apply {
        println("yanshee.kexe ==${this.absolutePath}")
    }) {
        into("/usr/local/bin")
        rename("yanshee.kexe", "yanshee")
        //0b111101101
        val fs = project.serviceOf<FileSystemOperations>()
        filePermissions.set(fs.permissions("rwxr-xr-x")) // rwxr-xr-x (755)
    }
    // 复制资源文件（如果有）
    from("src/linuxArm64Main/resources") {
        into("/usr/share/${project.name}/resources")
    }
    // 复制README和其他文档
    from("README.md") {
        into("/usr/share/doc/${project.name}")
    }
    // 复制桌面图标和.desktop文件（如果是GUI应用）
    from("src/linuxArm64Main/resources/icons") {
        into("/usr/share/icons/hicolor/scalable/apps")
        include("*.svg")
    }
    from("src/linuxArm64Main/resources") {
        into("/usr/share/applications")
        include("*.desktop")
    }
    // 如果需要配置文件
    from("src/linuxArm64Main/resources/config") {
        into("/etc/${project.name}")
    }
    // 创建符号链接（可选）
    link("/usr/bin/${project.name}", "/usr/local/bin/${project.name}")
}


// 添加Cython任务
val cythonize by tasks.creating {
    group = "小工具"
    description = "Generate C++ code and header files from YanAPI.py using Cython"
    doLast {
        // 检查Cython是否已安装
        val result = exec {
            commandLine("python3", "-m", "pip", "show", "cython")
            isIgnoreExitValue = true
        }

        // 如果Cython未安装，则安装它
        if (result.exitValue != 0) {
            exec {
                commandLine("python3", "-m", "pip", "install", "cython")
            }
            // 安装后刷新环境
            exec {
                commandLine("python3", "-m", "pip", "show", "cython")
            }
        }
        // 执行Cython命令，生成C++代码和头文件
        exec {
            // "--cplus",
            commandLine(
                "python3",
                "-m",
                "cython",
                "--cplus",
                "--embed",
                "--force",
                "--3str",
                "-o",
                "src/nativeInterop/cpp/YanAPI.cpp",
                "src/pythonMain/python/YanAPI.pyx"
            )
        }
    }
}