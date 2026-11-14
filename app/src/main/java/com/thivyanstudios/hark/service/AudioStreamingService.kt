package com.thivyanstudios.hark.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.RequiresPermission
import com.thivyanstudios.hark.R
import com.thivyanstudios.hark.audio.AudioEngine
import com.thivyanstudios.hark.audio.model.AudioEngineEvent
import com.thivyanstudios.hark.data.STTModelManager
import com.thivyanstudios.hark.data.UserPreferencesRepository
import com.thivyanstudios.hark.di.IoDispatcher
import com.thivyanstudios.hark.di.MainDispatcher
import com.thivyanstudios.hark.util.HarkLog
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.pow

@AndroidEntryPoint
class AudioStreamingService : Service(), AudioStreamingController {

    private val binder = LocalBinder()
    
    @Inject
    lateinit var sttModelManager: STTModelManager

    private val _isStreaming = MutableStateFlow(value = false)
    override val isStreaming = _isStreaming.asStateFlow()

    private val _transcription = MutableStateFlow("")
    override val transcription = _transcription.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    override val audioLevel = _audioLevel.asStateFlow()

    private val _audioInputDevices = MutableStateFlow<List<AudioInputDevice>>(emptyList())
    override val audioInputDevices = _audioInputDevices.asStateFlow()

    private val _selectedInputDeviceId = MutableStateFlow<Int?>(null)
    override val selectedInputDeviceId = _selectedInputDeviceId.asStateFlow()

    private val fullTranscript = StringBuilder()
    private var lastTranscriptionTime = 0L

    private var wakeLock: PowerManager.WakeLock? = null

    private var recognizer: OnlineRecognizer? = null
    private var sherpaStream: OnlineStream? = null

    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository
    
    @Inject
    lateinit var audioEngine: AudioEngine

    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    @MainDispatcher
    lateinit var mainDispatcher: CoroutineDispatcher

    @Inject
    @IoDispatcher
    lateinit var ioDispatcher: CoroutineDispatcher

    private lateinit var serviceScope: CoroutineScope
    private var stopJob: Job? = null
    private var enableBluetoothHeadsetSupport = false
    private var lastTranscriptModeEnabled: Boolean? = null
    private var lastSelectedModelId: String? = null

    private val _hearingAidConnected = MutableStateFlow(false)
    override val hearingAidConnected = _hearingAidConnected.asStateFlow()

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        @RequiresPermission(allOf = [Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT])
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            HarkLog.i(TAG, "Audio devices added")
            updateHearingAidStatus()
            if (refreshInputDevices() && _isStreaming.value && hasRequiredPermissions()) {
                restartStreaming()
            }
        }

        @RequiresPermission(allOf = [Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT])
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            HarkLog.i(TAG, "Audio devices removed")
            updateHearingAidStatus()
            val selectedDeviceRemoved = removedDevices?.any {
                it.id == _selectedInputDeviceId.value
            } == true
            refreshInputDevices()
            if ((selectedDeviceRemoved || removedDevices?.isNotEmpty() == true) &&
                _isStreaming.value && hasRequiredPermissions()) {
                restartStreaming()
            }
        }
    }

    private fun isCompatibleDevice(type: Int): Boolean {
        return if (enableBluetoothHeadsetSupport) {
            when (type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                -> true
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        (type == AudioDeviceInfo.TYPE_BLE_HEADSET || 
                        type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
                    } else false
                }
            }
        } else {
            type == AudioDeviceInfo.TYPE_HEARING_AID
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val hasRecordAudio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasBluetoothConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return hasRecordAudio && hasBluetoothConnect
    }

    inner class LocalBinder : Binder() {
        fun getService(): AudioStreamingController = this@AudioStreamingService
    }

    override fun onBind(intent: Intent?): IBinder {
        HarkLog.i(TAG, "onBind")
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        HarkLog.i(TAG, "onCreate")
        serviceScope = CoroutineScope(mainDispatcher + SupervisorJob())

        userPreferencesRepository.userPreferencesFlow
            .distinctUntilChanged()
            .onEach { prefs ->
                val newEnableSupport = prefs.enableBluetoothHeadsetSupport
                if (newEnableSupport != enableBluetoothHeadsetSupport) {
                    HarkLog.i(TAG, "Bluetooth headset support preference changed: $newEnableSupport")
                    bluetoothSupportChange(newEnableSupport)
                }

                val newTranscriptMode = prefs.transcriptModeEnabled
                if (lastTranscriptModeEnabled != null && newTranscriptMode != lastTranscriptModeEnabled) {
                    HarkLog.i(TAG, "Transcript mode changed: $newTranscriptMode, stopping stream")
                    if (_isStreaming.value) {
                        stopStreaming()
                    }
                }
                lastTranscriptModeEnabled = newTranscriptMode

                val newModelId = prefs.selectedModelId
                if (lastSelectedModelId != null && newModelId != lastSelectedModelId) {
                    HarkLog.i(TAG, "Model selection changed: $newModelId, stopping stream")
                    if (_isStreaming.value) {
                        stopStreaming()
                    }
                }
                lastSelectedModelId = newModelId

                val gain = 10.0.pow(prefs.microphoneGain / 20.0).toFloat()
                audioEngine.setMicrophoneGain(gain)
                audioEngine.setNoiseSuppressionEnabled(prefs.noiseSuppressionEnabled)
                audioEngine.setDynamicsProcessingEnabled(prefs.dynamicsProcessingEnabled)
                audioEngine.setSpeechPresenceGainDb(prefs.speechPresenceGainDb)
                audioEngine.setTranscriptModeEnabled(prefs.transcriptModeEnabled)
            }
            .launchIn(serviceScope)
            
        audioEngine.events.receiveAsFlow()
            .onEach { event ->
                when(event) {
                    is AudioEngineEvent.NoiseSuppressorAvailability -> {
                        if (!event.isAvailable) {
                            HarkLog.w(TAG, "Noise suppressor not available")
                            audioEngine.sendError(getString(R.string.noise_suppression_not_available))
                        }
                    }
                    is AudioEngineEvent.DynamicsProcessingAvailability -> {
                        if (!event.isAvailable) {
                            HarkLog.w(TAG, "Dynamics processing not available")
                            audioEngine.sendError(getString(R.string.dynamics_processing_not_available))
                        }
                    }
                }
            }
            .launchIn(serviceScope)

        // Stop streaming if model library changes (download or delete)
        sttModelManager.modelStoreUpdateTrigger
            .onEach {
                if (_isStreaming.value) {
                    HarkLog.i(TAG, "Model library changed, stopping stream")
                    stopStreaming()
                }
            }
            .launchIn(serviceScope)

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hark::AudioStreamingWakeLock")
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        updateHearingAidStatus()
        refreshInputDevices()
    }

    private fun bluetoothSupportChange(newEnableSupport: Boolean) {
        enableBluetoothHeadsetSupport = newEnableSupport
        if (_isStreaming.value) {
            stopStreaming()
        }
        updateHearingAidStatus()
    }

    private fun updateHearingAidStatus() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val isConnected = devices.any { isCompatibleDevice(it.type) }
        HarkLog.i(TAG, "Hearing aid connected: $isConnected")
        _hearingAidConnected.value = isConnected
    }

    private fun refreshInputDevices(): Boolean {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .mapNotNull { device ->
                val label = when (device.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone Built-in Mic"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset Mic"
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset Mic"
                    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset Mic"
                    else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        device.type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                        "Bluetooth Headset Mic"
                    } else {
                        null
                    }
                }
                label?.let { AudioInputDevice(device.id, it) }
            }
            .distinctBy { it.id }
            .sortedBy { if (it.label == "Phone Built-in Mic") 0 else 1 }

        val previousId = _selectedInputDeviceId.value
        val nextId = previousId?.takeIf { id -> devices.any { it.id == id } }
            ?: devices.firstOrNull { it.label == "Phone Built-in Mic" }?.id
            ?: devices.firstOrNull()?.id
        _audioInputDevices.value = devices
        _selectedInputDeviceId.value = nextId
        return previousId != nextId
    }

    override fun selectInputDevice(deviceId: Int) {
        if (_audioInputDevices.value.none { it.id == deviceId }) return
        if (_selectedInputDeviceId.value == deviceId) return
        _selectedInputDeviceId.value = deviceId
        if (_isStreaming.value && hasRequiredPermissions()) {
            restartStreaming()
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT])
    private fun restartStreaming() {
        HarkLog.i(TAG, "Restarting streaming")
        if (_isStreaming.value) {
            stopStreaming()
            serviceScope.launch {
                stopJob?.join()
                startStreaming()
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT])
    @SuppressLint("ForegroundServiceType")
    override fun startStreaming() {
        if (_isStreaming.value) return

        HarkLog.i(TAG, "Starting streaming")
        _isStreaming.value = true

        serviceScope.launch {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startForeground(
                        NOTIFICATION_ID, 
                        notificationHelper.createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID, 
                        notificationHelper.createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notificationHelper.createNotification())
                }
                
                wakeLock?.acquire(60 * 60 * 1000L) // 1 hour timeout
                withContext(ioDispatcher) {
                    // Initialize Sherpa before starting engine
                    prepareSherpa()
                    audioEngine.start(_selectedInputDeviceId.value ?: -1)
                    startTranscriptionLoop()
                    startLevelMonitoringLoop()
                }
            } catch (e: Exception) {
                HarkLog.e(TAG, "Failed to start streaming", e)
                stopStreaming()
            }
        }
    }

    private suspend fun prepareSherpa() = withContext(ioDispatcher) {
        val prefs = userPreferencesRepository.userPreferencesFlow.first()
        val modelId = prefs.selectedModelId
        val model = sttModelManager.availableModels.find { it.id == modelId } ?: sttModelManager.availableModels.first()
        
        if (!sttModelManager.isModelDownloaded(model)) {
            HarkLog.i(TAG, "No model downloaded, skipping Sherpa initialization.")
            return@withContext
        }
        
        try {
            // 1. Initialize the base config
            val config = OnlineRecognizerConfig()

            // 2. Point it to your downloaded files
            config.modelConfig.transducer.encoder = File(filesDir, model.encoderFileName).absolutePath
            config.modelConfig.transducer.decoder = File(filesDir, model.decoderFileName).absolutePath
            config.modelConfig.transducer.joiner = File(filesDir, model.joinerFileName).absolutePath
            config.modelConfig.tokens = File(filesDir, model.tokensFileName).absolutePath

            config.decodingMethod = "modified_beam_search"
            config.maxActivePaths = 4
            config.enableEndpoint = true
            config.endpointConfig.rule1.minTrailingSilence = 1.5f
            config.endpointConfig.rule2.minTrailingSilence = 0.8f

            // Sherpa-ONNX streaming models expect 16kHz feature sample rate.
            // Input audio from hardware (e.g. 48kHz) passed via acceptWaveform will be resampled automatically.
            config.featConfig.sampleRate = 16000
            config.featConfig.featureDim = 80
            config.modelConfig.numThreads = prefs.whisperThreads
            config.modelConfig.debug = false
            
            // Initialize the engine with these bounds
            recognizer = OnlineRecognizer(null, config)
            sherpaStream = recognizer?.createStream()

            HarkLog.i(TAG, "Sherpa-ONNX initialized successfully with model: ${model.name}")
        } catch (e: Exception) {
            HarkLog.e(TAG, "Failed to initialize Sherpa-ONNX", e)
            audioEngine.sendError("STT Engine failed to initialize")
        }
    }

    override fun clearTranscription() {
        fullTranscript.setLength(0)
        _transcription.value = ""
    }

    private fun startLevelMonitoringLoop() {
        serviceScope.launch(ioDispatcher) {
            while (_isStreaming.value) {
                val level = audioEngine.getTranscriptionLevel()
                _audioLevel.value = level
                kotlinx.coroutines.delay(50L) // 20fps for UI smoothness
            }
        }
    }

    private fun startTranscriptionLoop() {
        serviceScope.launch(ioDispatcher) {
            val currentRecognizer = recognizer ?: return@launch
            val currentStream = sherpaStream ?: return@launch

            val inputSampleRate = audioEngine.getOptimalSampleRate()

            val bufferSize = 1600 * 2 // 100ms of audio
            val audioBuffer = FloatArray(bufferSize)
            
            fullTranscript.setLength(0)
            
            while (_isStreaming.value) {
                val read = audioEngine.readTranscriptionData(audioBuffer, 0, bufferSize)
                if (read > 0) {
                    // If the read buffer was partial, slice it down to the exact data read
                    val activeSamples = if (read < audioBuffer.size) {
                        audioBuffer.copyOfRange(0, read)
                    } else {
                        audioBuffer
                    }

                    // Pass the hardware-rate audio data into Sherpa
                    currentStream.acceptWaveform(activeSamples, sampleRate = inputSampleRate)
                    
                    while (currentRecognizer.isReady(currentStream)) {
                        currentRecognizer.decode(currentStream)
                    }
                    
                    val result = currentRecognizer.getResult(currentStream)
                    if (result.text.isNotBlank()) {
                        val newText = result.text.trim()
                        HarkLog.d(TAG, "Sherpa Partial Result: '$newText'")
                        
                        // Handle sentences with fullTranscript to avoid infinite growth in a single result
                        val displayResult = if (fullTranscript.isNotEmpty()) {
                            "$fullTranscript $newText"
                        } else {
                            newText
                        }

                        if (displayResult != _transcription.value) {
                             _transcription.value = displayResult
                             lastTranscriptionTime = System.currentTimeMillis()
                        }
                    }

                    if (currentRecognizer.isEndpoint(currentStream)) {
                        val finalResult = currentRecognizer.getResult(currentStream).text
                        HarkLog.i(TAG, "Sherpa Endpoint Detected. Final: '$finalResult'")
                        if (finalResult.isNotBlank()) {
                            if (fullTranscript.isNotEmpty()) fullTranscript.append(" ")
                            fullTranscript.append(finalResult.trim())
                        }
                        currentRecognizer.reset(currentStream)
                    }
                }
                kotlinx.coroutines.delay(30L) // Fast loop for low latency
            }
        }
    }

    override fun stopStreaming() {
        if (!_isStreaming.value) return

        HarkLog.i(TAG, "Stopping streaming")
        _isStreaming.value = false
        
        fullTranscript.setLength(0)
        _transcription.value = ""
        _audioLevel.value = 0f

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopJob = serviceScope.launch {
            withContext(ioDispatcher) {
                audioEngine.stop()
                recognizer?.release()
                recognizer = null
                sherpaStream = null
            }
        }
    }

    override fun onDestroy() {
        HarkLog.i(TAG, "onDestroy")
        stopStreaming()
        serviceScope.cancel()
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AudioStreamingService"
        private const val NOTIFICATION_ID = 1
    }
}
