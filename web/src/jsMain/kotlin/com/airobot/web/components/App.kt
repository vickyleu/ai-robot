package com.airobot.web.components

import com.airobot.web.pages.HomePage
import mui.material.CssBaseline
import mui.system.ThemeProvider
import mui.system.createTheme
import react.FC
import react.Props
import react.router.RouteObject
import react.router.dom.RouterProvider
import react.router.dom.createBrowserRouter

val App = FC<Props> {
    val router = createBrowserRouter(
        routes = arrayOf(
            RouteObject(
                path = "/",
                Component = HomePage,
            )
        )
    )

    ThemeProvider {
        theme = createTheme()
        CssBaseline()
        RouterProvider {
            this.router = router
        }
    }
} 