package com.airobot.device.yanapi.pojo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BatteryInfo(
    // 电池电量百分比
    val percent: Int,
    // 电池电量mAh,json返回的翻译是错的, 写的是电压
    @SerialName("voltage")
    val capacity: Int,
    // 充电状态
    @Serializable(with = SmartBooleanSerializer::class)
    val charging: Boolean,
)