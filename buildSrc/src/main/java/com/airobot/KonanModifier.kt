package com.airobot

object KonanModifier {
    fun konanProperties(toolchainUrl:String,externalToolchain: Boolean=true): String {
        /**
         * 树莓派型号对照表 `cat /proc/cpuinfo`
         * -------------------------------------------------------------------------------------------------------------
         * |树莓派 1 Model A/B          |BCM2835 (ARM1176JZF-S)             |ARMv6                      |arm1176jzf-s   |
         * |树莓派 Zero/Zero W          |BCM2835 (ARM1176JZF-S)             |ARMv6                      |arm1176jzf-s   |
         * |树莓派 2 Model B (v1.1)     |BCM2836 (Cortex-A7)                |ARMv7                      |cortex-a7      |
         * |树莓派 2 Model B (v1.2)     |BCM2837 (Cortex-A53)               |ARMv8/AArch64              |cortex-a53     |
         * |树莓派 3 Model A+/B/B+      |BCM2837 (Cortex-A53)               |ARMv8/AArch64              |cortex-a53     |
         * |树莓派 4 Model B            |BCM2711 (Cortex-A72)               |ARMv8/AArch64              |cortex-a72     |
         * |树莓派 400                  |BCM2711 (Cortex-A72)               |ARMv8/AArch64              |cortex-a72     |
         * |树莓派 5                    |BCM2712 (Cortex-A76)               |ARMv8/AArch64              |cortex-a76     |
         * -------------------------------------------------------------------------------------------------------------
         */
        val overridenProperties: Lazy<List<MutableMap.MutableEntry<String, String>>> = lazy {
            // 获取当前操作系统
            val host = when(System.getProperty("os.name").lowercase()) {
                "mac os x" -> "macos"
                "windows" -> "windows"
                "linux" -> "linux"
                else -> System.getProperty("os.name").lowercase()
            }
            val hostArch = when(System.getProperty("os.arch").lowercase()){
                "aarch64" -> "arm64"
                "x86_64","x64" -> "x64"
                "arm" -> "arm"
                "x86" -> "x86"
                else -> System.getProperty("os.arch").lowercase()
            }

            return@lazy  if(externalToolchain){
                linkedMapOf<String, String>(
                //下载链接
                "dependenciesUrl" to toolchainUrl,
                //压缩包名字
                "dependencies.${host}_${hostArch}-linux_arm32_hfp" to "armv7-unknown-linux-gnueabihf",
                "toolchainDependency.linux_arm32_hfp" to "armv7-unknown-linux-gnueabihf",
                "gccToolchain.linux_arm32_hfp" to "armv7-unknown-linux-gnueabihf",
                "targetToolchain.${host}_${hostArch}-linux_arm32_hfp" to "\$gccToolchain.linux_arm32_hfp/armv7-unknown-linux-gnueabihf",
                "linker.${host}_${hostArch}-linux_arm32_hfp" to "\$targetToolchain.${host}_${hostArch}-linux_arm32_hfp/bin/ld",//.bfd
                "targetTriple.linux_arm32_hfp" to "armv7-unknown-linux-gnueabihf",
                "targetSysRoot.linux_arm32_hfp" to "\$gccToolchain.linux_arm32_hfp/armv7-unknown-linux-gnueabihf/sysroot",
                "libGcc.linux_arm32_hfp" to "../../lib/gcc/armv7-unknown-linux-gnueabihf/13.3.0",
                // 其他配置
                "targetCpu.linux_arm32_hfp" to "arm1176jzf-s",
                "targetCpuFeatures.linux_arm32_hfp" to "+armv7,+dsp,+fp64,+strict-align,+vfp2,+vfp2sp,+vfp3,+neon,-aes,-d32,-fp-armv8," +
                        "-fp-armv8d16,-fp-armv8d16sp,-fp-armv8sp,-fp16,-fp16fml,-fullfp16,-sha2,-thumb-mode,-vfp4,-vfp4d16,-vfp4d16sp,-vfp4sp",
              )
            }else{
                linkedMapOf<String, String>(
                    "clangFlags.${host}_${hostArch}" to " -std=c++17 -D_GLIBCXX_USE_CXX11_ABI=1 -cc1 -emit-obj -disable-llvm-passes -x ir"
                ).apply {
                    println("[ERROR] 🙄🙄🙄this modify is not working, please use externalToolchain, " +
                            "you can see clang++ still use `-cc1 -mfloat-abi hard -emit-obj -disable-llvm-optzns -x ir`,with out `-std=c++17 -D_GLIBCXX_USE_CXX11_ABI=1` !🙄🙄🙄")
                }
            }.entries.toList()
        }
        return return overridenProperties.value.mapIndexed { index, item ->
            index to item
        }.joinToString("") { (index, item) ->
            "${item.key}=${item.value}${if (overridenProperties.value.size == index + 1) "" else ";"}"
        }
    }
}