package com.airobot.web.components

import com.airobot.web.pages.DeviceDetailPage
import com.airobot.web.pages.DevicesPage
import com.airobot.web.pages.HomePage
import com.airobot.web.utils.ThemeContext
import com.airobot.web.utils.ThemeProvider
import com.airobot.web.utils.getCurrentTheme
import js.objects.jso
import mui.material.CssBaseline
import react.FC
import react.Props
import react.router.RouteObject
import react.router.dom.RouterProvider
import react.router.dom.createBrowserRouter
import react.use
import mui.system.ThemeProvider as MuiThemeProvider

val App = FC<Props> {
    val router = createBrowserRouter(
        routes = arrayOf(
            RouteObject(
                path = "/",
                Component = HomePage,
            ),
            RouteObject(
                path = "/devices",
                Component = DevicesPage,
            ),
            RouteObject(
                path = "/devices/:deviceId",
                Component = DeviceDetailPage,
            )
        ),
    )

    ThemeProvider {
        val themeContext = use(ThemeContext)
        MuiThemeProvider {
            theme = getCurrentTheme(themeContext?.isDarkMode ?: false)
            CssBaseline()
            RouterProvider {
                this.router = router
                asDynamic().future = jso {
                    v7_relativeSplatPath = true
                    v7_startTransition = true
                }
            }
        }
    }
}