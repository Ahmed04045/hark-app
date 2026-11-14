package com.thivyanstudios.hark.audio.model

data class AudioProcessingConfig(
    val microphoneGain: Float = 1.0f,
    val ambientGain: Float = 0.0f,
    val noiseSuppressionEnabled: Boolean = true,
    val dynamicsProcessingEnabled: Boolean = false
)
