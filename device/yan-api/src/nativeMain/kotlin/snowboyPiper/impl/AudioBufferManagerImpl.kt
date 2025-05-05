package com.airobot.device.yanapi.snowboyPiper.impl

import com.airobot.device.yanapi.snowboyPiper.interfaces.AudioBufferManager


class AudioBufferManagerImpl : AudioBufferManager {

    private val audioAccumulator = mutableListOf<Short>()

    override val size: Int
        get() = audioAccumulator.size

    override fun addAudio(audioData: ShortArray) {
        audioAccumulator.addAll(audioData.toList())
    }

    override fun addAudio(audioData: List<Short>) {
        audioAccumulator.addAll(audioData)
    }

    override fun getAccumulatedAudio(): ShortArray {
        return audioAccumulator.toShortArray()
    }

    override fun clear() {
        audioAccumulator.clear()
    }

    override fun retainOverlap(overlapSize: Int) {
        if (audioAccumulator.size > overlapSize) {
            val newAccumulator = audioAccumulator.takeLast(overlapSize)
            audioAccumulator.clear()
            audioAccumulator.addAll(newAccumulator)
        }
    }
}