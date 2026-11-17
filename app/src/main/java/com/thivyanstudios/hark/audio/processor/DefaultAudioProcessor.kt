package com.thivyanstudios.hark.audio.processor

import android.media.AudioRecord
import android.media.AudioTrack
import com.thivyanstudios.hark.audio.PlatformInputEffects
import com.thivyanstudios.hark.audio.model.AudioEngineEvent
import com.thivyanstudios.hark.audio.model.AudioProcessingConfig
import com.thivyanstudios.hark.audio.stream.AudioSink
import com.thivyanstudios.hark.audio.stream.AudioSource
import com.thivyanstudios.hark.util.Constants
import kotlinx.coroutines.channels.Channel
import kotlin.math.sqrt

/**
 * A simplified Java-based audio processor used only as a fallback 
 * if the high-performance Native (Oboe) engine fails to start.
 */
class DefaultAudioProcessor(private val events: Channel<AudioEngineEvent>) : AudioProcessor {

    @Volatile
    private var currentConfig = AudioProcessingConfig()
    
    // Pre-allocated buffer to reduce GC pressure during audio processing
    private var processingBuffer: FloatArray? = null
    private val automaticGainController = AutomaticGainController()
    private val platformInputEffects = PlatformInputEffects()

    override fun process(
        audioSource: AudioSource,
        audioSink: AudioSink,
        config: AudioProcessingConfig,
        isRunning: () -> Boolean
    ) {
        currentConfig = config
        setupAudioEffects(audioSource.audioSessionId)

        val bufferSize = audioSink.bufferSizeInFrames
        
        // Only allocate if necessary
        if (processingBuffer == null || processingBuffer?.size != bufferSize) {
            processingBuffer = FloatArray(bufferSize)
        }
        
        val buffer = processingBuffer!!

        while (isRunning()) {
            val read = audioSource.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (read > 0) {
                applyGain(buffer, read)
                audioSink.write(buffer, 0, read, AudioTrack.WRITE_BLOCKING)
            }
        }
    }

    override fun updateConfig(config: AudioProcessingConfig) {
        currentConfig = config
    }

    private fun setupAudioEffects(audioSessionId: Int) {
        platformInputEffects.disable(audioSessionId)
        events.trySend(AudioEngineEvent.NoiseSuppressorAvailability(false))
        events.trySend(AudioEngineEvent.DynamicsProcessingAvailability(false))
    }

    private fun applyGain(buffer: FloatArray, size: Int) {
        val config = currentConfig
        val currentGain = config.microphoneGain
        val ambientGain = config.ambientGain
        if (config.automaticGainControlEnabled && size > 0) {
            var sumSquares = 0.0
            for (i in 0 until size) {
                sumSquares += buffer[i] * buffer[i]
            }
            val inputRms = sqrt(sumSquares / size).toFloat()
            automaticGainController.update(
                inputRms = inputRms,
                appliedGain = currentGain,
                frameSamples = size,
                sampleRate = Constants.Audio.SAMPLE_RATE
            )
        } else {
            automaticGainController.reset()
        }
        
        for (i in 0 until size) {
            // In the fallback processor, we simulate the same logic as native
            // Primary path (gain) + Ambient path (ambientGain)
            val adjustedSample = buffer[i] * automaticGainController.gain
            val primary = adjustedSample * currentGain
            val ambient = adjustedSample * ambientGain
            
            // Basic mix and clip
            val mixed = primary + ambient
            buffer[i] = mixed.coerceIn(-1.0f, 1.0f)
        }
    }

    override fun release() {
        platformInputEffects.release()
        processingBuffer = null
        automaticGainController.reset()
    }

    companion object {
        private const val TAG = "DefaultAudioProcessor"
    }
}
