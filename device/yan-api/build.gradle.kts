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
            }
        }
        // 配置cinterop以绑定Python方法
        compilations["main"].cinterops {
            create("PythonInterop") {
                definitionFile = file("src/nativeInterop/cinterop/PythonInterop.def")
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



}

// 添加Python任务
val runPython by tasks.creating(jython.JythonTask::class) {
    group = "application"
    description = "Run the YanAPI Python script"
    script(file("src/pythonMain/python/YanAPI.py"))
}

// 添加Cython任务
val cythonize by tasks.creating {
    group = "build"
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
            commandLine("python3", "-m", "cython",  "--cplus", "--embed","--generate-setup", "-o", "src/pythonMain/python/YanAPI.cpp", "src/pythonMain/python/YanAPI.py")
        }
    }
}