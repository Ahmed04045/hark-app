package com.thivyanstudios.hark.ui.viewmodel

import app.cash.turbine.test
import android.content.Context
import android.os.PowerManager
import com.thivyanstudios.hark.audio.AudioEngine
import com.thivyanstudios.hark.data.STTModelManager
import com.thivyanstudios.hark.data.TranscriptionRepository
import com.thivyanstudios.hark.data.UserPreferencesRepository
import com.thivyanstudios.hark.data.model.UserPreferences
import com.thivyanstudios.hark.service.AudioServiceManager
import com.thivyanstudios.hark.service.AudioStreamingController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    
    private lateinit var audioServiceManager: AudioServiceManager
    private lateinit var userPreferencesRepository: UserPreferencesRepository
    private lateinit var transcriptionRepository: TranscriptionRepository
    private lateinit var sttModelManager: STTModelManager
    private lateinit var audioEngine: AudioEngine
    private lateinit var viewModel: MainViewModel

    private val serviceFlow = MutableStateFlow<AudioStreamingController?>(null)
    private val prefsFlow = MutableStateFlow(UserPreferences())
    private val errorEvents = MutableSharedFlow<String>()

    private fun mockService(isStreamingValue: Boolean, hearingAidConnectedValue: Boolean) =
        mock<AudioStreamingController> {
            on { isStreaming } doReturn MutableStateFlow(isStreamingValue)
            on { hearingAidConnected } doReturn MutableStateFlow(hearingAidConnectedValue)
            on { transcription } doReturn MutableStateFlow("")
            on { audioLevel } doReturn MutableStateFlow(0f)
            on { audioInputDevices } doReturn MutableStateFlow(emptyList())
            on { selectedInputDeviceId } doReturn MutableStateFlow(null)
        }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        audioServiceManager = mock {
            on { service } doReturn serviceFlow
        }
        userPreferencesRepository = mock {
            on { userPreferencesFlow } doReturn prefsFlow
        }
        transcriptionRepository = mock {
            on { allTranscriptions } doReturn MutableStateFlow(emptyList())
        }
        audioEngine = mock {
            on { errorEvents } doReturn errorEvents
        }
        sttModelManager = mock {
            on { modelStoreUpdateTrigger } doReturn MutableStateFlow(0L)
            on { availableModels } doReturn emptyList()
        }
        val context = mock<Context> {
            on { getSystemService(Context.POWER_SERVICE) } doReturn mock<PowerManager>()
        }
        
        viewModel = MainViewModel(
            context,
            audioServiceManager,
            userPreferencesRepository,
            transcriptionRepository,
            sttModelManager,
            audioEngine
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `toggleStreaming stops streaming if already streaming`() = runTest {
        val mockService = mockService(true, false)
        serviceFlow.value = mockService
        
        viewModel.toggleStreaming("Connect Message")
        
        verify(mockService).stopStreaming()
    }

    @Test
    fun `toggleStreaming starts streaming if not streaming and hearing aid connected`() = runTest {
        val mockService = mockService(false, true)
        serviceFlow.value = mockService
        
        viewModel.toggleStreaming("Connect Message")
        
        verify(mockService).startStreaming()
    }

    @Test
    fun `toggleStreaming starts streaming if not streaming and bypass is enabled even if not connected`() = runTest {
        val mockService = mockService(false, false)
        serviceFlow.value = mockService
        
        // Use turbine to wait for the UI state to actually reflect the preference change
        viewModel.uiState.test {
            awaitItem() // Initial state
            
            // Enable bypass
            prefsFlow.value = UserPreferences(bypassBluetoothChecks = true)
            
            val updatedState = awaitItem()
            assertEquals(true, updatedState.bypassBluetoothChecks)
            
            // Now that we KNOW the state has updated, call the action
            viewModel.toggleStreaming("Connect Message")
            
            verify(mockService).startStreaming()
        }
    }

    @Test
    fun `toggleStreaming shows error and does not start if not connected and no bypass`() = runTest {
        val mockService = mockService(false, false)
        serviceFlow.value = mockService
        
        // Ensure bypass is disabled
        prefsFlow.value = UserPreferences(bypassBluetoothChecks = false)
        
        viewModel.snackbarEvents.test {
            viewModel.toggleStreaming("Connect Message")
            
            assertEquals("Connect Message", awaitItem())
            verify(mockService, org.mockito.kotlin.never()).startStreaming()
        }
    }

    @Test
    fun `uiState correctly merges service and preference states`() = runTest {
        val mockService = mockService(true, false)
        
        viewModel.uiState.test {
            awaitItem() // Initial empty state
            
            serviceFlow.value = mockService
            var item = awaitItem()
            assertEquals(true, item.isStreaming)
            assertEquals(false, item.hearingAidConnected)
            
            prefsFlow.value = UserPreferences(hapticFeedbackEnabled = true)
            item = awaitItem()
            assertEquals(true, item.hapticFeedbackEnabled)
            // The isStreaming should remain true as it comes from the service
            assertEquals(true, item.isStreaming)
        }
    }
}
