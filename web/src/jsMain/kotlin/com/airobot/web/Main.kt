package com.airobot.web

import com.airobot.web.components.App
import react.create
import react.dom.client.createRoot
import web.dom.document

fun main() {
    val container = document.getElementById("root") ?: error("找不到root元素")
    createRoot(container).render(App.create())
}