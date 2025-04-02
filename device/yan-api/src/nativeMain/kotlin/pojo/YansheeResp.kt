package com.airobot.device.yanapi.pojo

import kotlinx.serialization.Serializable

@Serializable
data class YansheeResp<T>(
    val code: Int,
    val msg: String,
    val data: T,
){
    fun isSuccessful(): Boolean{
        return code==0
    }
}