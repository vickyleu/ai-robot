package com.airobot.web.components

import js.objects.jso
import mui.icons.material.Brightness4
import mui.icons.material.Brightness7
import mui.icons.material.Dashboard
import mui.icons.material.Devices
import mui.icons.material.Home
import mui.icons.material.Settings
import mui.material.AppBar
import mui.material.AppBarPosition
import mui.material.IconButton
import mui.material.Tab
import mui.material.Tabs
import mui.material.Toolbar
import mui.material.Typography
import mui.material.styles.TypographyVariant
import mui.system.sx
import react.FC
import react.Props
import react.ReactNode
import react.asElementOrNull
import react.create
import react.router.useNavigate
import web.cssom.Border
import web.cssom.Color
import web.cssom.Cursor
import web.cssom.FlexGrow
import web.cssom.FontWeight
import web.cssom.LineStyle
import web.cssom.px

// 定义导航项
data class NavItem(
    val label: String,
    val path: String,
    val icon: ReactNode
)

// 创建主题上下文接口
interface ThemeContextProps {
    var isDarkMode: Boolean
    var toggleTheme: () -> Unit
}

// 导航栏组件
val NavBar = FC<Props> {
    val navigate = useNavigate()

    // 模拟主题上下文，实际项目中应该使用React Context
    val isDarkMode = false // 这里应该从Context中获取
    val toggleTheme = { /* 这里应该调用Context中的切换函数 */ }

    // 导航项列表
    val navItems = listOf(
        NavItem("首页", "/", Home.create()),
        NavItem("设备", "/devices", Devices.create()),
        NavItem("控制台", "/dashboard", Dashboard.create()),
        NavItem("设置", "/settings", Settings.create())
    )

    // 当前选中的导航项索引
    val selectedIndex = 0 // 这里应该根据当前路径动态计算

    AppBar {
        position = AppBarPosition.static
        sx {
            borderBottom = Border(1.px, style = LineStyle.solid)
            borderBottomColor = if (isDarkMode) Color("#424242") else Color("#e0e0e0")
        }

        Toolbar {
            // Logo
            Typography {
                variant = TypographyVariant.h6
                sx {
                    marginRight = 24.px
                    fontWeight = FontWeight.bold
                    cursor = Cursor.pointer
                }
                onClick = { navigate("/") }
                +"AI Robot"
            }

            // 导航选项卡
            Tabs {
                value = selectedIndex
                onChange = { _, newValue ->
                    val index = newValue.toString().toInt()
                    navigate(navItems[index].path)
                }
                sx {
                    flexGrow = number(1.0)
                }

                navItems.forEachIndexed { index, item ->
                    Tab {
                        icon = item.icon.asElementOrNull()
                        label = ReactNode(item.label)
                        value = index
                    }
                }
            }

            // 主题切换按钮
            IconButton {
                onClick = { toggleTheme() }
                if (isDarkMode) {
                    Brightness7()
                } else {
                    Brightness4()
                }
            }
        }
    }
}

// 辅助函数，将Double转为FlexGrow
private fun number(value: Double): FlexGrow {
    return jso<FlexGrow> { this.asDynamic().valueOf = { value } }
}