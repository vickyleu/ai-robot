val realRootProject: File = rootProject.rootDir.let {
    if (it.name == "buildSrc") {
        it
    } else {
        it.parentFile
    }
}
rootProject.layout.buildDirectory.set(file("${realRootProject.absolutePath}/${realRootProject.name}/subprojects/build/${rootProject.name}"))
plugins {
    `kotlin-dsl`
}

buildscript {
    repositories {
        gradlePluginPortal() {
            content {
                excludeGroupByRegex("com.vickyleu.*")
            }
        }
        google() {
            content {
                excludeGroupByRegex("com.vickyleu.*")
            }
        }
        mavenCentral() {
            content {
                excludeGroupByRegex("com.vickyleu.*")
            }
        }
    }
    class KonanModifier(kotlinVersion: String) {
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
        private val arch = System.getProperty("os.arch").lowercase()
        private var isChange = false
        private val konanPropertiesFile by lazy{
            File(
                "$home/.konan/kotlin-native-prebuilt-$os-$arch-$kotlinVersion/konan/konan.properties"
            )
        }
        private val konanProperties by lazy{
            readPropertiesFile(konanPropertiesFile.readLines().toMutableList())
        }

        private fun readPropertiesFile(list: List<String>): Map<Int, Pair<String, MutableList<String>>> {
            val map = mutableMapOf<Int, Pair<String, MutableList<String>>>()
            var index = 0
            var continuationLine = false
            var currentKey = ""
            var currentValue = ""
            var currentList = mutableListOf<String>()
            list.forEach { line ->
                val trimmedLine = line.trim()
                if (trimmedLine.isEmpty() || !trimmedLine.contains("=")) {
                    if(continuationLine){
                        val lineValue = trimmedLine.trim()
                        if (lineValue.endsWith("\\")) {
                            // 还有更多续行
                            currentList.add(lineValue.substring(0, lineValue.length - 1).trim())
                        } else {
                            // 最后一个续行
                            currentList.add(lineValue.trim())
                            map[index] = currentKey to currentList
                            index++
                            continuationLine = false
                        }
                    }else{
                        // 处理空行或无key的行
                        map[index] = trimmedLine to mutableListOf()
                        index++
                        continuationLine = false
                    }
                }
                else if (!continuationLine) {
                    // 新的键值对
                    val parts = trimmedLine.split("=", limit = 2)
                    currentKey = parts[0].trim()
                    currentValue = parts[1].trim()
                    currentList = mutableListOf()
                    if (currentValue.endsWith("\\")) {
                        // 有续行符
                        continuationLine = true
                        currentValue = currentValue.substring(0, currentValue.length - 1).trim()
                    } else {
                        // 无续行，可能包含单个值
                        currentList.add(currentValue.trim())
                        map[index] = currentKey to currentList
                        index++
                    }
                }
                else {
                    // 处理续行
                    val lineValue = trimmedLine.trim()
                    if (lineValue.endsWith("\\")) {
                        // 还有更多续行
                        currentList.add(lineValue.substring(0, lineValue.length - 1).trim())
                    } else {
                        // 最后一个续行
                        currentList.add(lineValue.trim())
                        map[index] = currentKey to currentList
                        index++
                        continuationLine = false
                    }
                }
            }
            // 处理文件结尾可能的未完成的续行
            if (continuationLine) {
                map[index] = currentKey to currentList
            }
            return map
        }

        private fun restorePropertiesFormat(map: Map<Int, Pair<String, MutableList<String>>>): List<String> {
            val result = mutableListOf<String>()
            // 按索引顺序处理（索引是字符串形式的数字）
            map.entries.sortedBy { it.key }.forEach { (_, pair) ->
                val (key, values) = pair

                if (values.isEmpty()) {
                    // 空行或者无key的行，直接添加
                    result.add(key)
                } else if (values.size == 1) {
                    // 单值情况
                    result.add("$key = ${values[0]}")
                } else {
                    // 多值情况需要使用续行符
                    result.add("$key = \\")
                    for (i in 0 until values.size - 1) {
                        result.add("  ${values[i]} \\")
                    }
                    // 最后一行无需续行符
                    result.add("  ${values[values.size - 1]}")
                }
            }

            return result
        }

        fun appendKonanProperties(key: String, value: String): KonanModifier {
            if(konanPropertiesFile.exists().not())return this
            // 获取到行号
            val item = konanProperties.entries.filter { (index, entry) ->
                (entry.first .trim().startsWith(key))
            }.map { (index, entry) ->
                Triple(index, entry.first, entry.second)
            }.singleOrNull()
            if (item != null) {

                val (index: Int, key: String, values: MutableList<String>) = item
                // 获取到行
                val listItems =
                    values.mapIndexed { idx, item -> idx to item }.mapNotNull { (idx, content) ->
                        if(content.contains(value)) null else (idx to content)
                    }
                var listItem = if(listItems.size==1) {
                    listItems.single()
                } else {
                    null
                }
                if (listItem == null) {
                    return this
                }
                val (idx, content) = listItem
                //The regular expression needs to handle possible equality signs, key (multiple spaces possible) = (minimum one space) value,
                values[idx] = " $value $content"
                isChange = true
            }
            return this
        }

        fun save() {
            if(konanPropertiesFile.exists().not())return
            if (isChange.not()) return run {
                println("konanPropertiesFile not change")
            }
            /**
             * @exception [org.jetbrains.kotlin.protobuf.InvalidProtocolBufferException]
             * @see <a href="https://stackoverflow.com/questions/57180047/kotlin-native-protobuf-problems">Protocol message tag had invalid wire type</a>
             *
             * @suppress IMPORTANT Don't delete all konan toolchains in `~/.konan` dir,
             * because the Konan compiler will use it.
            */
            val newKonanProperties = restorePropertiesFormat(konanProperties).joinToString("\n")
            println("[ERROR] I'm afraid to write to the file ! 😢")
            // println("newKonanProperties::\n$newKonanProperties")
            // konanPropertiesFile.writeText(newKonanProperties)
        }
    }
    val externalToolchain = properties["ai.use.externalToolchain"].toString().toBooleanStrictOrNull() == true
    val konan = KonanModifier(libs.versions.kotlin.asProvider().get())
    if(externalToolchain){
        konan.appendKonanProperties(
            "clangFlags.linux_arm32_hfp",
            "-std=c++17 -D_GLIBCXX_USE_CXX11_ABI=1"
        )
        konan.save()
    }
    configurations
        .all {
//            if(name.endsWith("NpmAggregated").not()){
                resolutionStrategy.eachDependency {
                    if ((requested.group == "org.jetbrains.kotlin" || requested.group == "org.jetbrains")
                        && requested.module.name.startsWith("kotlin")
                    ) {
                        useVersion(libs.versions.kotlin.asProvider().get())
                    }
                }
//            }
        }
}

allprojects {
    this.configurations.all {
//        if(name.endsWith("NpmAggregated").not()){
            // 所有group是org.jetbrains.kotlinx并且module包含coroutines的都替换成com.vickyleu.kotlinx.coroutines:原来的module:版本号
            resolutionStrategy.eachDependency {
                if ((requested.group == "org.jetbrains.kotlin" || requested.group == "org.jetbrains")
                    && requested.module.name.startsWith("kotlin")
                ) {
                    useVersion(libs.versions.kotlin.asProvider().get())
                }
            }
//        }
    }
}
configurations.all {
//    if(name.endsWith("NpmAggregated").not()){
        resolutionStrategy {
            eachDependency {
                if (
                    requested.group == "org.jetbrains.kotlin"
                    && requested.module.name.startsWith("kotlin")
                ) {
                    useVersion(libs.versions.kotlin.asProvider().get())
                }
            }
        }
//    }
}