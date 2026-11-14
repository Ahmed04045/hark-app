# HARK — Hearing Aid Relay Kit

HARK is an Android app for relaying microphone audio to compatible hearing devices or headsets, with optional on-device speech transcription. It is built with Kotlin, Jetpack Compose, and a native C++ audio engine.

> HARK is an assistive listening tool, not a medical device. Audio quality, latency, and device compatibility depend on your phone, Android version, and connected audio equipment.

## Features

- Live microphone audio relay with configurable audio processing.
- Optional live English transcription using a streaming Sherpa-ONNX model on the device.
- Transcript-only mode for captions without audio output.
- Local transcription history and user preferences.
- Audio routing for compatible hearing aids, Bluetooth devices, and wired or USB audio equipment. Available routes vary by device and Android version.
- A Kotlin audio fallback for devices where the native audio engine cannot be used.

## Requirements

- Android 9 (API 28) or later.
- A recent Android Studio installation.
- JDK 17 or later.
- Android SDK Platform 37, Android NDK, and CMake 3.22.1 or later.

For the best audio experience, test on a physical Android device with your intended hearing aids or headset. Emulator audio routing does not represent every real-device configuration.

## Build and run

Clone the repository and open it in Android Studio:

```bash
git clone https://github.com/Ahmed04045/hark-app.git
cd hark-app
```

Allow Gradle to sync, then run the `app` configuration on a connected device. You can also build a debug APK from a terminal:

```bash
# macOS or Linux
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. The first build needs network access to resolve dependencies and fetch the pinned RNNoise source.

HARK requests microphone access to capture audio. Android may also request notification permission for the foreground audio service. To use transcription, download the available model in the app; model files are stored in the app's private storage.

## Transcription and privacy

Speech recognition runs on the device with a locally stored Sherpa-ONNX streaming model. The model is downloaded from Hugging Face when requested, so an internet connection is needed for that download. HARK does not send captured audio to a HARK-operated transcription service. Captions and any saved transcription history are kept on the device.

The app also keeps technical diagnostics locally. Review the in-app privacy/logging information before choosing to share a diagnostic log.

## Project layout

```text
app/src/main/
├── cpp/       Native audio engine and JNI bridge
├── java/      Android app, audio, transcription, storage, and UI
├── res/       Android resources
└── assets/    Bundled notices
docs/          Architecture and engine documentation
```

More detail is available in:

- [System architecture](docs/ARCHITECTURE.md)
- [Audio processing engine](docs/AUDIO_ENGINE.md)
- [Speech-to-text pipeline](docs/TRANSCRIPTION_ENGINE.md)

## License

HARK's original source code is distributed under the **BSD 3-Clause License**. See [LICENSE](LICENSE) for the copyright notice, conditions, and disclaimer. If you redistribute the source, retain that notice and the license terms; if you redistribute a binary, reproduce them in its documentation or other accompanying materials. The license also restricts using the copyright holder's or contributors' names to endorse derived products without prior written permission.

Third-party libraries, bundled components, and downloaded speech models may have separate licenses and terms. Their inclusion does not change those terms or make them part of HARK's BSD license. Review the applicable upstream notices before redistributing them. The bundled RNNoise notice is in `app/src/main/assets/licenses/RNNOISE.txt`.

## Contributing

Bug reports and pull requests are welcome. When reporting an audio or routing issue, include your Android version, device model, and the type of connected audio device, and avoid attaching private recordings or transcripts.
