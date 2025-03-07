package com.airobot.device.yanapi

import com.airobot.core.Launcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class YanLauncher: Launcher {
    val device = YanDevice()
    override fun startApplication() {
        runBlocking {
            device.onStatusUpdate {
                println("it:::${it}")
            }
            device.isCharging()
//            device.connect()
            println("我是这个东西啊")

            withContext(Dispatchers.Unconfined) {
                // 保持主线程运行，等待状态更新
                while (true) {
                    kotlinx.coroutines.delay(1000)
                }
            }
        }
    }
}