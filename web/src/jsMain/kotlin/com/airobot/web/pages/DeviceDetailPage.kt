package com.airobot.web.pages

import com.airobot.web.components.Device
import com.airobot.web.components.DeviceStatus
import com.airobot.web.components.getColor
import com.airobot.web.components.getText
import js.objects.jso
import mui.icons.material.ArrowBack
import mui.icons.material.Battery90
import mui.icons.material.Circle
import mui.icons.material.PlayArrow
import mui.icons.material.Stop
import mui.material.Button
import mui.material.ButtonVariant
import mui.material.Card
import mui.material.CardContent
import mui.material.Chip
import mui.material.ChipVariant
import mui.material.Divider
import mui.material.Grid
import mui.material.IconButton
import mui.material.LinearProgress
import mui.material.Paper
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
import react.router.useParams
import react.useState
import web.cssom.AlignItems
import web.cssom.Color
import web.cssom.Display
import web.cssom.JustifyContent
import web.cssom.Margin
import web.cssom.px
import web.cssom.rgb

// 设备详情页面组件
val DeviceDetailPage = FC<Props> {
    val navigate = useNavigate()
    val params = useParams()
    val deviceId = params["deviceId"] ?: ""

    // 模拟设备数据获取
    val device = when (deviceId) {
        "1" -> Device("1", "机器人 Alpha", "四足机器人", DeviceStatus.ONLINE, 85, "刚刚")
        "2" -> Device("2", "机器人 Beta", "轮式机器人", DeviceStatus.BUSY, 62, "10分钟前")
        "3" -> Device("3", "机器人 Gamma", "人形机器人", DeviceStatus.OFFLINE, 20, "2小时前")
        "4" -> Device("4", "机器人 Delta", "四足机器人", DeviceStatus.ONLINE, 90, "5分钟前")
        else -> null
    }

    // 控制状态
    var isControlling by useState(false)

    Box {
        sx {
            padding = 24.px
        }

        // 返回按钮和标题
        Box {
            sx {
                display = Display.flex
                alignItems = AlignItems.center
                marginBottom = 24.px
            }

            IconButton {
                onClick = { navigate("/devices") }
                ArrowBack()
            }

            Typography {
                variant = TypographyVariant.h4
                sx {
                    marginLeft = 16.px
                }
                +(device?.name ?: "未知设备")
            }
        }

        if (device != null) {
            // 设备信息卡片
            Card {
                sx {
                    marginBottom = 24.px
                }

                CardContent {
                    // 状态和类型
                    Box {
                        sx {
                            display = Display.flex
                            justifyContent = JustifyContent.spaceBetween
                            alignItems = AlignItems.center
                            marginBottom = 16.px
                        }

                        Box {
                            Typography {
                                variant = TypographyVariant.h6
                                +"设备信息"
                            }

                            Typography {
                                variant = TypographyVariant.body1
                                +device.type
                            }
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
                        }
                    }

                    Divider {
                        sx {
                            margin = Margin(16.px, 0.px)
                        }
                    }

                    // 电池状态
                    Typography {
                        variant = TypographyVariant.subtitle1
                        +"电池状态"
                    }

                    Box {
                        sx {
                            display = Display.flex
                            alignItems = AlignItems.center
                            marginTop = 8.px
                            marginBottom = 16.px
                        }

                        Battery90 {
                            sx {
                                marginRight = 8.px
                                color = when {
                                    device.batteryLevel > 60 -> Color("#4caf50") // 绿色
                                    device.batteryLevel > 20 -> Color("#ff9800") // 橙色
                                    else -> Color("#f44336") // 红色
                                }
                            }
                        }

                        Box {
                            sx {
                                flexGrow = number(1.0)
                                marginRight = 16.px
                            }

                            LinearProgress {
                                variant = mui.material.LinearProgressVariant.determinate
                                value = device.batteryLevel.toDouble()
                                sx {
                                    height = 10.px
                                    borderRadius = 5.px
                                }
                            }
                        }

                        Typography {
                            variant = TypographyVariant.body2
                            +"${device.batteryLevel}%"
                        }
                    }

                    // 最后活动时间
                    Typography {
                        variant = TypographyVariant.subtitle1
                        +"最后活动时间"
                    }

                    Typography {
                        variant = TypographyVariant.body1
                        sx {
                            marginTop = 4.px
                        }
                        +device.lastActive
                    }
                }
            }

            // 控制面板
            Card {
                CardContent {
                    Typography {
                        variant = TypographyVariant.h6
                        sx {
                            marginBottom = 16.px
                        }
                        +"控制面板"
                    }

                    if (device.status == DeviceStatus.OFFLINE) {
                        // 设备离线提示
                        Paper {
                            sx {
                                padding = 16.px
                                backgroundColor = rgb(0, 0, 0, 0.05)
                                textAlign = web.cssom.TextAlign.center
                            }

                            Typography {
                                +"设备当前离线，无法控制"
                            }
                        }
                    } else {
                        // 控制按钮
                        Grid {
                            container = true
                            spacing = responsive(2)

                            // 启动/停止按钮
                            Grid {
                                item = true
                                asDynamic().xs = 12
                                asDynamic().sm = 6
                                asDynamic().md = 3
                                Button {
                                    variant =
                                        if (isControlling) ButtonVariant.contained else ButtonVariant.outlined
                                    color =
                                        if (isControlling) mui.material.ButtonColor.error else mui.material.ButtonColor.primary
                                    fullWidth = true
                                    onClick = { isControlling = !isControlling }
                                    startIcon = if (isControlling) {
                                        Stop.create()
                                    } else {
                                        PlayArrow.create()
                                    }

                                    +(if (isControlling) "停止" else "启动")
                                }
                            }

                            // 其他控制按钮
                            listOf("前进", "后退", "左转", "右转").forEachIndexed { index, action ->
                                Grid {
                                    item = true
                                    asDynamic().xs = 12
                                    asDynamic().sm = 6
                                    asDynamic().md = 3

                                    Button {
                                        variant = ButtonVariant.outlined
                                        fullWidth = true
                                        disabled =
                                            !isControlling || device.status == DeviceStatus.BUSY
                                        +action
                                    }
                                }
                            }
                        }

                        // 控制状态提示
                        if (isControlling) {
                            Box {
                                sx {
                                    marginTop = 16.px
                                    padding = 8.px
                                    backgroundColor = Color("#e3f2fd")
                                    borderRadius = 4.px
                                }

                                Typography {
                                    variant = TypographyVariant.body2
                                    +"设备控制中，请使用上方按钮操作设备"
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // 设备不存在提示
            Paper {
                sx {
                    padding = 24.px
                    textAlign = web.cssom.TextAlign.center
                }

                Typography {
                    variant = TypographyVariant.h5
                    +"未找到设备"
                }

                Typography {
                    variant = TypographyVariant.body1
                    sx {
                        marginTop = 8.px
                        marginBottom = 16.px
                    }
                    +"无法找到ID为 ${deviceId} 的设备"
                }

                Button {
                    variant = ButtonVariant.contained
                    onClick = { navigate("/devices") }
                    +"返回设备列表"
                }
            }
        }
    }
}

// 辅助函数，将Double转为FlexGrow
private fun number(value: Double): web.cssom.FlexGrow {
    return jso<web.cssom.FlexGrow> { this.asDynamic().valueOf = { value } }
}