package com.airobot.web.pages

import com.airobot.web.components.DeviceList
import com.airobot.web.components.NavBar
import js.objects.jso
import mui.icons.material.Search
import mui.material.*
import mui.material.styles.TypographyVariant
import mui.system.Box
import mui.system.Container
import mui.system.responsive
import mui.system.sx
import react.*
import react.dom.onChange
import react.router.useNavigate
import web.cssom.*

val HomePage = FC<Props> {
    var searchQuery by useState("")
    val navigate = useNavigate()

    Box {
        sx {
            display = Display.flex
            flexDirection = FlexDirection.column
            minHeight = 100.vh
        }

        // 导航栏
        NavBar()

        // 主内容区域
        Container {
            sx {
                marginTop = 24.px
                marginBottom = 24.px
                display = Display.flex
                flexDirection = FlexDirection.column
                alignItems = AlignItems.center
                flexGrow = number(1.0)
            }
            maxWidth = "lg"

            // Logo和搜索框
            Box {
                sx {
                    display = Display.flex
                    flexDirection = FlexDirection.column
                    alignItems = AlignItems.center
                    justifyContent = JustifyContent.center
                    width = 100.pct
                    marginBottom = 48.px
                }

                // Logo
                Box {
                    sx {
                        marginBottom = 32.px
                    }
                    Typography {
                        variant = TypographyVariant.h3
                        +"AI Robot"
                    }
                }

                // 搜索框
                Box {
                    sx {
                        width = responsive(584.px).asDynamic()
                        maxWidth = 90.pct
                    }
                    TextField {
                        fullWidth = true
                        value = searchQuery
                        onChange = { event ->
                            searchQuery = event.target.asDynamic().value
                        }
                        placeholder = "搜索设备或命令..."
                        variant = FormControlVariant.outlined
                        //Classifier 'InputProps' does not have a companion object, and thus must be initialized here
                        asDynamic().InputProps = jso<InputProps> {
                            endAdornment = InputAdornment.create {
                                position = InputAdornmentPosition.end
                                IconButton {
                                    Search()
                                }
                            }
                        }
                    }
                }
            }

            // 快速访问卡片
            Typography {
                variant = TypographyVariant.h5
                sx {
                    alignSelf = AlignSelf.flexStart
                    marginBottom = 16.px
                    fontWeight = "500".asDynamic()
                }
                +"快速访问"
            }

            // 快速访问卡片网格
            Grid {
                container = true
                spacing = responsive(3)
                sx {
                    marginBottom = 48.px
                }

                // 设备管理卡片
                Grid {
                    item = true
                    asDynamic().xs = 12
                    asDynamic().sm = 6
                    asDynamic().md = 4

                    Card {
                        sx {
                            height = 100.pct
                            cursor = Cursor.pointer
                        }
                        onClick = { navigate("/devices") }

                        CardContent {
                            Typography {
                                variant = TypographyVariant.h6
                                +"设备管理"
                            }

                            Typography {
                                variant = TypographyVariant.body2
                                sx {
                                    marginTop = 8.px
                                    marginBottom = 16.px
                                }
                                +"查看和控制所有连接的机器人设备"
                            }

                            Button {
                                variant = ButtonVariant.outlined
                                +"查看设备"
                            }
                        }
                    }
                }

                // 控制台卡片
                Grid {
                    item = true
                    asDynamic().xs = 12
                    asDynamic().sm = 6
                    asDynamic().md = 4

                    Card {
                        sx {
                            height = 100.pct
                            cursor = Cursor.pointer
                        }
                        onClick = { navigate("/dashboard") }

                        CardContent {
                            Typography {
                                variant = TypographyVariant.h6
                                +"控制台"
                            }

                            Typography {
                                variant = TypographyVariant.body2
                                sx {
                                    marginTop = 8.px
                                    marginBottom = 16.px
                                }
                                +"查看设备状态和操作历史记录"
                            }

                            Button {
                                variant = ButtonVariant.outlined
                                +"进入控制台"
                            }
                        }
                    }
                }

                // 设置卡片
                Grid {
                    item = true
                    asDynamic().xs=12
                    asDynamic().sm=6
                    asDynamic().md=4

                    Card {
                        sx {
                            height = 100.pct
                            cursor = Cursor.pointer
                        }
                        onClick = { navigate("/settings") }

                        CardContent {
                            Typography {
                                variant = TypographyVariant.h6
                                +"系统设置"
                            }

                            Typography {
                                variant = TypographyVariant.body2
                                sx {
                                    marginTop = 8.px
                                    marginBottom = 16.px
                                }
                                +"配置系统参数和用户偏好"
                            }

                            Button {
                                variant = ButtonVariant.outlined
                                +"前往设置"
                            }
                        }
                    }
                }
            }

            // 最近设备
            Typography {
                variant = TypographyVariant.h5
                sx {
                    alignSelf = AlignSelf.flexStart
                    marginBottom = 16.px
                    fontWeight = "500".asDynamic()
                }
                +"最近设备"
            }

            // 设备列表
            DeviceList()
        }
    }
}

// 辅助函数，将Double转为FlexGrow
private fun number(value: Double): web.cssom.FlexGrow {
    return js.objects.jso { this.asDynamic().valueOf = { value } }
}