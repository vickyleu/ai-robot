package com.airobot.web.utils

import react.FC
import react.PropsWithChildren
import react.createContext
import react.useState

// 主题上下文接口
interface ThemeContextProps {
    var isDarkMode: Boolean
    var toggleTheme: () -> Unit
}

// 创建主题上下文
val ThemeContext = createContext<ThemeContextProps?>(null)

// 主题上下文提供者组件
val ThemeProvider = FC<PropsWithChildren> { props ->
    var isDarkMode by useState(false)
    
    val contextValue = object : ThemeContextProps {
        override var isDarkMode: Boolean = isDarkMode
        override var toggleTheme: () -> Unit = {
            isDarkMode = !isDarkMode
        }
    }
    
    ThemeContext.Provider {
        value = contextValue
        +props.children
    }
}

// 辅助函数，获取当前主题
fun getCurrentTheme(isDarkMode: Boolean) = if (isDarkMode) darkTheme else lightTheme