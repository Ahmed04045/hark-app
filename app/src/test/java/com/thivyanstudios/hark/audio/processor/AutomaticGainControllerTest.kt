package com.thivyanstudios.hark.audio.processor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticGainControllerTest {

    @Test
    fun `quiet speech gain is limited and rises gradually`() {
        val controller = AutomaticGainController()

        repeat(100) {
            controller.update(inputRms = 0.01f, frameSamples = 480, sampleRate = 48_000)
        }

        assertTrue(controller.gain in 6.5f..8.0f)
    }

    @Test
    fun `agc compensates for manual gain instead of driving the limiter`() {
        val controller = AutomaticGainController()

        repeat(100) {
            controller.update(
                inputRms = 0.01f,
                appliedGain = 10.0f,
                frameSamples = 480,
                sampleRate = 48_000
            )
        }

        assertTrue(controller.gain in 1.15f..1.35f)
    }

    @Test
    fun `silence does not increase gain and returns toward unity`() {
        val controller = AutomaticGainController()
        repeat(100) {
            controller.update(inputRms = 0.01f, frameSamples = 480, sampleRate = 48_000)
        }
        val gainDuringSpeech = controller.gain

        repeat(10) {
            controller.update(inputRms = 0.0f, frameSamples = 480, sampleRate = 48_000)
        }

        assertTrue(controller.gain < gainDuringSpeech)
        assertTrue(controller.gain > 1.0f)
        repeat(100) {
            controller.update(inputRms = 0.0f, frameSamples = 480, sampleRate = 48_000)
        }
        assertEquals(1.0f, controller.gain, 0.01f)
    }

    @Test
    fun `loud input reduces gain more quickly than quiet input raises it`() {
        val controller = AutomaticGainController()
        repeat(100) {
            controller.update(inputRms = 0.01f, frameSamples = 480, sampleRate = 48_000)
        }

        val beforeLoudInput = controller.gain
        repeat(10) {
            controller.update(
                inputRms = 0.5f,
                appliedGain = 10.0f,
                frameSamples = 480,
                sampleRate = 48_000
            )
        }

        assertTrue(controller.gain < beforeLoudInput)
        val targetGain = 0.12589254f / (0.5f * 10.0f)
        val reductionFraction = (beforeLoudInput - controller.gain) / (beforeLoudInput - targetGain)
        assertTrue(reductionFraction > 0.6f)
    }

    @Test
    fun `input below noise floor does not trigger gain increase`() {
        val controller = AutomaticGainController()

        repeat(100) {
            controller.update(inputRms = 0.0005f, frameSamples = 480, sampleRate = 48_000)
        }

        assertEquals(1.0f, controller.gain, 0.001f)
    }
}
