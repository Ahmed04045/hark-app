#include "hark_audio_engine.h"
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <oboe/FifoBuffer.h>
#include <rnnoise.h>

#define TAG "HarkAudioEngine"

HarkAudioEngine::HarkAudioEngine() = default;

HarkAudioEngine::~HarkAudioEngine() {
    stop();
}

bool HarkAudioEngine::start(
        int32_t sampleRate, int32_t framesPerBurst, int32_t inputDeviceId,
        bool useCommunicationInputPreset) {
    mShouldBeStreaming.store(false, std::memory_order_release);
    joinRestartThread();

    std::lock_guard<std::mutex> lock(mStreamLock);
    (void) sampleRate;
    // RNNoise operates at 48 kHz; request that rate and let Android route-convert as needed.
    mSampleRate = 48000;
    mFramesPerBurst = framesPerBurst;
    mInputDeviceId = inputDeviceId;
    mUseCommunicationInputPreset = useCommunicationInputPreset;

    bool success = openStreamsLocked();
    mShouldBeStreaming.store(success, std::memory_order_release);
    return success;
}

bool HarkAudioEngine::openStreamsLocked() {
    closeStreams();

    // Reset processing states
    mEnvelope = 0.0f;
    mLimiterGain = 1.0f;
    mLimiterReleaseCoefficient =
            expf(-1.0f / (static_cast<float>(mSampleRate) * 0.080f));
    mRnnoiseOutputOffset = kRnnoiseFrameSize;
    mIsBuffering = true;
    mInputResamplePhase = 0.0f;
    mPreviousInputSample = 0.0f;
    mHasPreviousInputSample = false;
    mOutputResamplePhase = 0.0f;
    mHasOutputCurrentSample = false;
    mHasOutputNextSample = false;

    uint32_t fifoCapacity = std::max(static_cast<uint32_t>(mFramesPerBurst) * 16, static_cast<uint32_t>(4096));
    mFifoBuffer = std::make_unique<oboe::FifoBuffer>(sizeof(float), fifoCapacity);

    // Sherpa-ONNX usually expects 16kHz audio, but we can buffer more for higher rates.
    // Buffer for 10 seconds of audio at the hardware sample rate.
    mTranscriptionFifo = std::make_unique<oboe::FifoBuffer>(sizeof(float), mSampleRate * 10);

    updateHighPassCoefficients(mSampleRate);
    mActiveSpeechPresenceGainDb = mSpeechPresenceGainDb.load(std::memory_order_acquire);
    updateSpeechPresenceCoefficients(mSampleRate, mActiveSpeechPresenceGainDb);
    mRnnoiseState = rnnoise_create(nullptr);
    if (!mRnnoiseState) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to initialize RNNoise");
        closeStreams();
        return false;
    }

    oboe::AudioStreamBuilder inBuilder;
    inBuilder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Shared) // Use Shared for better compatibility
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Mono)
            ->setSampleRate(mSampleRate)
            ->setInputPreset(mUseCommunicationInputPreset
                    ? oboe::InputPreset::VoiceCommunication
                    : oboe::InputPreset::VoiceRecognition)
            ->setDataCallback(this)
            ->setErrorCallback(this);
    if (mInputDeviceId >= 0) {
        inBuilder.setDeviceId(mInputDeviceId);
    }

    oboe::Result result = inBuilder.openStream(mInStream);
    if (result != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Error opening input stream: %s", oboe::convertToText(result));
        closeStreams();
        return false;
    }
    mInputSampleRate = mInStream->getSampleRate();
    mInputChannelCount = mInStream->getChannelCount();
    if (mInputSampleRate <= 0 || mInputChannelCount <= 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Invalid input stream format");
        closeStreams();
        return false;
    }

    oboe::AudioStreamBuilder outBuilder;
    outBuilder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Shared) // Use Shared for better compatibility
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setUsage(oboe::Usage::Media)
            ->setSampleRate(mSampleRate)
            ->setDataCallback(this)
            ->setErrorCallback(this);

    result = outBuilder.openStream(mOutStream);
    if (result != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Error opening output stream: %s", oboe::convertToText(result));
        closeStreams();
        return false;
    }
    mOutputSampleRate = mOutStream->getSampleRate();
    mOutputChannelCount = mOutStream->getChannelCount();
    if (mOutputSampleRate <= 0 || mOutputChannelCount <= 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Invalid output stream format");
        closeStreams();
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG,
            "Audio route: input %d Hz/%d ch (device %d), output %d Hz/%d ch",
            mInputSampleRate, mInputChannelCount, mInputDeviceId,
            mOutputSampleRate, mOutputChannelCount);

    mOutStream->setBufferSizeInFrames(mFramesPerBurst * 2);
    mInStream->requestStart();
    mOutStream->requestStart();

    return true;
}

void HarkAudioEngine::stop() {
    mShouldBeStreaming.store(false, std::memory_order_release);
    joinRestartThread();
    std::lock_guard<std::mutex> lock(mStreamLock);
    closeStreams();
}

void HarkAudioEngine::joinRestartThread() {
    std::lock_guard<std::mutex> lock(mRestartThreadLock);
    if (mRestartThread.joinable()) {
        mRestartThread.join();
    }
}

void HarkAudioEngine::closeStreams() {
    if (mOutStream) { mOutStream->stop(); mOutStream->close(); mOutStream.reset(); }
    if (mInStream) { mInStream->stop(); mInStream->close(); mInStream.reset(); }
    mTranscriptionFifo.reset();
    mFifoBuffer.reset();
    if (mRnnoiseState) {
        rnnoise_destroy(mRnnoiseState);
        mRnnoiseState = nullptr;
    }
    mTranscriptionLevel.store(0.0f, std::memory_order_release);
}

int32_t HarkAudioEngine::readTranscriptionData(float *target, int32_t numFrames) {
    // Called from a JVM thread; the restart thread may swap the FIFO under us
    std::lock_guard<std::mutex> lock(mStreamLock);
    if (!mTranscriptionFifo) return 0;
    return mTranscriptionFifo->read(target, numFrames);
}

float HarkAudioEngine::getTranscriptionLevel() {
    return mTranscriptionLevel.load(std::memory_order_acquire);
}

void HarkAudioEngine::setMicrophoneGain(float gain) { mGain.store(gain, std::memory_order_release); }
void HarkAudioEngine::setNoiseSuppressionEnabled(bool enabled) {
    mIsNoiseSuppressionEnabled.store(enabled, std::memory_order_release);
}
void HarkAudioEngine::setDynamicsProcessingEnabled(bool enabled) {
    mIsDynamicsProcessingEnabled.store(enabled, std::memory_order_release);
}
void HarkAudioEngine::setTranscriptModeEnabled(bool enabled) {
    mIsTranscriptModeEnabled.store(enabled, std::memory_order_release);
}
void HarkAudioEngine::setSpeechPresenceGainDb(float gainDb) {
    mSpeechPresenceGainDb.store(std::clamp(gainDb, 3.0f, 8.0f), std::memory_order_release);
}

oboe::DataCallbackResult HarkAudioEngine::onAudioReady(
        oboe::AudioStream *audioStream,
        void *audioData,
        int32_t numFrames) {

    if (audioStream->getDirection() == oboe::Direction::Input) {
        const auto *inputData = static_cast<const float*>(audioData);
        const int32_t inputChannels = std::max(1, audioStream->getChannelCount());
        const float outputSamplesPerInputSample =
                static_cast<float>(mSampleRate) / static_cast<float>(mInputSampleRate);
        int32_t resampledCount = 0;
        auto flushResampledInput = [&]() {
            if (resampledCount == 0) return;
            const int32_t written = mFifoBuffer->write(mResampledInput, resampledCount);
            if (written < resampledCount) {
                static int32_t overflowLogCounter = 0;
                if (++overflowLogCounter % 100 == 1) {
                    __android_log_print(ANDROID_LOG_WARN, TAG,
                            "Input FIFO full; dropped %d resampled frames",
                            resampledCount - written);
                }
            }
            resampledCount = 0;
        };

        for (int32_t frame = 0; frame < numFrames; ++frame) {
            float monoSample = 0.0f;
            for (int32_t channel = 0; channel < inputChannels; ++channel) {
                monoSample += inputData[frame * inputChannels + channel];
            }
            monoSample /= static_cast<float>(inputChannels);

            if (!mHasPreviousInputSample) {
                mPreviousInputSample = monoSample;
                mHasPreviousInputSample = true;
                continue;
            }

            mInputResamplePhase += outputSamplesPerInputSample;
            while (mInputResamplePhase >= 1.0f) {
                mInputResamplePhase -= 1.0f;
                const float interpolation =
                        1.0f - mInputResamplePhase / outputSamplesPerInputSample;
                mResampledInput[resampledCount++] =
                        mPreviousInputSample +
                        (monoSample - mPreviousInputSample) * interpolation;
                if (resampledCount == kMaxResampledFrames) {
                    flushResampledInput();
                }
            }
            mPreviousInputSample = monoSample;
        }
        flushResampledInput();

        // Keep the transcription side channel mono even if a route negotiates stereo input.
        float currentGain = mGain.load(std::memory_order_acquire);
        float transcriptionGain = currentGain * 10.0f;

        float sumSquares = 0.0f;
        int32_t framesToProcess = std::min(numFrames, kMaxFrames);
        for (int i = 0; i < framesToProcess; ++i) {
            float monoSample = 0.0f;
            for (int32_t channel = 0; channel < inputChannels; ++channel) {
                monoSample += inputData[i * inputChannels + channel];
            }
            monoSample /= static_cast<float>(inputChannels);
            float sample = monoSample * transcriptionGain;
            mGainedBuffer[i] = sample;
            sumSquares += sample * sample;
        }

        // Calculate RMS level for visualization/gating
        float rms = std::sqrt(sumSquares / static_cast<float>(framesToProcess));

        // Exponential moving average for smoothing the level
        float alpha = 0.1f;
        float currentLevel = mTranscriptionLevel.load(std::memory_order_acquire);
        mTranscriptionLevel.store(currentLevel * (1.0f - alpha) + rms * alpha, std::memory_order_release);

        pushToTranscriptionFifo(mGainedBuffer, framesToProcess);
    } else {
        auto *outputData = static_cast<float *>(audioData);
        const int32_t channelCount = audioStream->getChannelCount();

        if (mIsTranscriptModeEnabled.load(std::memory_order_acquire)) {
            std::fill_n(outputData, numFrames * channelCount, 0.0f);
            return oboe::DataCallbackResult::Continue;
        }

        const float currentGain = mGain.load(std::memory_order_acquire);
        const bool noiseSuppressionEnabled =
                mIsNoiseSuppressionEnabled.load(std::memory_order_acquire);
        const bool dynamicsEnabled =
                mIsDynamicsProcessingEnabled.load(std::memory_order_acquire);
        const float requestedGainDb =
                mSpeechPresenceGainDb.load(std::memory_order_acquire);
        if (requestedGainDb != mActiveSpeechPresenceGainDb) {
            mActiveSpeechPresenceGainDb = requestedGainDb;
            updateSpeechPresenceCoefficients(mSampleRate, requestedGainDb);
        }

        const float sourceSamplesPerOutputSample =
                static_cast<float>(mSampleRate) / static_cast<float>(mOutputSampleRate);
        for (int32_t frame = 0; frame < numFrames; ++frame) {
            if (!mHasOutputCurrentSample) {
                mHasOutputCurrentSample = readProcessedSample(
                        &mOutputCurrentSample, noiseSuppressionEnabled,
                        dynamicsEnabled, currentGain);
            }
            if (!mHasOutputCurrentSample) {
                std::fill_n(outputData + frame * channelCount, channelCount, 0.0f);
                continue;
            }
            if (!mHasOutputNextSample) {
                mHasOutputNextSample = readProcessedSample(
                        &mOutputNextSample, noiseSuppressionEnabled,
                        dynamicsEnabled, currentGain);
            }
            if (!mHasOutputNextSample) {
                std::fill_n(outputData + frame * channelCount, channelCount, 0.0f);
                continue;
            }

            while (mOutputResamplePhase >= 1.0f) {
                mOutputCurrentSample = mOutputNextSample;
                mHasOutputNextSample = false;
                mOutputResamplePhase -= 1.0f;
                mHasOutputNextSample = readProcessedSample(
                        &mOutputNextSample, noiseSuppressionEnabled,
                        dynamicsEnabled, currentGain);
                if (!mHasOutputNextSample) break;
            }
            if (!mHasOutputNextSample) {
                std::fill_n(outputData + frame * channelCount, channelCount, 0.0f);
                continue;
            }

            const float sample = mOutputCurrentSample +
                    (mOutputNextSample - mOutputCurrentSample) * mOutputResamplePhase;
            for (int32_t channel = 0; channel < channelCount; ++channel) {
                outputData[frame * channelCount + channel] = sample;
            }
            mOutputResamplePhase += sourceSamplesPerOutputSample;
        }
    }
    return oboe::DataCallbackResult::Continue;
}

bool HarkAudioEngine::readProcessedSample(
        float *sample, bool noiseSuppressionEnabled, bool dynamicsEnabled, float gain) {
    if (mRnnoiseOutputOffset >= kRnnoiseFrameSize) {
        const uint32_t framesAvailable = mFifoBuffer->getFullFramesAvailable();
        constexpr uint32_t kStartupBufferFrames = kRnnoiseFrameSize * 2;
        if (mIsBuffering && framesAvailable < kStartupBufferFrames) return false;
        if (framesAvailable < kRnnoiseFrameSize) return false;

        const int32_t framesRead = mFifoBuffer->read(mRnnoiseInput, kRnnoiseFrameSize);
        if (framesRead != kRnnoiseFrameSize) return false;
        mIsBuffering = false;
        processRnnoiseFrame(noiseSuppressionEnabled, dynamicsEnabled, gain);
    }
    *sample = mRnnoiseOutput[mRnnoiseOutputOffset++];
    return true;
}

void HarkAudioEngine::pushToTranscriptionFifo(const float* data, int32_t numFrames) {
    if (!mTranscriptionFifo || !data || numFrames <= 0) return;

    static int32_t logCounter = 0;
    if (++logCounter % 1000 == 0) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "Pushing to transcription FIFO: %d frames", numFrames);
    }

    int32_t written = mTranscriptionFifo->write(data, numFrames);
    if (written < numFrames) {
        // If the FIFO is full, we simply drop the remaining frames to maintain real-time safety.
    }
}


float HarkAudioEngine::Biquad::process(float input) {
    const float output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
    x2 = x1;
    x1 = input;
    y2 = y1;
    y1 = output;
    return output;
}

void HarkAudioEngine::Biquad::reset() {
    x1 = x2 = y1 = y2 = 0.0f;
}

void HarkAudioEngine::updateHighPassCoefficients(int32_t sampleRate) {
    const float w0 = 2.0f * static_cast<float>(M_PI) * 90.0f / static_cast<float>(sampleRate);
    const float cosW0 = cosf(w0);
    const float sinW0 = sinf(w0);
    const float alpha = sinW0 / (2.0f * 0.70710678f);
    const float a0 = 1.0f + alpha;
    mHighPass.b0 = ((1.0f + cosW0) / 2.0f) / a0;
    mHighPass.b1 = -(1.0f + cosW0) / a0;
    mHighPass.b2 = mHighPass.b0;
    mHighPass.a1 = (-2.0f * cosW0) / a0;
    mHighPass.a2 = (1.0f - alpha) / a0;
    mHighPass.reset();
}

void HarkAudioEngine::updateSpeechPresenceCoefficients(int32_t sampleRate, float gainDb) {
    const float A = powf(10.0f, gainDb / 40.0f);
    const float w0 = 2.0f * static_cast<float>(M_PI) * 3200.0f / static_cast<float>(sampleRate);
    const float cosW0 = cosf(w0);
    const float alpha = sinf(w0) / (2.0f * 1.0f);
    const float a0 = 1.0f + alpha / A;
    mSpeechPresenceEq.b0 = (1.0f + alpha * A) / a0;
    mSpeechPresenceEq.b1 = (-2.0f * cosW0) / a0;
    mSpeechPresenceEq.b2 = (1.0f - alpha * A) / a0;
    mSpeechPresenceEq.a1 = (-2.0f * cosW0) / a0;
    mSpeechPresenceEq.a2 = (1.0f - alpha / A) / a0;
    mSpeechPresenceEq.reset();
}

void HarkAudioEngine::processRnnoiseFrame(
        bool noiseSuppressionEnabled, bool dynamicsEnabled, float gain) {
    for (int32_t i = 0; i < kRnnoiseFrameSize; ++i) {
        const float highPassed = mHighPass.process(mRnnoiseInput[i]);
        mRnnoiseScratch[i] = highPassed * 32768.0f;
    }

    if (noiseSuppressionEnabled && mRnnoiseState) {
        rnnoise_process_frame(mRnnoiseState, mRnnoiseOutput, mRnnoiseScratch);
    } else {
        std::copy_n(mRnnoiseScratch, kRnnoiseFrameSize, mRnnoiseOutput);
    }

    for (int32_t i = 0; i < kRnnoiseFrameSize; ++i) {
        const float denoised = mRnnoiseOutput[i] / 32768.0f;
        const float clarityAdjusted = mSpeechPresenceEq.process(denoised);
        float gained = clarityAdjusted * gain;
        if (dynamicsEnabled) {
            gained = applySoftKneeLimiter(gained);
        }
        mRnnoiseOutput[i] = applyPeakLimiter(gained);
    }
    mRnnoiseOutputOffset = 0;
}

float HarkAudioEngine::applySoftKneeLimiter(float input) {
    constexpr float threshold = 0.75f;
    constexpr float kneeWidth = 0.15f;
    constexpr float attack = 0.05f;
    constexpr float release = 0.0005f;
    const float absInput = std::abs(input);

    if (absInput > mEnvelope) {
        mEnvelope = absInput * attack + mEnvelope * (1.0f - attack);
    } else {
        mEnvelope = absInput * release + mEnvelope * (1.0f - release);
    }

    if (mEnvelope <= threshold - kneeWidth / 2.0f) return input;
    float targetGain = threshold / mEnvelope;
    if (mEnvelope < threshold + kneeWidth / 2.0f) {
        const float diff = mEnvelope - (threshold - kneeWidth / 2.0f);
        const float reduction = (diff * diff) / (2.0f * kneeWidth);
        targetGain = (mEnvelope - reduction) / mEnvelope;
    }
    return input * targetGain;
}

float HarkAudioEngine::applyPeakLimiter(float input) {
    constexpr float threshold = 0.70794578f; // -3 dBFS
    const float absInput = std::abs(input);
    const float targetGain = absInput > threshold ? threshold / absInput : 1.0f;

    if (targetGain < mLimiterGain) {
        mLimiterGain = targetGain;
    } else {
        mLimiterGain = mLimiterReleaseCoefficient * mLimiterGain +
                (1.0f - mLimiterReleaseCoefficient) * targetGain;
    }

    return std::clamp(input * mLimiterGain, -threshold, threshold);
}

void HarkAudioEngine::onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result error) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "Stream error: %s", oboe::convertToText(error));

    // Oboe requires reopening the stream after an audio route disconnect.
    if (error != oboe::Result::ErrorDisconnected) return;
    if (!mShouldBeStreaming.load(std::memory_order_acquire)) return;

    bool expected = false;
    if (!mRestartPending.compare_exchange_strong(expected, true)) return;

    std::lock_guard<std::mutex> lock(mRestartThreadLock);
    if (mRestartThread.joinable()) {
        mRestartThread.join();
    }
    mRestartThread = std::thread([this] { restartStreams(); });
}

void HarkAudioEngine::restartStreams() {
    for (int attempt = 0; attempt < 3; ++attempt) {
        // Give the new audio route time to settle before reopening
        std::this_thread::sleep_for(std::chrono::milliseconds(250 << attempt));
        if (!mShouldBeStreaming.load(std::memory_order_acquire)) break;

        std::lock_guard<std::mutex> lock(mStreamLock);
        if (!mShouldBeStreaming.load(std::memory_order_acquire)) break;

        if (openStreamsLocked()) {
            __android_log_print(ANDROID_LOG_INFO, TAG,
                    "Streams restarted after disconnect (attempt %d)", attempt + 1);
            mRestartPending.store(false, std::memory_order_release);
            return;
        }
        __android_log_print(ANDROID_LOG_WARN, TAG,
                "Stream restart attempt %d failed", attempt + 1);
    }
    __android_log_print(ANDROID_LOG_ERROR, TAG, "Giving up on stream restart");
    mRestartPending.store(false, std::memory_order_release);
}
