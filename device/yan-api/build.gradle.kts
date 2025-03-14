import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile
import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("com.github.rzabini.gradle-jython") version "1.1.0"

}

kotlin {
    // 配置目标平台
    linuxArm64 { // 针对Linux x64平台
        binaries {
            executable {
                entryPoint = "main"
                // 如有需要，可添加链接器选项：
                linkerOpts("-L${file("src/nativeInterop/cpp/build").absolutePath}", "-lyanapi")
            }
        }
        // 配置cinterop以绑定Python方法
        compilations["main"].cinterops {
            // cinterop配置
            val pythonInterop by creating {
                defFile("src/nativeInterop/cinterop/pythonInterop.def")
                packageName("com.airobot.pythoninterop")
                // 获取Python头文件目录
                val pythonInclude = providers.exec {
                    commandLine("python3", "-c", "import sysconfig; print(sysconfig.get_path('include'))")
                }.standardOutput.asText.get().trim()
                /*headers((file("src/nativeInterop/cpp").absolutePath+"/YanAPI.h").apply {
                    // 如果YanAPI头文件不存在，抛出异常
                    if (!file(this).exists()) {
                        throw GradleException("YanAPI头文件未找到，请确保C++代码已生成。${this}")
                    }
                },
                    "$pythonInclude/Python.h".apply {
                        // 如果Python头文件不存在，抛出异常
                        println("Python头文件路径：$this")
                        if (!file(this).exists()) {
                            throw GradleException("Python头文件未找到，请确保Python已安装并在PATH中。${this}")
                        }
                    }
                )*/
//                // 包含项目自身的头文件目录和Python系统头文件
                includeDirs(file("src/nativeInterop/cpp").absolutePath,pythonInclude)
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
        val nativeInterop by creating{
            this.kotlin.srcDir("src/nativeInterop/cinterop")
        }
        val pythonMain by creating{
            this.kotlin.srcDir("src/pythonMain/python")
        }
    }
// 定义一个任务检查 CMake 是否存在
    tasks.register("checkCMake") {
        group = "小工具"
        doLast {
            val output = ByteArrayOutputStream()
            val result = exec {
                commandLine("cmake", "--version")
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
        // 获取 Python 环境路径
        val pythonPath = providers.exec {
            commandLine("python3", "-c", "import sysconfig; print(sysconfig.get_path('include'))")
        }.standardOutput.asText.get().trim()
        val pythonLibPath = providers.exec {
            commandLine("python3", "-c", "import sysconfig; print(sysconfig.get_config_var('LIBDIR'))")
        }.standardOutput.asText.get().trim()
        // 向CMake传递Python路径参数
        commandLine("cmake",
            "-S", ".",
            "-B", "build",
            "-DPYTHON_INCLUDE_DIR=$pythonPath",
            "-DPYTHON_LIBRARY=$pythonLibPath"
        )
    }
// 定义一个任务，用于调用 CMake 编译 native 库
    tasks.create<Exec>("buildNativeLib"){
        group = "小工具"
        dependsOn("configureNativeLib")
        workingDir(file("src/nativeInterop/cpp"))// 指定 CMakeLists.txt 所在目录
// 获取 Python 环境路径
        val pythonPath = providers.exec {
            commandLine("python3", "-c", "import sysconfig; print(sysconfig.get_path('include'))")
        }.standardOutput.asText.get().trim()
        val pythonLibPath = providers.exec {
            commandLine("python3", "-c", "import sysconfig; print(sysconfig.get_config_var('LIBDIR'))")
        }.standardOutput.asText.get().trim()
        commandLine("cmake", "--build", "build")
    }

// 让 Kotlin/Native 编译任务依赖 buildNativeLib 任务
    tasks.withType<KotlinNativeCompile>().all {
        dependsOn(tasks["buildNativeLib"])
    }
}


// 添加Python任务
val runPython by tasks.creating(jython.JythonTask::class) {
    group = "小工具"
    description = "Run the YanAPI Python script"
    script(file("src/pythonMain/python/YanAPI.py"))
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
            commandLine("python3", "-m", "cython", "--embed", "--force", "--3str",   "-o", "src/nativeInterop/cpp/YanAPI.c", "src/pythonMain/python/YanAPI.pyx")
        }
    }
}