package com.thivyanstudios.hark.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.thivyanstudios.hark.util.HarkLog

class PlatformInputEffects {
    private val effects = mutableListOf<AudioEffect>()

    fun disable(sessionId: Int) {
        release()
        if (sessionId <= 0) {
            HarkLog.w(TAG, "Cannot disable platform input effects: invalid audio session $sessionId")
            return
        }

        disableEffect("NoiseSuppressor") { NoiseSuppressor.create(sessionId) }
        disableEffect("AcousticEchoCanceler") { AcousticEchoCanceler.create(sessionId) }
        disableEffect("AutomaticGainControl") { AutomaticGainControl.create(sessionId) }
    }

    fun release() {
        effects.forEach(AudioEffect::release)
        effects.clear()
    }

    private inline fun disableEffect(name: String, create: () -> AudioEffect?) {
        val effect = try {
            create()
        } catch (e: RuntimeException) {
            HarkLog.e(TAG, "Could not access platform $name", e)
            return
        }
        if (effect == null) {
            HarkLog.i(TAG, "Platform $name is not available for this input session")
            return
        }

        effects += effect
        if (!effect.hasControl()) {
            HarkLog.w(TAG, "Could not disable platform $name: effect control is unavailable")
            return
        }

        val result = effect.setEnabled(false)
        if (result != AudioEffect.SUCCESS) {
            HarkLog.w(TAG, "Could not disable platform $name (status $result)")
        } else {
            HarkLog.i(TAG, "Disabled platform $name")
        }
    }

    companion object {
        private const val TAG = "PlatformInputEffects"
    }
}
