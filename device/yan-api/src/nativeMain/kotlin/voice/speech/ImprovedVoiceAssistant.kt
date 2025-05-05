@file:OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)

package com.airobot.device.yanapi.voice.speech

import com.airobot.device.yanapi.voice.audio.BasicAudioProcessor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.time.ExperimentalTime


/**
 * BasicAudioProcessor的扩展函数
 */
fun BasicAudioProcessor.setGain(gain: Float) {
    this.setParameter("gain", gain.toDouble())
}

fun BasicAudioProcessor.setNoiseGate(threshold: Int) {
    this.setParameter("noiseGate", threshold.toDouble())
}

fun BasicAudioProcessor.setLowPassFilterCoeff(coeff: Float) {
    this.setParameter("lowPassCoeff", coeff.toDouble())
} 