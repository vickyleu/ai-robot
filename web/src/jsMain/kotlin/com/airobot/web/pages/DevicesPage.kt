package com.airobot.web.pages

import com.airobot.web.components.DeviceList
import com.airobot.web.components.NavBar
import mui.material.Container
import mui.material.Typography
import mui.material.styles.TypographyVariant
import mui.system.Box
import mui.system.sx
import react.FC
import react.Props
import web.cssom.Display
import web.cssom.FlexDirection
import web.cssom.FontSynthesisWeight
import web.cssom.FontWeight
import web.cssom.px
import web.cssom.svh
import web.cssom.vh

// 设备页面组件
val DevicesPage = FC<Props> {
    Box {
        sx {
            display = Display.flex
            flexDirection = FlexDirection.column
            minHeight = 100.vh
        }
        
        // 导航栏
        NavBar()
        
        // 内容区域
        Container {
            sx {
                marginTop = 24.px
                marginBottom = 24.px
                flexGrow = number(1.0)
            }
            maxWidth = "lg"
            
            // 页面标题
            Typography {
                variant = TypographyVariant.h4
                sx {
                    marginBottom = 24.px
                    fontWeight = "600".asDynamic()
                }
                +"设备管理"
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