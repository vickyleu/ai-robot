package com.airobot

object KonanModifier {
    fun konanProperties(): String{
        val hostArch = System.getProperty("os.arch")
        val overridenProperties= if(hostArch.contains("aarch64")) {
            // M1配置
            linkedMapOf(
                //下载链接
                "dependenciesUrl" to "https://media.githubusercontent.com/media/vickyleu/gcc_maven/refs/heads/main/arm64",
                //压缩包名字
                "dependencies.macos_arm64-linux_arm32_hfp" to "armv7-unknown-linux-gnueabihf",
                "toolchainDependency.linux_arm32_hfp" to "armv7-unknown-linux-gnueabihf",
                "gccToolchain.linux_arm32_hfp" to "armv7-unknown-linux-gnueabihf",
                "targetToolchain.macos_arm64-linux_arm32_hfp" to "\$gccToolchain.linux_arm32_hfp/armv7-unknown-linux-gnueabihf",
                "linker.macos_arm64-linux_arm32_hfp" to "\$targetToolchain.macos_arm64-linux_arm32_hfp/bin/ld",//.bfd
                "targetTriple.linux_arm32_hfp" to "armv7-unknown-linux-gnueabihf",
                "targetSysRoot.linux_arm32_hfp" to "\$gccToolchain.linux_arm32_hfp/armv7-unknown-linux-gnueabihf/sysroot",
                "libGcc.linux_arm32_hfp" to "../../lib/gcc/armv7-unknown-linux-gnueabihf/13.3.0",
                // 其他配置
                "targetCpu.linux_arm32_hfp" to "generic",
//                "targetCpu.linux_arm32_hfp" to "cortex-a7",
//                "targetCpuFeatures.linux_arm32_hfp" to "+dsp,+strict-align,+vfp3,+vfp3d16,+neon,-thumb-mode",
            ).entries.toList()
        } else {
            // Intel配置
            linkedMapOf(
                //下载链接
                "dependenciesUrl" to "https://media.githubusercontent.com/media/vickyleu/gcc_maven/refs/heads/main/x86_64",
                //压缩包名字
                "dependencies.macos_x64-linux_arm32_hfp" to "armv7-unknown-linux-gnueabihf",
                "toolchainDependency.linux_arm32_hfp" to "armv7-unknown-linux-gnueabihf",
                "gccToolchain.linux_arm32_hfp" to "armv7-unknown-linux-gnueabihf",
                "targetToolchain.macos_x64-linux_arm32_hfp" to "\$gccToolchain.linux_arm32_hfp/armv7-unknown-linux-gnueabihf",
                "linker.macos_x64-linux_arm32_hfp" to "\$targetToolchain.macos_x64-linux_arm32_hfp/bin/ld",//.bfd
                "targetTriple.linux_arm32_hfp" to "armv7-unknown-linux-gnueabihf",
                "targetSysRoot.linux_arm32_hfp" to "\$gccToolchain.linux_arm32_hfp/armv7-unknown-linux-gnueabihf/sysroot",
                "libGcc.linux_arm32_hfp" to "../../lib/gcc/armv7-unknown-linux-gnueabihf/13.3.0",
                // 其他配置
                "targetCpu.linux_arm32_hfp" to "generic",
//                "targetCpu.linux_arm32_hfp" to "cortex-a7",
//                "targetCpuFeatures.linux_arm32_hfp" to "+dsp,+strict-align,+vfp3,+vfp3d16,+neon,-thumb-mode",
            ).entries.toList()
        }
        return overridenProperties.mapIndexed { index,item->
            index to item
        }.joinToString(""){(index,item)->
            "${item.key}=${item.value}${if(overridenProperties.size==index+1)"" else ";"}"
        }
    }
}