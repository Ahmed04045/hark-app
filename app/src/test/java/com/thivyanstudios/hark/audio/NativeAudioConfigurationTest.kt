package com.thivyanstudios.hark.audio

import com.thivyanstudios.hark.audio.model.AudioProcessingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAudioConfigurationTest {

    @Test
    fun `stored settings are replayed to a newly created native engine`() {
        val received = mutableMapOf<String, Any>()
        val configuration = NativeAudioConfiguration(
            processing = AudioProcessingConfig(
                microphoneGain = 3.0f,
                noiseSuppressionEnabled = false,
                automaticGainControlEnabled = false,
                dynamicsProcessingEnabled = true,
            ),
            transcriptModeEnabled = true,
            speechPresenceGainDb = 8.0f,
        )

        configuration.applyTo(object : NativeAudioConfigurationTarget {
            override fun setMicrophoneGain(gain: Float) {
                received["microphoneGain"] = gain
            }

            override fun setNoiseSuppressionEnabled(enabled: Boolean) {
                received["noiseSuppressionEnabled"] = enabled
            }

            override fun setAutomaticGainControlEnabled(enabled: Boolean) {
                received["automaticGainControlEnabled"] = enabled
            }

            override fun setDynamicsProcessingEnabled(enabled: Boolean) {
                received["dynamicsProcessingEnabled"] = enabled
            }

            override fun setTranscriptModeEnabled(enabled: Boolean) {
                received["transcriptModeEnabled"] = enabled
            }

            override fun setSpeechPresenceGainDb(gainDb: Float) {
                received["speechPresenceGainDb"] = gainDb
            }
        })

        assertEquals(3.0f, received["microphoneGain"])
        assertFalse(received["noiseSuppressionEnabled"] as Boolean)
        assertFalse(received["automaticGainControlEnabled"] as Boolean)
        assertTrue(received["dynamicsProcessingEnabled"] as Boolean)
        assertTrue(received["transcriptModeEnabled"] as Boolean)
        assertEquals(8.0f, received["speechPresenceGainDb"])
    }
}
