@file:OptIn(InternalKotlinGradlePluginApi::class)

import com.airobot.KonanDownloader
import com.airobot.KonanModifier
import com.airobot.LinuxToolchain.Armhfp
import org.gradle.internal.extensions.stdlib.capitalized
import org.gradle.kotlin.dsl.support.serviceOf
import org.jetbrains.kotlin.gradle.InternalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink
import org.jetbrains.kotlin.konan.target.CompilerOutputKind.PROGRAM
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.OutputStream
import java.util.Properties
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ospackage)
}
val externalToolchain = properties["ai.use.externalToolchain"].toString().toBooleanStrictOrNull() == true
val armhfToolchain = Armhfp.executeToolchain(providers,externalToolchain) ?: error("无法找到工具链")
kotlin {
    this.compilerOptions {
        // 设置编译器选项
        freeCompilerArgs.addAll(
            listOf(
                "-Xskip-prerelease-check",
                "-Xskip-metadata-version-check",
                "-Xopt-in=kotlinx.cinterop.ExperimentalForeignApi",
            )
        )
    }
    // 配置目标平台
    @Suppress("DEPRECATION")
    linuxArm32Hfp {
        val overridenProperties = KonanModifier.konanProperties(
            toolchainUrl = armhfToolchain.toolchainUrl,
            externalToolchain = externalToolchain,
        )
        compilerOptions.freeCompilerArgs.addAll(
            mutableListOf<String>(
                // others
            ).apply {
                if(overridenProperties.isNotBlank()){
                    this.add("-Xoverride-konan-properties=${overridenProperties}",)
                }
                if(externalToolchain.not()){
                    addAll(listOf(
                        "-Xverbose-phases=ObjectFiles,Linker,CStubs",
                        "-Xruntime-logs=gc=info"
                    ))
                }
            }
        )
        binaries {
            // 如有需要，可添加链接器选项：
            val libYanPath =
                project.layout.buildDirectory.get().asFile.resolve("cmake").absolutePath
            val libThirdpartyPath = file("src/nativeInterop/cpp/libs").absolutePath
            val sharedLibrary = file("src/linuxMain/resources/linuxArm32Hfp").absolutePath
            /*staticLib{
                // Add the -fopenmp flag to linker options
                // 添加链接器选项
                linkerOpts.addAll(listOf(
                    "-lstdc++",
                    "-lm",
                    "-L${libThirdpartyPath}",
                    "-l:libyanapi.a",
                    "-l:libvosk.a",
                    "-l:libwhisper.a",
                ))
            }
            sharedLib{
                linkerOpts.addAll(listOf(
                    "-L${sharedLibrary}",
                    "-L${libYanPath}",
                    "-lm",       // 数学库
                    "-lasound",
                    "-lpython3.5m",
                    "-lcrypto",
                    "-lssl",
                    "-lcurl",
                    "-lexpat",
                    "-lm",
                    "-lz",
                ))
            }*/
            executable {
                entryPoint = "main"
                baseName = "yanshee"
                linkerOpts(
                    "-lstdc++",
                    "-lm",       // 数学库
                    "-L${sharedLibrary}",
                    "-L${libYanPath}",
                    "-L${libThirdpartyPath}",
                  "-Wl,--allow-multiple-definition",// 允许符号重复定义
//                     强制链接静态库 START
                    "-Wl,--whole-archive",
                    "-lsnowboy",
                    "-lyanapi",
                    "-lvosk",
                    "-lportaudio",
                    "-lgomp",
                    "-lwhisper",
                    "-lpiper",
                    "-lopencc",
                    "-latomic",
                    "-Wl,--no-whole-archive",
                    // 强制链接静态库 END
                    // 显式链接顺序
                    "-l:libsnowboy.a",
                    "-l:libyanapi.a",
                    "-l:libvosk.a",
                    "-l:libportaudio.a",
                    "-l:libwhisper.a",
                    "-l:libopencc.a",
                    "-l:libpiper.a",
                    "-lasound",
                    "-lpython3.5m",
                    "-lcrypto",
                    "-lssl",
                    "-lcurl",
                    "-lexpat",
                    "-lm",
                    "-lz",
                    "-Wl,--gc-sections",
                    "-Wl,-rpath,/usr/local/lib/$baseName",
                    // 补丁,替换C23符号
//                    "-Wl,--wrap=__isoc23_strtol",
//                    "-Wl,--wrap=__isoc23_strtoll",
//                    "-Wl,--wrap=__isoc23_strtoull_l",
//                    "-Wl,--wrap=__isoc23_strtoll_l",
//                    "-Wl,-T,${file("src/nativeInterop/patch/wrap_symbols.ld").absolutePath}",
                )
            }
        }
        // 配置cinterop以绑定Python方法
        compilations["main"].cinterops {
            if(armhfToolchain.isFakeSource.not()){
                // cinterop配置
                create("python") {
                    defFile("src/nativeInterop/cinterop/pythonInterop.def")
                    packageName("com.airobot.pythoninterop")
                    // 从上面四个目录中查找所有的头文件,添加到headers.files
                    includeDirs(
                        file("src/nativeInterop/cpp"),
                        file("src/nativeInterop/cpp/include/python/"),
                        file("src/nativeInterop/cpp/include/python/python3.5m"),
                        file("src/nativeInterop/cpp/include/python/python3.5m/numpy"),
                    )
                    compilerOpts(
                        "-fPIC",
                        "-DCYTHON_EXTERN_C=extern\n\"C\"",
                        "-DPY_MAJOR_VERSION=3",
                        "-DWITH_THREAD=1",
                        "-DPY_MINOR_VERSION=5",
                        "-DPY_MICRO_VERSION=0",
                        "-DPY_RELEASE_LEVEL=0xF",
                        "-DPY_RELEASE_SERIAL=0",
                        "-DPY_VERSION_HEX=0x030500F0",
                        "-nostdinc++",
                        "-std=c++17",
                        "-D_GLIBCXX_USE_CXX11_ABI=1",
                        "-ffunction-sections",
                        "-fdata-sections",
                        *armhfToolchain.includedDirs.map { "-I$it" }.toTypedArray()
                    )
                }
                create("alsa") {
                    defFile("src/nativeInterop/cinterop/alsa.def")
                    packageName("com.airobot.alsainterop")
                    // 从上面四个目录中查找所有的头文件,添加到headers.files
                    includeDirs(
                        file("src/nativeInterop/cpp/include/"),
                    )
                    compilerOpts(
                        "-fPIC",
                        "-nostdinc++",
                        "-std=c++17",
                        "-D_GLIBCXX_USE_CXX11_ABI=1",
                        "-ffunction-sections",
                        "-fdata-sections",
                        *armhfToolchain.includedDirs.map { "-I$it" }.toTypedArray()
                    )
                }
                create("portaudio") {
                    defFile("src/nativeInterop/cinterop/portaudio.def")
                    packageName("com.airobot.portaudiointerop")
                    // 从上面四个目录中查找所有的头文件,添加到headers.files
                    includeDirs(
                        file("src/nativeInterop/cpp/include/portaudio/"),
                    )
                    compilerOpts(
                        "-fPIC",
                        "-nostdinc++",
                        "-std=c++17",
                        "-D_GLIBCXX_USE_CXX11_ABI=1",
                        "-ffunction-sections",
                        "-fdata-sections",
                        *armhfToolchain.includedDirs.map { "-I$it" }.toTypedArray()
                    )
                }
                create("snowboy") {
                    // https://github.com/seasalt-ai/snowboy
                    defFile("src/nativeInterop/cinterop/snowboy.def")
                    packageName("com.airobot.snowboyinterop")
                    // 从上面四个目录中查找所有的头文件,添加到headers.files
                    includeDirs(
                        file("src/nativeInterop/cpp/include/snowboy/"),
                        file("src/nativeInterop/cpp/include/snowboy/hack"),
                    )
                    compilerOpts(
                        "-fPIC",
                        "-nostdinc++",
                        "-std=c++17",
                        "-D_GLIBCXX_USE_CXX11_ABI=1",
                        "-ffunction-sections",
                        "-fdata-sections",
                        *armhfToolchain.includedDirs.map { "-I$it" }.toTypedArray()
                    )
                }
                create("opencc") {
                    defFile("src/nativeInterop/cinterop/opencc.def")
                    packageName("com.airobot.openccinterop")
                    // 从上面四个目录中查找所有的头文件,添加到headers.files
                    includeDirs(
                        file("src/nativeInterop/cpp/include/"),
                        file("src/nativeInterop/cpp/include/opencc/hack"),
                    )
                    compilerOpts(
                        "-fPIC",
                        "-nostdinc++",
                        "-std=c++17",
                        "-D_GLIBCXX_USE_CXX11_ABI=1",
                        "-ffunction-sections",
                        "-fdata-sections",
                        *armhfToolchain.includedDirs.map { "-I$it" }.toTypedArray()
                    )
                }
                create("piper") {
                    defFile("src/nativeInterop/cinterop/piper.def")
                    packageName("com.airobot.piperinterop")
                    // 从上面四个目录中查找所有的头文件,添加到headers.files
                    includeDirs(
                        file("src/nativeInterop/cpp/include"),
                        file("src/nativeInterop/cpp/include/piper"),
                        file("src/nativeInterop/cpp/include/piper/soxr"),
                        file("src/nativeInterop/cpp/include/piper/onnxruntime"),
                    )
                    compilerOpts(
                        "-fPIC",
                        "-nostdinc++",
                        "-std=c++17",
                        "-pthread",
                        "-D__STDC_LIMIT_MACROS",
                        "-D_GLIBCXX_USE_CXX11_ABI=1",
                        "-ffunction-sections",
                        "-fdata-sections",
                        *armhfToolchain.includedDirs.map { "-I$it" }.toTypedArray()
                    )
                }
                create("vosk") {
                    defFile("src/nativeInterop/cinterop/vosk.def")
                    packageName("com.airobot.voskinterop")
                    // 从上面四个目录中查找所有的头文件,添加到headers.files
                    includeDirs(
                        file("src/nativeInterop/cpp/include/vosk"),
                        file("src/nativeInterop/cpp/include/vosk/kaldi"),
                        file("src/nativeInterop/cpp/include/vosk/fst"),
                        file("src/nativeInterop/cpp/include/vosk/hack"),
                    )
                    compilerOpts(
                        "-fPIC",
                        "-nostdinc++",
                        "-std=c++17",
                        "-D_GLIBCXX_USE_CXX11_ABI=1",
                        "-ffunction-sections",
                        "-fdata-sections",
                        "-L${file("src/nativeInterop/cpp/libs/").absolutePath}",
                        *armhfToolchain.includedDirs.map { "-I$it" }.toTypedArray()
                    )
                }
                create("whisper") {
                    defFile("src/nativeInterop/cinterop/whisper.def")
                    packageName("com.airobot.whisperinterop")
                    // 从上面四个目录中查找所有的头文件,添加到headers.files
                    includeDirs(
                        file("src/nativeInterop/cpp/include/whisper"),
                        file("src/nativeInterop/cpp/include/whisper/hack"),
                    )
                    compilerOpts(
                        "-fPIC",
                        "-nostdinc++",
                        "-fopenmp",
                        "-std=c++17",
                        "-pthread",
                        "-D_GLIBCXX_USE_CXX11_ABI=1",
                        "-ffunction-sections",
                        "-fdata-sections",
                        "-L${file("src/nativeInterop/cpp/libs/").absolutePath}",
                        *armhfToolchain.includedDirs.map { "-I$it" }.toTypedArray()
                    )
                }
            }else{
                create("fake"){
                    defFile("src/nativeInterop/cinterop/fake.def")
                    packageName("com.airobot.fakeinterop")
                    compilerOpts(
                        "-fPIC",
                        "-nostdinc++",
                        "-std=c++17",
                        "-D_GLIBCXX_USE_CXX11_ABI=1",
                        *armhfToolchain.includedDirs.map { "-I$it" }.toTypedArray()
                    )
                }
            }
        }
    }

    @Suppress("UNUSED")
    sourceSets {
        if(armhfToolchain.isFakeSource){
            linuxMain.get().kotlin.include("**/FakeMain.kt")
            commonMain.get().kotlin.exclude("**/**.kt")
            nativeMain.get().kotlin.exclude("**/**.kt")
            linuxMain.get().kotlin.exclude("**/**.kt")
            KonanDownloader.getInstance().startDownloadToolchain(project,
                armhfToolchain.toolchainDownloadUrl,
            )
        }else{
            linuxMain.get().kotlin.exclude("**/FakeMain.kt")
        }
        commonMain.dependencies {
            implementation(projects.shared.core)
            implementation(projects.shared.protocol)
            api(libs.androidx.annotation)
        }
        if(armhfToolchain.isFakeSource.not()){
            create("nativeInterop"){
                this.kotlin.srcDir("src/nativeInterop/cinterop")
                this.languageSettings.optIn("kotlin.ExperimentalMultiplatform")
            }
            create("pythonMain"){
                this.kotlin.srcDir("src/pythonMain/python")
                this.languageSettings.optIn("kotlin.ExperimentalMultiplatform")
            }
        }
    }
    if(armhfToolchain.isFakeSource.not()){
        tasks.withType<KotlinNativeLink>().configureEach {
            val link: KotlinNativeLink = this@configureEach
            when (this.outputKind) {
                PROGRAM -> {
                    doFirst {
                        val allFields = link::class.memberProperties
                        try {
                            val kFiled =
                                allFields.find { it.name == "apiFiles" } ?: return@doFirst
                            kFiled.isAccessible = true
//                        val realField = (kFiled.call(link) ?: return@doFirst)
                        } catch (_: Exception) {
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
                }
            }
        }
        tasks.register<Exec>("configureNativeLib") {
            group = "小工具"
            dependsOn("checkCMake")
            workingDir = file("src/nativeInterop/cpp")
            val assemble = "${properties["cmake.assemble.library"]}".toBooleanStrictOrNull() == true
            val cmakeAssembleLibrary = if (assemble) "ON" else "OFF"
            val output = ByteArrayOutputStream()
            // 向CMake传递Python路径参数
            commandLine(
                "/usr/local/bin/cmake",
                "--fresh",
                "--log-level",
                "ERROR",//"VERBOSE",
//                "--debug-output",
                "-S", ".",
                "-B", "${project.layout.buildDirectory.asFile.get().resolve("cmake").absolutePath}",
                *armhfToolchain.getCmakeConfig().toTypedArray(),
                "-DCMAKE_ASSEMBLE_LIBRARY=${cmakeAssembleLibrary}",
                "-DCMAKE_TOOLCHAIN_FILE=${file("src/nativeInterop/cpp/toolchain.cmake").absolutePath}",
            )
            standardOutput = output
            doFirst {
                logger.lifecycle("执行 CMake 配置，参数: ${commandLine.joinToString(" ")}")
            }
            doLast {
                output.reset()
            }
        }
        // 定义一个任务，用于调用 CMake 编译 native 库// 使用 8 个线程进行编译
        // 指定 CMakeLists.txt 所在目录
        // 获取 Python 环境路径
        tasks.register<Exec>("buildNativeLib"){
            val output = ByteArrayOutputStream()
            group = "小工具"
            dependsOn("configureNativeLib")
            val assemble = "${properties["cmake.assemble.library"]}".toBooleanStrictOrNull() == true
            val cmakeAssembleLibrary = if (assemble) "ON" else "OFF"
            workingDir(file("src/nativeInterop/cpp"))// 指定 CMakeLists.txt 所在目录
            // 获取 Python 环境路径
            commandLine(
                "/usr/local/bin/cmake",
                "--build",
                "${project.layout.buildDirectory.asFile.get().resolve("cmake").absolutePath}",
                "-j", "8", // 使用 8 个线程进行编译
                "-v", // 啰嗦模式
            )
            doFirst {
                logger.lifecycle("构建原生库，使用线程数: 8 ${commandLine.joinToString(" ")}")
            }
            standardOutput = output
            doLast {
                output.reset()
            }
        }
        // 让 Kotlin/Native 编译任务依赖 buildNativeLib 任务
        tasks.withType<KotlinNativeCompile>().all {
            println("[WARRING]  If it cannot be compiled and the toolchain is not downloaded now, you can temporarily comment on this task!! " +
                    "😆😆😆😆 I don't remember when the toolchain was downloaded")
            dependsOn(tasks["buildNativeLib"])
        }
    }
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

val piHost: String by lazy {
    localProperties["piHost"] as String? ?: throw GradleException("piHost is missing in local.properties")
}
val piPort: String by lazy {
    localProperties["piPort"] as String? ?: throw GradleException("piPort is missing in local.properties")
}
val jumpModelInstall =
    properties["ai.install.model.jump"].toString().toBooleanStrictOrNull() == true

@Suppress("UNUSED")
val distDeb: TaskProvider<com.netflix.gradle.plugins.deb.Deb> =
    tasks.register<com.netflix.gradle.plugins.deb.Deb>("distDeb") {
        group=".树莓派"
        description="树莓派可执行安装包"

        // 基本包信息
        packageName = "yanshee"
        version = "1.0.0"
        summary = project.name
        release = "1"
        setArch("armhf") // 修改为armv7架构，因为实际是armv7使用,换了gcc编译器的
        packageGroup = "utils"
        maintainer = "Vicky Leu <yu6564172@gmail.com>" // 替换为您的信息
        url = "https://github.com/vickyleu/ai-robot" // 替换为您的项目URL

        // 添加对数据包的依赖
        requires("yanshee-data", "1.0.0", org.redline_rpm.header.Flags.GREATER)
        val dataDebTask = distDataDeb?.get()
        if (!jumpModelInstall&&dataDebTask!=null) {
            dataDebTask.dependsOn("linkReleaseExecutableLinuxArm32Hfp")
            dependsOn(dataDebTask)
        }else{
            dependsOn("linkReleaseExecutableLinuxArm32Hfp") // 构建Linux ARM32 HFP二进制文件的任务
        }
        // 添加配置保留文件，以及前置和后置安装脚本
        preInstallFile(file("src/linuxMain/resources/preinst.sh"))
        postInstallFile(file("src/linuxMain/resources/postinst.sh"))
        // 修正conffile配置为具体文件而不是目录
        configurationFile(file("src/linuxMain/resources/config/yanshee.conf").absolutePath)

        @Suppress("UNUSED_VARIABLE")
        val fs = project.serviceOf<FileSystemOperations>()

        // 复制二进制文件
        from(project.layout.buildDirectory.get().asFile.resolve("bin/linuxArm32Hfp/releaseExecutable/${packageName}")) {
            into("/usr/local/bin/yanshee/")
            @Suppress("DEPRECATION")
            this.fileMode = 0x755
        }

        // 复制资源文件（如果有）
        from("src/nativeMain/resources") {
            into("/usr/share/${packageName}/resources")
        }

        from("src/pythonMain/python/colorlog") {
            into("/usr/share/${packageName}/python/colorlog")
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
        link("/usr/bin/${packageName}", "/usr/local/bin/yanshee/${packageName}")
    }

// 添加数据包构建任务
@Suppress("UNUSED")
val distDataDeb: TaskProvider<com.netflix.gradle.plugins.deb.Deb>? = if(jumpModelInstall) null else
    tasks.register<com.netflix.gradle.plugins.deb.Deb>("distDataDeb") {
        group=".树莓派"
        description="树莓派数据包"
        packageName = "yanshee-data"
        version = "1.0.0"
        summary = "Model and library files for ${project.name}"
        release = "1"
        setArch("armhf")
        packageGroup = "utils"
        maintainer = "Vicky Leu <yu6564172@gmail.com>"
        url = "https://github.com/vickyleu/ai-robot"

        // 添加数据包的后处理脚本
        postInstallFile(file("src/linuxMain/resources/data-postinst.sh"))

        // 复制模型和动态库
        from("src/linuxMain/resources/linuxArm32Hfp") {
            into("/usr/local/lib/yanshee/")
            @Suppress("DEPRECATION")
            this.fileMode = 0x755
        }
        from("src/nativeInterop/cpp/libs/piper/") {
            into("/usr/local/lib/yanshee/")
            @Suppress("DEPRECATION")
            this.fileMode = 0x755
        }

        from("src/linuxMain/resources/model/") {
            into("/usr/local/share/yanshee-model/")
            @Suppress("DEPRECATION")
            this.fileMode = 0x755
        }
    }

@Suppress("UNUSED")
val installDeb: TaskProvider<Task> = tasks.register<Task>("installDeb") {
    group=".树莓派"
    description="树莓派自动安装"

    val debTask = distDeb.get()
    val packageName = debTask.packageName
    val dataDebTask = distDataDeb?.get()
    val dataPackageName = dataDebTask?.packageName
    val version = debTask.version
    val release = debTask.release
    val archStr = debTask.archStr

    val deb = project.layout.buildDirectory.get().asFile.resolve(
        "distributions/${packageName}_${version}-${release}_${archStr}.deb"
    )

    val dataDeb = project.layout.buildDirectory.get().asFile.resolve(
        "distributions/${dataPackageName}_${version}-${release}_${archStr}.deb"
    )

    val jumpModelInstall =
        properties["ai.install.model.jump"].toString().toBooleanStrictOrNull() == true

    dependsOn(debTask)


    doLast {
        if (deb.exists()) {
            // 准备安装命令
            val installCommands = StringBuilder()

            // 如果是完整安装，则先安装数据包
            if (!jumpModelInstall && dataDeb.exists()) {
                installCommands.append("sudo dpkg -i --force-overwrite /tmp/${dataDeb.name}; ")
            }
            installCommands.append("sudo pkill -9 $packageName || true; ")
            // 然后安装主包（不使用 --purge 命令）
            installCommands.append("sudo dpkg -i /tmp/${deb.name}; ")
            installCommands.append("ls -la /usr/local/bin/yanshee/$packageName; ")
            installCommands.append("md5sum /usr/local/bin/yanshee/$packageName; ")
            installCommands.append("sudo ldconfig; ")
            installCommands.append("gdb /usr/local/bin/yanshee/$packageName -ex run")
//            installCommands.append("gdb /usr/local/bin/yanshee/$packageName -ex 'set confirm off' -ex 'b piper_init' -ex 'run'")

            // 使用sshpass传输deb包到树莓派
            // 直接使用exec而不是providers.exec来避免ProcessOutputValueSource错误
            @Suppress("DEPRECATION")
            exec {
                val sedInPlace =
                    if (System.getProperty("os.name").contains("mac", ignoreCase = true)) {
                        "sed -i ''"  // macOS 需要空备份扩展名
                    } else {
                        "sed -i"     // Linux 无需扩展名
                    }

                var combinedCommand = """
                       | /usr/bin/ssh-keyscan -p $piPort $piHost > /tmp/hostkey && 
                       | $sedInPlace '/${piHost.replace(".", "\\.")}/d' ~/.ssh/known_hosts &&
                       | cat /tmp/hostkey >> ~/.ssh/known_hosts &&
                       | rm /tmp/hostkey &&
                       |  
                       | /usr/local/bin/sshpass -p raspberry scp -P $piPort \
                       |     -o UserKnownHostsFile=/dev/null \
                       |     -o StrictHostKeyChecking=no \
                       |     '${deb.absolutePath}' \
                       |     pi@$piHost:/tmp/
                    """.trimMargin()

                // 如果是完整安装，还需要传输数据包
                if (!jumpModelInstall && dataDeb.exists()) {
                    combinedCommand += """
                        | &&
                        |/usr/local/bin/sshpass -p raspberry scp -P $piPort \
                        |    -o UserKnownHostsFile=/dev/null \
                        |    -o StrictHostKeyChecking=no \
                        |    '${dataDeb.absolutePath}' \
                        |    pi@$piHost:/tmp/
                    """.trimMargin()
                }

                // 添加安装命令
                combinedCommand += """ 
                       | &&
                       | /usr/local/bin/sshpass -p raspberry ssh -p $piPort \
                       |     -o UserKnownHostsFile=/dev/null \
                       |     -o StrictHostKeyChecking=no \
                       |     pi@$piHost \
                       |     "$installCommands"
                    """.trimMargin()

                commandLine(
                    "bash", "-c", combinedCommand
                ).apply {
                    println(this.commandLine.joinToString(" "))
                }
                
                // 配置流式日志输出
                isIgnoreExitValue = true

                var startProgramIsExecute = true
                // 实时输出标准输出流，使用UTF-8编码处理中文
                standardOutput = object : OutputStream() {

                    private val buffer = ByteArrayOutputStream()
                    
                    override fun write(b: Int) {
                        if (b == '\n'.code) {
                            val line = String(buffer.toByteArray(), Charsets.UTF_8)
                            when{
                                line.startsWith("Warning: Permanently added")->Unit
                                line.startsWith("Dwarf Error:")->Unit
                                // 不过滤语音识别相关的日志
                                line.startsWith("[INFO][gc]") && !line.contains("vosk", ignoreCase = true) && !line.contains("alsa", ignoreCase = true) && !line.contains("speech", ignoreCase = true) && !line.contains("audio", ignoreCase = true) && !line.contains("mic", ignoreCase = true)->Unit
                                line.startsWith("[Thread ") && !line.contains("vosk", ignoreCase = true) && !line.contains("alsa", ignoreCase = true) && !line.contains("speech", ignoreCase = true) && !line.contains("audio", ignoreCase = true) && !line.contains("mic", ignoreCase = true)->Unit
                                line.startsWith("[New Thread") && !line.contains("vosk", ignoreCase = true) && !line.contains("alsa", ignoreCase = true) && !line.contains("speech", ignoreCase = true) && !line.contains("audio", ignoreCase = true) && !line.contains("mic", ignoreCase = true)->Unit
                                else->{
                                    if(startProgramIsExecute.not()){
                                        if(line.contains("Starting program:")) {
                                            startProgramIsExecute = true
                                        }
                                        buffer.reset()
                                        return
                                    }
                                    println(line)
                                }
                            }
                            buffer.reset()
                        } else {
                            buffer.write(b)
                        }
                    }
                    
                    override fun flush() {
                        if (buffer.size() > 0) {
                            val line = String(buffer.toByteArray(), Charsets.UTF_8)
                            when{
                                line.startsWith("Warning: Permanently added")->Unit
                                line.startsWith("Dwarf Error:")->Unit
                                // 不过滤语音识别相关的日志
                                line.startsWith("[INFO][gc]") && !line.contains("vosk", ignoreCase = true) && !line.contains("alsa", ignoreCase = true) && !line.contains("speech", ignoreCase = true) && !line.contains("audio", ignoreCase = true) && !line.contains("mic", ignoreCase = true)->Unit
                                line.startsWith("[Thread ") && !line.contains("vosk", ignoreCase = true) && !line.contains("alsa", ignoreCase = true) && !line.contains("speech", ignoreCase = true) && !line.contains("audio", ignoreCase = true) && !line.contains("mic", ignoreCase = true)->Unit
                                line.startsWith("[New Thread") && !line.contains("vosk", ignoreCase = true) && !line.contains("alsa", ignoreCase = true) && !line.contains("speech", ignoreCase = true) && !line.contains("audio", ignoreCase = true) && !line.contains("mic", ignoreCase = true)->Unit
                                else->{
                                    if(startProgramIsExecute.not()){
                                        if(line.contains("Starting program:")) {
                                            startProgramIsExecute = true
                                        }
                                        buffer.reset()
                                        return
                                    }
                                    println(line)
                                }
                            }
                            buffer.reset()
                        }
                    }
                    
                    override fun close() {
                        flush()
                        buffer.close()
                    }
                }
                // 实时输出标准错误流，使用UTF-8编码处理中文
                errorOutput = object : OutputStream() {
                    private val buffer = ByteArrayOutputStream()
                    
                    override fun write(b: Int) {
                        if (b == '\n'.code) {
                            val line = String(buffer.toByteArray(), Charsets.UTF_8)
                            when{
                                line.startsWith("Warning: Permanently added")->Unit
                                line.startsWith("Dwarf Error:")->Unit
                                line.startsWith("[INFO][gc]")->Unit
                                line.startsWith("[Thread ")->Unit
                                line.startsWith("[New Thread")->Unit
                                line.startsWith("LOG (VoskAPI:")->Unit
                                else->{
                                    if(startProgramIsExecute.not()){
                                        if(line.contains("Starting program:")) {
                                            startProgramIsExecute = true
                                        }
                                        buffer.reset()
                                        return
                                    }
                                    System.err.println(line)
                                }
                            }
                            buffer.reset()
                        } else {
                            buffer.write(b)
                        }
                    }
                    
                    override fun flush() {
                        if (buffer.size() > 0) {
                            val line = String(buffer.toByteArray(), Charsets.UTF_8)
                            when{
                                line.startsWith("Warning: Permanently added")->Unit
                                line.startsWith("Dwarf Error:")->Unit
                                line.startsWith("[INFO][gc]")->Unit
                                line.startsWith("[Thread ")->Unit
                                line.startsWith("[New Thread")->Unit
                                else->{
                                    if(startProgramIsExecute.not()){
                                        if(line.contains("Starting program:")) {
                                            startProgramIsExecute = true
                                        }
                                        buffer.reset()
                                        return
                                    }
                                    System.err.println(line)
                                }
                            }
                            buffer.reset()
                        }
                    }
                    
                    override fun close() {
                        flush()
                        buffer.close()
                    }
                }
            }
            // 不需要在这里一次性输出所有日志，因为已经在执行过程中实时输出了
        } else {
            println("Deb file not found: ${deb.absolutePath}")
        }
    }
}

// 向项目添加创建必要脚本的任务
// 向项目添加创建必要脚本的任务
tasks.register<Task>("createDebScripts") {
    doLast {
       val preinst=file("src/linuxMain/resources/preinst.sh")
       val postinst=file("src/linuxMain/resources/postinst.sh")
       val datapostinst=file("src/linuxMain/resources/data-postinst.sh")
       // 创建preinst.sh脚本
       preinst.writeText("""
            |#!/bin/bash
            |set -e
            |
            |# 在卸载前备份重要文件
            |if [ "$1" = "upgrade" ] || [ "$1" = "remove" ]; then
            |    # 确保备份目录存在
            |    mkdir -p /var/backups/yanshee
            |
            |    # 如果模型文件存在，备份它们
            |    if [ -d "/usr/local/share/yanshee-model" ]; then
            |        mkdir -p /var/backups/yanshee/model
            |        cp -r /usr/local/share/yanshee-model/* /var/backups/yanshee/model/
            |    fi
            |
            |    # 如果动态库存在，备份它们
            |    if [ -d "/usr/local/lib" ]; then
            |        mkdir -p /var/backups/yanshee/lib
            |        cp -r /usr/local/lib/*.so* /var/backups/yanshee/lib/ 2>/dev/null || true
            |    fi
            |fi
            |
            |exit 0
       """.trimMargin())
       preinst.setExecutable(true)
       // 创建postinst.sh脚本（主包）
       postinst.writeText("""
              |#!/bin/bash
              |set -e
              |
              |case "$1" in
              |    configure)
              |        # 检查模型文件是否存在，如果不存在则尝试从备份恢复
              |        if [ ! -d "/usr/local/share/yanshee-model" ] || [ -z "$(ls -A /usr/local/share/yanshee-model 2>/dev/null)" ]; then
              |            if [ -d "/var/backups/yanshee/model" ] && [ ! -z "$(ls -A /var/backups/yanshee/model 2>/dev/null)" ]; then
              |                mkdir -p /usr/local/share/yanshee-model
              |                cp -r /var/backups/yanshee/model/* /usr/local/share/yanshee-model/
              |                chmod 755 /usr/local/share/yanshee-model/*
              |                
              |                # 检查是否有需要解压的zip文件
              |                if [ -f "/usr/local/share/yanshee-model/vosk-model-small-cn-0.22.zip" ]; then
              |                    cd /usr/local/share/yanshee-model/
              |                    unzip vosk-model-small-cn-0.22.zip
              |                    rm vosk-model-small-cn-0.22.zip
              |                fi
              |            fi
              |        fi
              |
              |        # 检查动态库是否存在，如果不存在则尝试从备份恢复
              |        if [ -d "/var/backups/yanshee/lib" ] && [ ! -z "$(ls -A /var/backups/yanshee/lib 2>/dev/null)" ]; then
              |            # 复制备份的动态库
              |            cp -r /var/backups/yanshee/lib/* /usr/local/lib/
              |            chmod 755 /usr/local/lib/*.so*
              |        fi
              |
              |        # 更新动态库缓存
              |        ldconfig
              |    ;;
              |esac
              |
              |exit 0
       """.trimMargin())
       postinst.setExecutable(true)
       // 创建数据包的postinst.sh脚本
       datapostinst.writeText("""
            |#!/bin/bash
            |set -e
            |
            |case "$1" in
            |    configure)
            |        # 解压模型文件
            |        if [ -f "/usr/local/share/yanshee-model/vosk-model-small-cn-0.22.zip" ]; then
            |            cd /usr/local/share/yanshee-model/
            |            unzip -o vosk-model-small-cn-0.22.zip
            |            rm vosk-model-small-cn-0.22.zip
            |        fi
            |        
            |        # 确保文件权限正确
            |        chmod 755 /usr/local/share/yanshee-model/*
            |        chmod 755 /usr/local/lib/*.so*
            |        
            |        # 更新动态库缓存
            |        ldconfig
            |    ;;
            |esac
            |
            |exit 0
        """.trimMargin())
       datapostinst.setExecutable(true)
       println("创建了必要的deb包安装脚本")
    }
}

// 添加Cython任务
@Suppress("UNUSED")
val cythonize: Task by tasks.creating {
    val debTask = distDeb.get()
    val packageName = debTask.packageName.capitalized()
    group = "小工具"
    description = "Generate C++ code and header files from YanAPI.py using Cython"
    @Suppress("DEPRECATION")
    doLast {
        // 需要判断当前python是否是3.5版本,不是就要抛出异常
        val outputStream = ByteArrayOutputStream()
        // 使用 pyenv 设置的 Python 路径
        exec {
            commandLine("sh", "-c", "$(pyenv which python3) --version")
            standardOutput = outputStream
            isIgnoreExitValue = true
        }
        val versionOutput = outputStream.toString().trim()
        if (!versionOutput.startsWith("Python 3.5")) {
            throw GradleException("需要 Python 3.5，但找到的是 $versionOutput")
        }
        outputStream.reset()
        // 检查Cython是否已安装
        val result = exec {
            commandLine("sh", "-c", "$(pyenv which python3) -m pip show cython")
            isIgnoreExitValue = true
        }

        // 如果Cython未安装，则安装它
        if (result.exitValue != 0) {
            exec {
                commandLine("sh", "-c", "$(pyenv which python3) -m pip install cython")
            }
            // 安装后刷新环境
            exec {
                commandLine("sh", "-c", "$(pyenv which python3) -m pip show cython")
            }
        }
        // 执行Cython命令，生成C++代码和头文件
        val result2 = exec {
            commandLine(
                "sh", "-c", " " +
                        "$(pyenv which python3) -m cython " +
                        " --gdb " +
                        " --annotate " +
                        " --cplus " +
                        " --force " +
                        " --3  " +
                        " --verbose " +  // 打印详细过程
                        " -l " + //
                        " --lenient " +
//                    " --line-directives " +
                        " --module-name $packageName " +
                        " -o " +
                        " src/nativeInterop/cpp/source/YanAPI.cpp " +
                        " src/pythonMain/python/YanAPI.pyx "
            ).apply {
                println(this.commandLine.joinToString(" "))
            }
        }
        if (result2.exitValue == 0) {
            //接下来需要修改YanAPI.h 避免cintrop无法识别函数
            val yanHeader = projectDir.resolve("src/nativeInterop/cpp/source/YanAPI.h")
            if (yanHeader.exists()) {
                // 读取文件内容
                val content = yanHeader.readText(Charsets.UTF_8)
                // 替换内容
                val newContent = content.replace(
                    "#define PyInit_$packageName()",
                    "#define PyInit_${packageName}_auto()"
                )
                // 写回文件
                yanHeader.writeText(newContent, Charsets.UTF_8)
            }
        }
    }
}
