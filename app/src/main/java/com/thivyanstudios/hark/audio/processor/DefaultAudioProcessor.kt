package com.thivyanstudios.hark.audio.processor

import android.media.AudioRecord
import android.media.AudioTrack
import com.thivyanstudios.hark.audio.PlatformInputEffects
import com.thivyanstudios.hark.audio.model.AudioEngineEvent
import com.thivyanstudios.hark.audio.model.AudioProcessingConfig
import com.thivyanstudios.hark.audio.stream.AudioSink
import com.thivyanstudios.hark.audio.stream.AudioSource
import kotlinx.coroutines.channels.Channel

/**
 * A simplified Java-based audio processor used only as a fallback 
 * if the high-performance Native (Oboe) engine fails to start.
 */
class DefaultAudioProcessor(private val events: Channel<AudioEngineEvent>) : AudioProcessor {

    @Volatile
    private var currentConfig = AudioProcessingConfig()
    
    // Pre-allocated buffer to reduce GC pressure during audio processing
    private var processingBuffer: FloatArray? = null
    private var automaticGain = 1.0f
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
            val inputRms = kotlin.math.sqrt(sumSquares / size).toFloat()
            val targetGain = if (inputRms < 0.0001f) 1.0f else {
                (0.06309573f / inputRms).coerceIn(0.25f, 16.0f)
            }
            val timeConstant = if (targetGain > automaticGain) 0.45f else 0.06f
            val smoothing = kotlin.math.exp(-size / (44100.0 * timeConstant)).toFloat()
            automaticGain = targetGain + smoothing * (automaticGain - targetGain)
        } else {
            automaticGain = 1.0f
        }
        
        for (i in 0 until size) {
            // In the fallback processor, we simulate the same logic as native
            // Primary path (gain) + Ambient path (ambientGain)
            val adjustedSample = buffer[i] * automaticGain
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
        automaticGain = 1.0f
    }

    companion object {
        private const val TAG = "DefaultAudioProcessor"
    }
}
