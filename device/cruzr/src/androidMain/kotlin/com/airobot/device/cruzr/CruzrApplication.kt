package com.airobot.device.cruzr

import android.app.Application
import android.content.Context
import com.airobot.core.device.Device

class CruzrApplication : Application() {
    companion object {
        private lateinit var instance: CruzrApplication
        fun getInstance(): CruzrApplication = instance
        fun getAppContext(): Context = instance.applicationContext
    }
    override fun onCreate() {
        super.onCreate()
        instance = this
        // 初始化CRUZR SDK
        initializeCruzrSDK()
    }
    private lateinit var cruzrDevice: Device
    
    private fun initializeCruzrSDK() {
        cruzrDevice = CruzrDeviceFactory(this).createDevice()
    }
    
    override fun onTerminate() {
        // 清理资源
        super.onTerminate()
    }
}