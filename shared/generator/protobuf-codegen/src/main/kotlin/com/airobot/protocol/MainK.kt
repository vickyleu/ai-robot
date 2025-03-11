package com.airobot.protocol

import pbandk.gen.Platform

public fun main() {
    Platform.stdoutWriteResponse(runGenerator(Platform.stdinReadRequest()))
}