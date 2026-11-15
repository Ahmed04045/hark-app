#ifndef HARK_AUDIO_ENGINE_H
#define HARK_AUDIO_ENGINE_H

#include <oboe/Oboe.h>
#include <mutex>
#include <atomic>
#include <memory>
#include <cstdint>
#include <vector>
#include <thread>

struct DenoiseState;

namespace oboe {
    class FifoBuffer;
}

class HarkAudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    HarkAudioEngine();
    ~HarkAudioEngine() override;

    bool start(int32_t sampleRate, int32_t framesPerBurst, int32_t inputDeviceId);
    void stop();

    int32_t readTranscriptionData(float *target, int32_t numFrames);
    float getTranscriptionLevel();

    void setMicrophoneGain(float gain);
    void setNoiseSuppressionEnabled(bool enabled);
    void setDynamicsProcessingEnabled(bool enabled);
    void setTranscriptModeEnabled(bool enabled);
    void setSpeechPresenceGainDb(float gainDb);

    // From oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream *audioStream,
            void *audioData,
            int32_t numFrames) override;

    // From oboe::AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result error) override;

private:
    std::shared_ptr<oboe::AudioStream> mInStream;
    std::shared_ptr<oboe::AudioStream> mOutStream;

    std::unique_ptr<oboe::FifoBuffer> mFifoBuffer;
    std::unique_ptr<oboe::FifoBuffer> mTranscriptionFifo;

    // Use atomic for thread-safe access from audio thread without locking
    std::atomic<float> mGain{1.0f};
    std::atomic<bool> mIsNoiseSuppressionEnabled{true};
    std::atomic<bool> mIsDynamicsProcessingEnabled{false};
    std::atomic<bool> mIsTranscriptModeEnabled{false};

    // Live audio level for transcription gating/visuals
    std::atomic<float> mTranscriptionLevel{0.0f};

    // Processing state (only accessed on audio thread)
    float mEnvelope = 0.0f;
    struct Biquad {
        float b0 = 1.0f;
        float b1 = 0.0f;
        float b2 = 0.0f;
        float a1 = 0.0f;
        float a2 = 0.0f;
        float x1 = 0.0f;
        float x2 = 0.0f;
        float y1 = 0.0f;
        float y2 = 0.0f;

        float process(float input);
        void reset();
    };

    Biquad mHighPass;
    Biquad mSpeechPresenceEq;
    std::atomic<float> mSpeechPresenceGainDb{6.0f};
    float mActiveSpeechPresenceGainDb = -1.0f;

    static constexpr int32_t kRnnoiseFrameSize = 480;
    DenoiseState *mRnnoiseState = nullptr;
    float mRnnoiseInput[kRnnoiseFrameSize]{};
    float mRnnoiseScratch[kRnnoiseFrameSize]{};
    float mRnnoiseOutput[kRnnoiseFrameSize]{};
    int32_t mRnnoiseOutputOffset = kRnnoiseFrameSize;
    float mLimiterGain = 1.0f;
    float mLimiterReleaseCoefficient = 0.0f;

    bool mIsBuffering = true;

    std::mutex mStreamLock;

    // Stream restart on disconnect (see onErrorAfterClose).
    std::atomic<bool> mShouldBeStreaming{false};
    std::atomic<bool> mRestartPending{false};
    std::thread mRestartThread;
    std::mutex mRestartThreadLock;
    int32_t mFramesPerBurst = 192;
    int32_t mInputDeviceId = -1;
    static constexpr int32_t kMaxFrames = 2048;
    int32_t mInputSampleRate = 48000;
    int32_t mInputChannelCount = 1;
    int32_t mOutputSampleRate = 48000;
    int32_t mOutputChannelCount = 2;
    float mInputResamplePhase = 0.0f;
    float mPreviousInputSample = 0.0f;
    bool mHasPreviousInputSample = false;
    static constexpr int32_t kMaxResampledFrames = kMaxFrames * 6 + 8;
    float mResampledInput[kMaxResampledFrames]{};
    float mOutputCurrentSample = 0.0f;
    float mOutputNextSample = 0.0f;
    float mOutputResamplePhase = 0.0f;
    bool mHasOutputCurrentSample = false;
    bool mHasOutputNextSample = false;

    bool openStreamsLocked();
    void restartStreams();
    void joinRestartThread();

    void updateHighPassCoefficients(int32_t sampleRate);
    void updateSpeechPresenceCoefficients(int32_t sampleRate, float gainDb);
    void closeStreams();
    void processRnnoiseFrame(bool noiseSuppressionEnabled, bool dynamicsEnabled, float gain);
    bool readProcessedSample(float *sample, bool noiseSuppressionEnabled,
                             bool dynamicsEnabled, float gain);
    float applySoftKneeLimiter(float input);
    float applyPeakLimiter(float input);

    // Resampling for transcription
    void pushToTranscriptionFifo(const float* data, int32_t numFrames);
    int32_t mSampleRate = 48000;

    // Pre-allocated buffers for audio processing to avoid heap allocation in callback
    float mGainedBuffer[kMaxFrames]{};
};

#endif //HARK_AUDIO_ENGINE_H
