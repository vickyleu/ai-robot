package com.airobot.web.components

import mui.icons.material.Circle
import mui.material.Card
import mui.material.CardActionArea
import mui.material.CardContent
import mui.material.Chip
import mui.material.ChipVariant
import mui.material.Divider
import mui.material.Grid
import mui.material.Typography
import mui.material.styles.TypographyVariant
import mui.system.Box
import mui.system.responsive
import mui.system.sx
import react.FC
import react.Props
import react.ReactNode
import react.create
import react.router.useNavigate
import web.cssom.AlignItems
import web.cssom.Color
import web.cssom.Display
import web.cssom.JustifyContent
import web.cssom.Margin
import web.cssom.pct
import web.cssom.px

// 设备数据类
data class Device(
    val id: String,
    val name: String,
    val type: String,
    val status: DeviceStatus,
    val batteryLevel: Int,
    val lastActive: String
)

// 设备状态枚举
enum class DeviceStatus {
    ONLINE, OFFLINE, BUSY
}

// 获取状态对应的颜色
fun DeviceStatus.getColor(): Color = when (this) {
    DeviceStatus.ONLINE -> Color("#4caf50") // 绿色
    DeviceStatus.OFFLINE -> Color("#9e9e9e") // 灰色
    DeviceStatus.BUSY -> Color("#ff9800") // 橙色
}

// 获取状态对应的文本
fun DeviceStatus.getText(): String = when (this) {
    DeviceStatus.ONLINE -> "在线"
    DeviceStatus.OFFLINE -> "离线"
    DeviceStatus.BUSY -> "忙碌"
}

// 设备列表组件
val DeviceList = FC<Props> {
    val navigate = useNavigate()

    // 模拟设备数据
    val devices = listOf(
        Device("1", "机器人 Alpha", "四足机器人", DeviceStatus.ONLINE, 85, "刚刚"),
        Device("2", "机器人 Beta", "轮式机器人", DeviceStatus.BUSY, 62, "10分钟前"),
        Device("3", "机器人 Gamma", "人形机器人", DeviceStatus.OFFLINE, 20, "2小时前"),
        Device("4", "机器人 Delta", "四足机器人", DeviceStatus.ONLINE, 90, "5分钟前")
    )

    Box {
        sx {
            padding = 16.px
            width = 100.pct
        }

        // 标题
        Typography {
            variant = TypographyVariant.h5
            sx {
                marginBottom = 16.px
                fontWeight = "500".asDynamic()
            }
            +"设备列表"
        }

        // 设备网格
        Grid {
            container = true
            spacing = responsive(2)

            devices.forEach { device ->
                Grid {
                    item = true
                    asDynamic().xs = 12
                    asDynamic().sm = 6
                    asDynamic().md = 4
                    asDynamic().lg = 3

                    Card {
                        sx {
                            height = 100.pct
                        }

                        CardActionArea {
                            onClick = { navigate("/devices/${device.id}") }

                            CardContent {
                                // 设备名称和状态
                                Box {
                                    sx {
                                        display = Display.flex
                                        justifyContent = JustifyContent.spaceBetween
                                        alignItems = AlignItems.center
                                        marginBottom = 8.px
                                    }

                                    Typography {
                                        variant = TypographyVariant.h6
                                        +device.name
                                    }

                                    Chip {
                                        icon = Circle.create {
                                            sx {
                                                color = device.status.getColor()
                                                fontSize = 12.px
                                            }
                                        }
                                        label = ReactNode(device.status.getText())
                                        variant = ChipVariant.outlined
                                        size = mui.material.Size.small
                                    }
                                }

                                // 设备类型
                                Typography {
                                    variant = TypographyVariant.body2
                                    +device.type
                                }

                                Divider {
                                    sx {
                                        margin = Margin(8.px, 0.px)
                                    }
                                }

                                // 电池和最后活动时间
                                Box {
                                    sx {
                                        display = Display.flex
                                        justifyContent = JustifyContent.spaceBetween
                                        marginTop = 8.px
                                    }

                                    Typography {
                                        variant = TypographyVariant.body2
                                        +"电量: ${device.batteryLevel}%"
                                    }

                                    Typography {
                                        variant = TypographyVariant.body2
                                        +"最后活动: ${device.lastActive}"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}