package com.thivyanstudios.hark.audio

import com.thivyanstudios.hark.audio.model.AudioProcessingConfig

internal interface NativeAudioConfigurationTarget {
    fun setMicrophoneGain(gain: Float)
    fun setNoiseSuppressionEnabled(enabled: Boolean)
    fun setAutomaticGainControlEnabled(enabled: Boolean)
    fun setDynamicsProcessingEnabled(enabled: Boolean)
    fun setTranscriptModeEnabled(enabled: Boolean)
    fun setSpeechPresenceGainDb(gainDb: Float)
}

internal data class NativeAudioConfiguration(
    val processing: AudioProcessingConfig = AudioProcessingConfig(),
    val transcriptModeEnabled: Boolean = false,
    val speechPresenceGainDb: Float = 6.0f,
) {
    fun applyTo(target: NativeAudioConfigurationTarget) {
        target.setMicrophoneGain(processing.microphoneGain)
        target.setNoiseSuppressionEnabled(processing.noiseSuppressionEnabled)
        target.setAutomaticGainControlEnabled(processing.automaticGainControlEnabled)
        target.setDynamicsProcessingEnabled(processing.dynamicsProcessingEnabled)
        target.setTranscriptModeEnabled(transcriptModeEnabled)
        target.setSpeechPresenceGainDb(speechPresenceGainDb)
    }
}
