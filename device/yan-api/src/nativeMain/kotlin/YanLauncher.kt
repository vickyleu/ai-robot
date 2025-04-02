package com.airobot.device.yanapi

import com.airobot.core.Launcher
import com.airobot.core.command.Action
import com.airobot.core.command.ActionRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import platform.posix.exit

class YanLauncher : Launcher {
    val device: YanDevice = YanDevice.instance
    override fun startApplication() {
        runBlocking {
            withContext(Dispatchers.Default) {
                async {
                    device.initDevice()
                    val complete = device.completableDeferred.await()
                    if (complete) {
                        device.onStatusUpdate {
                            println("it:::${it}")
                        }
                        device.connect()
                        println("[DEBUG] ${if(device.isCharging()) "在充电中" else "不在充电中"}")
                        device.speak("说话, 回答我, why? baby why? tell me !")
                        println("初始化连接成功,准备退出\n")
                        exit(0)
                    } else {
                        println("初始化连接失败,准备退出\n")
                        exit(0)
                    }
                }
            }
            withContext(Dispatchers.Unconfined) {
                // 保持主线程运行，等待状态更新
                async {
                    while (true) {
                        delay(1000)
//                    println("keep alive\n")
                    }
                }
            }
        }
    }

    private suspend fun monkeyMimic() {
        // 模仿猴子的动作 - 拍胸口
        device.moveJoints(
            Servo.ServoName.左肩上下 to 90,
            Servo.ServoName.右肩上下 to 90,
            Servo.ServoName.左肘 to 90,
            Servo.ServoName.右肘 to 90
        )
        delay(500)

        // 拍胸口动作
        repeat(3) {
            device.moveJoints(
                Servo.ServoName.左肩水平 to 45,
                Servo.ServoName.右肩水平 to 135
            )
            delay(300)
            device.moveJoints(
                Servo.ServoName.左肩水平 to 90,
                Servo.ServoName.右肩水平 to 90
            )
            delay(300)
        }

        // 抓头动作
        device.moveJoints(
            Servo.ServoName.右肩上下 to 135,
            Servo.ServoName.右肘 to 135
        )
        delay(1000)

        // 恢复初始位置
        device.moveJoints(
            Servo.ServoName.左肩上下 to 0,
            Servo.ServoName.右肩上下 to 0,
            Servo.ServoName.左肘 to 0,
            Servo.ServoName.右肘 to 0,
            Servo.ServoName.左肩水平 to 90,
            Servo.ServoName.右肩水平 to 90
        )
    }

    private suspend fun crabMimic() {
        // 模仿螃蟹的动作 - 侧向移动
        device.moveJoints(
            Servo.ServoName.左肩上下 to 90,
            Servo.ServoName.右肩上下 to 90,
            Servo.ServoName.左肩水平 to 45,
            Servo.ServoName.右肩水平 to 135
        )
        delay(500)

        // 侧向移动动作
        repeat(3) {
            // 向左移动
            device.move(speed = 80, steps = 1, direction = "left")
            delay(800)

            // 向右移动
            device.move(speed = 80, steps = 1, direction = "right")
            delay(800)
        }

        // 恢复初始位置
        device.moveJoints(
            Servo.ServoName.左肩上下 to 0,
            Servo.ServoName.右肩上下 to 0,
            Servo.ServoName.左肩水平 to 90,
            Servo.ServoName.右肩水平 to 90
        )
    }

    private suspend fun morningStretch() {
        // 早晨伸展动作
        // 伸展手臂
        device.moveJoints(
            Servo.ServoName.左肩上下 to 180,
            Servo.ServoName.右肩上下 to 180
        )
        delay(1000)

        // 扭动身体
        device.moveJoints(
            Servo.ServoName.左肩水平 to 45,
            Servo.ServoName.右肩水平 to 135
        )
        delay(800)

        device.moveJoints(
            Servo.ServoName.左肩水平 to 135,
            Servo.ServoName.右肩水平 to 45
        )
        delay(800)

        // 恢复初始位置
        device.moveJoints(
            Servo.ServoName.左肩上下 to 0,
            Servo.ServoName.右肩上下 to 0,
            Servo.ServoName.左肩水平 to 90,
            Servo.ServoName.右肩水平 to 90
        )
    }

    private suspend fun exercise() {
        // 锻炼动作 - 深蹲
        repeat(2) {
            // 下蹲
            device.moveJoints(
                Servo.ServoName.左膝 to 90,
                Servo.ServoName.右膝 to 90,
                Servo.ServoName.左踝前后 to 120,
                Servo.ServoName.右踝前后 to 120
            )
            delay(1000)

            // 站起
            device.moveJoints(
                Servo.ServoName.左膝 to 0,
                Servo.ServoName.右膝 to 0,
                Servo.ServoName.左踝前后 to 90,
                Servo.ServoName.右踝前后 to 90
            )
            delay(1000)
        }

        // 手臂运动
        repeat(2) {
            // 举起手臂
            device.moveJoints(
                Servo.ServoName.左肩上下 to 180,
                Servo.ServoName.右肩上下 to 180
            )
            delay(800)

            // 放下手臂
            device.moveJoints(
                Servo.ServoName.左肩上下 to 0,
                Servo.ServoName.右肩上下 to 0
            )
            delay(800)
        }
    }

    private suspend fun readingPose() {
        // 阅读姿势 - 一只手拿书，一只手翻页
        device.moveJoints(
            Servo.ServoName.左肩上下 to 90,
            Servo.ServoName.左肘 to 90,
            Servo.ServoName.右肩上下 to 45,
            Servo.ServoName.右肘 to 90
        )
        delay(1000)

        // 翻页动作
        repeat(2) {
            device.moveJoints(Servo.ServoName.右肘 to 45)
            delay(500)

            // 侧向移动动作
            repeat(3) {
                // 向左移动
                device.move(speed = 80, steps = 1, direction = "left")
                delay(800)

                // 向右移动
                device.move(speed = 80, steps = 1, direction = "right")
                delay(800)
            }

            // 恢复初始位置
            device.moveJoints(
                Servo.ServoName.左肩上下 to 0,
                Servo.ServoName.右肩上下 to 0,
                Servo.ServoName.左肩水平 to 90,
                Servo.ServoName.右肩水平 to 90
            )
        }
    }
    private suspend fun sleepingPose() {

    }


    /**
     * 注册日常活动动作
     * 包括伸展、锻炼、阅读、睡觉等日常活动
     */
    private fun registerDailyActivityActions() {
        // 组合动作 - 日常活动
        ActionRegistry.register(
            "daily_activity", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val activity = params["activity"]?.toString() ?: "stretch"
                println("执行日常活动动作，活动: $activity")

                when (activity) {
                    "stretch" -> morningStretch()
                    "exercise" -> exercise()
                    "read" -> readingPose()
                    "sleep" -> sleepingPose()
                    else -> morningStretch()
                }
            }

            private suspend fun morningStretch() {
                // 早晨伸展动作
                // 伸展手臂
                device.moveJoints(
                    Servo.ServoName.左肩上下 to 180,
                    Servo.ServoName.右肩上下 to 180
                )
                delay(1000)

                // 扭动身体
                device.moveJoints(
                    Servo.ServoName.左肩水平 to 45,
                    Servo.ServoName.右肩水平 to 135
                )
                delay(800)

                device.moveJoints(
                    Servo.ServoName.左肩水平 to 135,
                    Servo.ServoName.右肩水平 to 45
                )
                delay(800)

                // 恢复初始位置
                device.moveJoints(
                    Servo.ServoName.左肩上下 to 0,
                    Servo.ServoName.右肩上下 to 0,
                    Servo.ServoName.左肩水平 to 90,
                    Servo.ServoName.右肩水平 to 90
                )
            }

            private suspend fun exercise() {
                // 锻炼动作 - 深蹲
                repeat(2) {
                    // 下蹲
                    device.moveJoints(
                        Servo.ServoName.左膝 to 90,
                        Servo.ServoName.右膝 to 90,
                        Servo.ServoName.左踝前后 to 120,
                        Servo.ServoName.右踝前后 to 120
                    )
                    delay(1000)

                    // 站起
                    device.moveJoints(
                        Servo.ServoName.左膝 to 0,
                        Servo.ServoName.右膝 to 0,
                        Servo.ServoName.左踝前后 to 90,
                        Servo.ServoName.右踝前后 to 90
                    )
                    delay(1000)
                }

                // 手臂运动
                repeat(2) {
                    // 举起手臂
                    device.moveJoints(
                        Servo.ServoName.左肩上下 to 180,
                        Servo.ServoName.右肩上下 to 180
                    )
                    delay(800)

                    // 放下手臂
                    device.moveJoints(
                        Servo.ServoName.左肩上下 to 0,
                        Servo.ServoName.右肩上下 to 0
                    )
                    device.moveJoints(Servo.ServoName.右肘 to 90)
                    delay(500)

                    // 侧向移动动作
                    repeat(3) {
                        // 向左移动
                        device.move(speed = 80, steps = 1, direction = "left")
                        delay(800)

                        // 向右移动
                        device.move(speed = 80, steps = 1, direction = "right")
                        delay(800)
                    }

                    // 恢复初始位置
                    device.moveJoints(
                        Servo.ServoName.左肩上下 to 0,
                        Servo.ServoName.右肩上下 to 0,
                        Servo.ServoName.左肩水平 to 90,
                        Servo.ServoName.右肩水平 to 90
                    )
                }
            }
        })

        ActionRegistry . register ("animal_mimic", object : Action {
            override suspend fun execute(params: Map<String, JsonElement>) {
                val animal = params["animal"]?.toString() ?: "dog"
                println("执行动物模仿动作，动物: $animal")

                when (animal) {
                    "dog" -> dogMimic()
                    "bird" -> birdMimic()
                    "monkey" -> monkeyMimic()
                    "crab" -> crabMimic()
                    else -> dogMimic()
                }
            }

            private suspend fun dogMimic() {
                // 模仿狗的动作 - 四肢着地姿势
                device.moveJoints(
                    Servo.ServoName.左髋前后 to 45,
                    Servo.ServoName.右髋前后 to 45,
                    Servo.ServoName.左膝 to 90,
                    Servo.ServoName.右膝 to 90,
                    Servo.ServoName.左肩上下 to 45,
                    Servo.ServoName.右肩上下 to 45,
                    Servo.ServoName.左肘 to 90,
                    Servo.ServoName.右肘 to 90
                )
                delay(1000)

                // 摇尾巴动作（通过髋部摆动）
                repeat(3) {
                    device.moveJoints(
                        Servo.ServoName.左髋水平 to 100,
                        Servo.ServoName.右髋水平 to 80
                    )
                    delay(300)
                    device.moveJoints(
                        Servo.ServoName.左髋水平 to 80,
                        Servo.ServoName.右髋水平 to 100
                    )
                    delay(300)
                }

                // 恢复站立姿势
                device.moveJoints(
                    Servo.ServoName.左髋前后 to 90,
                    Servo.ServoName.右髋前后 to 90,
                    Servo.ServoName.左膝 to 0,
                    Servo.ServoName.右膝 to 0,
                    Servo.ServoName.左肩上下 to 0,
                    Servo.ServoName.右肩上下 to 0,
                    Servo.ServoName.左肘 to 0,
                    Servo.ServoName.右肘 to 0,
                    Servo.ServoName.左髋水平 to 90,
                    Servo.ServoName.右髋水平 to 90
                )
            }

            private suspend fun birdMimic() {
                // 模仿鸟的动作 - 展翅
                device.moveJoints(
                    Servo.ServoName.左肩上下 to 90,
                    Servo.ServoName.右肩上下 to 90,
                    Servo.ServoName.左肩水平 to 0,
                    Servo.ServoName.右肩水平 to 180
                )
                delay(500)

                // 拍打翅膀
                repeat(4) {
                    device.moveJoints(
                        Servo.ServoName.左肩上下 to 45,
                        Servo.ServoName.右肩上下 to 45
                    )
                    delay(300)
                    device.moveJoints(
                        Servo.ServoName.左肩上下 to 135,
                        Servo.ServoName.右肩上下 to 135
                    )
                    delay(300)
                }

                // 恢复初始位置
                device.moveJoints(
                    Servo.ServoName.左肩上下 to 0,
                    Servo.ServoName.右肩上下 to 0,
                    Servo.ServoName.左肩水平 to 90,
                    Servo.ServoName.右肩水平 to 90
                )
            }

            private suspend fun monkeyMimic() {
                // 模仿猴子的动作 - 拍胸口
                device.moveJoints(
                    Servo.ServoName.左肩上下 to 90,
                    Servo.ServoName.右肩上下 to 90,
                    Servo.ServoName.左肘 to 90,
                    Servo.ServoName.右肘 to 90
                )
                delay(500)

                // 拍胸口动作
                repeat(3) {
                    device.moveJoints(
                        Servo.ServoName.左肩水平 to 45,
                        Servo.ServoName.右肩水平 to 135
                    )
                    delay(300)
                    device.moveJoints(
                        Servo.ServoName.左肩水平 to 90,
                        Servo.ServoName.右肩水平 to 90
                    )
                    delay(300)
                }

                // 抓头动作
                device.moveJoints(
                    Servo.ServoName.右肩上下 to 135,
                    Servo.ServoName.右肘 to 135
                )
                delay(1000)

                // 恢复初始位置
                device.moveJoints(
                    Servo.ServoName.左肩上下 to 0,
                    Servo.ServoName.右肩上下 to 0,
                    Servo.ServoName.左肘 to 0,
                    Servo.ServoName.右肘 to 0,
                    Servo.ServoName.左肩水平 to 90,
                    Servo.ServoName.右肩水平 to 90
                )
            }

            private suspend fun crabMimic() {
                // 模仿螃蟹的动作 - 侧向移动
                device.moveJoints(
                    Servo.ServoName.左肩上下 to 90,
                    Servo.ServoName.右肩上下 to 90,
                    Servo.ServoName.左肩水平 to 45,
                    Servo.ServoName.右肩水平 to 135
                )
                delay(500)

                // 侧向移动动作
                repeat(3) {
                    // 向左移动
                    device.move(speed = 80, steps = 1, direction = "left")
                    delay(800)

                    // 向右移动
                    device.move(speed = 80, steps = 1, direction = "right")
                    delay(800)
                }

                // 恢复初始位置
                device.moveJoints(
                    Servo.ServoName.左肩上下 to 0,
                    Servo.ServoName.右肩上下 to 0,
                    Servo.ServoName.左肩水平 to 90,
                    Servo.ServoName.右肩水平 to 90
                )
            }
        })
    }
}

                