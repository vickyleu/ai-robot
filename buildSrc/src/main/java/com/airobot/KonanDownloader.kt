package com.airobot

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.create
import java.io.File
import kotlin.system.exitProcess

@Suppress("DEPRECATION")
class KonanDownloader {
    private constructor()

    companion object{
        @JvmStatic
        fun getInstance(): KonanDownloader {
            return KonanDownloader()
        }
    }
    private val env = System.getenv().map { it.key.lowercase() to it.value }.toMap()
    // 获取到home目录
    private val home: String by env
    // 获取到当前操作系统的架构
    private val konanDependenciesDir = File(
        "$home/.konan/dependencies"
    )

    private var isDownloading = false

    fun startDownloadToolchain(project: Project, url: String){
        if(isDownloading)return
        // 下载工具链到临时目录,可以是项目的build目录,并解压到konanDependenciesDir目录下
        val tempDir = File(project.rootProject.layout.buildDirectory.get().asFile,"konan")
        if(!tempDir.exists()){
            tempDir.mkdirs()
        }
        project.logger.lifecycle("Downloading toolchain from $url to $tempDir")
        project.logger.lifecycle("Downloading native dependencies (arm toolchain for cmake). This is a one-time action performed only on the first run of the compiler.")
        project.logger.lifecycle("Because the vosk I compiled with a higher version of glibc, I don't want to recompile it anymore. I'm so tired.")
        // 创建下载任务
        val downloadTask = project.tasks.create("downloadToolchain") {
            doLast {
                val fileName = url.substring(url.lastIndexOf('/') + 1)
                val downloadFile = File(tempDir, fileName)
                // 使用Java的URL连接下载文件
                project.logger.lifecycle("Downloading to ${downloadFile.absolutePath}")
                try {
                    val connection = java.net.URL(url).openConnection()
                    connection.connectTimeout = 30000
                    connection.readTimeout = 60000

                    val bis = java.io.BufferedInputStream(connection.getInputStream())
                    val bos = java.io.BufferedOutputStream(java.io.FileOutputStream(downloadFile))

                    val buffer = ByteArray(8192)
                    var count = bis.read(buffer)
                    var totalBytes = 0L
                    isDownloading = true
                    while (count != -1) {
                        bos.write(buffer, 0, count)
                        totalBytes += count
                        project.logger.debug("Downloaded $totalBytes bytes")
                        count = bis.read(buffer)
                    }

                    bos.close()
                    bis.close()

                    project.logger.lifecycle("Download completed: ${downloadFile.absolutePath}")

                    // 解压文件
                    extractFile(project, downloadFile, konanDependenciesDir)
                } catch (e: Exception) {
                    project.logger.error("Failed to download toolchain: ${e.message}")
                    throw GradleException("酸萝卜别吃!!! Failed to download toolchain", e)
                }
            }
        }
        // 运行下载任务
        downloadTask.actions.forEach { it.execute(downloadTask) }
        project.logger.lifecycle("Toolchain setup completed")
    }

    /**
     * 解压完成后还要再konan目录(destDir)下创建一个.extracted的文件,然后把解压的目录名字(fileName.xxx)写进去,防止触发konan的重复下载
     */
    private fun extractFile(project: Project, file: File, destDir: File) {
        project.logger.lifecycle("Extracting ${file.name} to ${destDir.absolutePath}")

        when {
            file.name.endsWith(".zip") -> extractZip(project, file, destDir)
            file.name.endsWith(".tar.gz") || file.name.endsWith(".tgz") -> extractTarGz(project, file, destDir)
            file.name.endsWith(".tar.xz") -> extractTarXz(project, file, destDir)
            else -> {
                isDownloading = false
                project.logger.error("Unsupported archive format: ${file.name}")
                exitProcess(0)
            }
        }
    }

    private fun extractZip(project: Project, zipFile: File, destDir: File) {
        project.tasks.create("extractZip") {
            doLast {
                project.tasks.create<Copy>("copyZip") {
                    // 使用Gradle的Copy任务来解压zip文件
                    from(project.zipTree(zipFile))
                    into(destDir)
                    doLast {
                        isDownloading = false
                        project.logger.lifecycle("Extracted ${zipFile.name} to ${destDir.absolutePath}")
                        // 判断是否需要创建.extracted文件
                        val file = File(destDir, zipFile.name)
                        if (file.exists()){
                            updateExtractedFile(file, destDir, project)
                        }
                        exitProcess(0)
                    }
                }
            }
        }.apply {
            this.actions.forEach { it.execute(this) }
        }
    }

    private fun updateExtractedFile(file: File, destDir: File, project: Project) {
        val fileName = file.name.substringBeforeLast(".")
        val extractedFile = File(destDir, ".extracted")
        if (!extractedFile.exists()) {
            extractedFile.createNewFile()
            extractedFile.writeText(fileName)
            project.logger.lifecycle("Created ${extractedFile.absolutePath} with content: $fileName")
        } else {
            // 读取.extracted readLines, 如果没有匹配到当前文件名, 就增加上去
            val existingFiles = extractedFile.readLines().toMutableList()
            if (!existingFiles.contains(fileName)) {
                existingFiles.add(fileName)
                extractedFile.writeText(existingFiles.joinToString("\n"))
                project.logger.lifecycle("Updated ${extractedFile.absolutePath} with content: $fileName")
            } else {
                project.logger.lifecycle("File $fileName already exists in ${extractedFile.absolutePath}")
            }
        }
    }

    private fun extractTarGz(project: Project, tarFile: File, destDir: File) {
        project.tasks.create<Copy>("copyTarGz") {
            from(project.tarTree(project.resources.gzip(tarFile)))
            into(destDir)
            doLast {
                isDownloading = false
                project.logger.lifecycle("Extracted ${tarFile.name} to ${destDir.absolutePath}")
                // 判断是否需要创建.extracted文件
                val file = File(destDir, tarFile.name)
                if (file.exists()){
                    updateExtractedFile(file, destDir, project)
                }
                exitProcess(0)
            }
        }.apply {
            this.actions.forEach { it.execute(this) }
        }
    }

    private fun extractTarXz(project: Project, tarFile: File, destDir: File) {
        // Gradle没有直接支持.tar.xz的解压，我们可以使用命令行工具
        val extract=project.tasks.create<Exec>("extractTarXz") {
            workingDir(destDir)

            if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
                // Windows可能需要安装适当的工具
                commandLine("cmd", "/c", "7z", "x", "-y", tarFile.absolutePath)
                // 然后解压.tar文件
                doLast {
                    val extractedTar = File(destDir, tarFile.name.replace(".tar.xz", ".tar"))
                    if (extractedTar.exists()) {
                        project.exec {
                            commandLine("cmd", "/c", "7z", "x", "-y", extractedTar.absolutePath)
                        }
                        extractedTar.delete()
                    }
                    isDownloading = false
                    project.logger.lifecycle("Extracted ${tarFile.name} to ${destDir.absolutePath}")
                    // 判断是否需要创建.extracted文件
                    val file = File(destDir, tarFile.name)
                    if (file.exists()){
                        updateExtractedFile(file, destDir, project)
                    }
                    exitProcess(0)
                }
            } else {
                // Linux/Mac可以直接使用tar命令
                commandLine("tar", "-xf", tarFile.absolutePath, "-C", destDir.absolutePath)
                doLast {
                    // 删除解压后的.tar.xz文件
                    if (tarFile.exists()) {
                        tarFile.delete()
                    }
                    isDownloading = false
                    project.logger.lifecycle("Extracted ${tarFile.name} to ${destDir.absolutePath}")
                    // 判断是否需要创建.extracted文件
                    val file = File(destDir, tarFile.name)
                    if (file.exists()){
                        updateExtractedFile(file, destDir, project)
                    }
                    exitProcess(0)
                }
            }
        }
        extract.actions.forEach { it.execute(extract) }
    }
}