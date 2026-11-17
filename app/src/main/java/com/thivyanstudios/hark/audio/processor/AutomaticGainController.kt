package com.thivyanstudios.hark.audio.processor

import kotlin.math.exp

internal class AutomaticGainController {
    var gain: Float = 1.0f
        private set

    fun update(
        inputRms: Float,
        appliedGain: Float = 1.0f,
        frameSamples: Int,
        sampleRate: Int
    ): Float {
        require(inputRms >= 0.0f)
        require(appliedGain > 0.0f)
        require(frameSamples > 0)
        require(sampleRate > 0)

        val targetGain = if (inputRms < MINIMUM_INPUT_RMS) {
            1.0f
        } else {
            (TARGET_RMS / (inputRms * appliedGain)).coerceIn(MINIMUM_GAIN, MAXIMUM_GAIN)
        }
        val timeConstant = if (targetGain > gain) GAIN_INCREASE_TIME_SECONDS else GAIN_DECREASE_TIME_SECONDS
        val frameSeconds = frameSamples.toFloat() / sampleRate
        val smoothing = exp(-frameSeconds / timeConstant)
        gain = targetGain + smoothing * (gain - targetGain)
        return gain
    }

    fun reset() {
        gain = 1.0f
    }

    private companion object {
        const val TARGET_RMS = 0.12589254f // -18 dBFS
        const val MINIMUM_INPUT_RMS = 0.001f // -60 dBFS
        const val MINIMUM_GAIN = 0.01f
        const val MAXIMUM_GAIN = 8.0f
        const val GAIN_INCREASE_TIME_SECONDS = 0.25f
        const val GAIN_DECREASE_TIME_SECONDS = 0.08f
    }
}
