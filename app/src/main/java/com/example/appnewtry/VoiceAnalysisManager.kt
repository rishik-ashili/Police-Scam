package com.example.appnewtry

import android.app.Dialog
import android.content.Context
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Bundle
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.Locale

class VoiceAnalysisManager(private val context: Context) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var currentLanguage = "hi-IN"
    private val riskIndicators = RiskIndicators()
    private var lastAlertTime = 0L
    private val alertCooldown = 10000L // 10 seconds cooldown between alerts
    private val scope = CoroutineScope(Dispatchers.IO)
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var mediaProjection: MediaProjection? = null
    private var currentAlert: Dialog? = null
    private var lastProcessedText = ""
    private var isProcessingSpeech = false
    private val processingCooldown = 500L // 500ms cooldown between processing
    private var lastProcessingTime = 0L
    
    private val _transcriptionFlow = MutableStateFlow("")
    val transcriptionFlow: StateFlow<String> = _transcriptionFlow

    private val _riskScoreFlow = MutableStateFlow(0f)
    val riskScoreFlow: StateFlow<Float> = _riskScoreFlow

    private val _graphDataFlow = MutableStateFlow<List<Entry>>(emptyList())
    val graphDataFlow: StateFlow<List<Entry>> = _graphDataFlow

    private var timeCounter = 0f

    // Audio recording parameters
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    private var lastTranscriptionTime = 0L
    private val transcriptionTimeout = 1500L // 1.5 seconds timeout for new sentence
    private var currentSentence = StringBuilder()

    init {
        setupSpeechRecognizer()
    }

    fun initializeMediaProjection(projection: MediaProjection) {
        mediaProjection = projection
    }

    private fun setupSpeechRecognizer() {
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d("VoiceAnalysis", "Ready for speech")
                        isProcessingSpeech = false
                    }
                    
                    override fun onBeginningOfSpeech() {
                        Log.d("VoiceAnalysis", "Speech started")
                        isProcessingSpeech = true
                    }
                    
                    override fun onRmsChanged(rmsdB: Float) {}
                    
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    
                    override fun onEndOfSpeech() {
                        Log.d("VoiceAnalysis", "Speech ended")
                        isProcessingSpeech = false
                        if (isListening) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                startListening()
                            }, 100) // Small delay before restarting
                        }
                    }

                    override fun onError(error: Int) {
                        Log.e("VoiceAnalysis", "Error in speech recognition: $error")
                        isProcessingSpeech = false
                        if (isListening && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                startListening()
                            }, 100)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        // Ignore partial results to prevent cumulative text
                        Log.d("VoiceAnalysis", "Partial results received but ignored")
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val text = matches[0]
                            if (text.isNotBlank()) {
                                processRecognizedText(text)
                            }
                            if (isListening) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    startListening()
                                }, 100)
                            }
                        }
                        isProcessingSpeech = false
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            Log.d("VoiceAnalysis", "Speech recognizer setup successful")
        } catch (e: Exception) {
            Log.e("VoiceAnalysis", "Error setting up speech recognizer: ${e.message}")
        }
    }

    private fun shouldProcessText(newText: String): Boolean {
        val currentTime = System.currentTimeMillis()
        // Check if the text is different from last processed text and enough time has passed
        if (newText != lastProcessedText && currentTime - lastProcessingTime >= processingCooldown) {
            lastProcessedText = newText
            lastProcessingTime = currentTime
            return true
        }
        return false
    }

    private fun setupAudioRecord() {
        try {
            Log.d("VoiceAnalysis", "Setting up AudioRecord...")
            mediaProjection?.let { projection ->
                Log.d("VoiceAnalysis", "MediaProjection is available")
                
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_ALARM)
                    .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .build()
                Log.d("VoiceAnalysis", "AudioPlaybackCaptureConfiguration built")

                // Log audio format details
                Log.d("VoiceAnalysis", "Audio format - Sample rate: $sampleRate, Channel: $channelConfig, Format: $audioFormat, Buffer size: $bufferSize")

                audioRecord = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build())
                    .setBufferSizeInBytes(bufferSize)
                    .build()

                // Check if AudioRecord was initialized properly
                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    Log.d("VoiceAnalysis", "AudioRecord initialized successfully")
                } else {
                    Log.e("VoiceAnalysis", "AudioRecord initialization failed. State: ${audioRecord?.state}")
                }

            } ?: Log.e("VoiceAnalysis", "MediaProjection is null")
        } catch (e: Exception) {
            Log.e("VoiceAnalysis", "Error setting up AudioRecord: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun startAudioCapture() {
        if (audioRecord == null) {
            Log.d("VoiceAnalysis", "AudioRecord is null, attempting to set up...")
            setupAudioRecord()
        }

        recordingJob = scope.launch {
            try {
                Log.d("VoiceAnalysis", "Starting audio recording...")
                audioRecord?.startRecording()
                
                // Check recording state
                when (audioRecord?.recordingState) {
                    AudioRecord.RECORDSTATE_RECORDING -> Log.d("VoiceAnalysis", "Recording started successfully")
                    AudioRecord.RECORDSTATE_STOPPED -> Log.e("VoiceAnalysis", "Recording failed to start")
                    else -> Log.e("VoiceAnalysis", "Unknown recording state: ${audioRecord?.recordingState}")
                }

                val buffer = ByteBuffer.allocateDirect(bufferSize)
                var totalBytesRead = 0
                var readCount = 0
                
                while (isListening) {
                    val bytesRead = audioRecord?.read(buffer, bufferSize) ?: 0
                    if (bytesRead > 0) {
                        totalBytesRead += bytesRead
                        readCount++
                        if (readCount % 100 == 0) { // Log every 100 reads
                            Log.d("VoiceAnalysis", "Audio capture stats - Total bytes read: $totalBytesRead, Read count: $readCount, Last read: $bytesRead bytes")
                        }
                        processAudioData(buffer, bytesRead)
                        buffer.clear()
                    } else {
                        Log.w("VoiceAnalysis", "No bytes read from AudioRecord. Return value: $bytesRead")
                    }
                }
            } catch (e: Exception) {
                Log.e("VoiceAnalysis", "Error in audio capture: ${e.message}")
                e.printStackTrace()
            } finally {
                Log.d("VoiceAnalysis", "Stopping audio recording...")
                audioRecord?.stop()
            }
        }
    }

    private fun processAudioData(buffer: ByteBuffer, bytesRead: Int) {
        // Convert audio data to text using SpeechRecognizer
        speechRecognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }

    private fun processRecognizedText(newText: String) {
        if (newText.isBlank()) return

        // Update the transcription with the new complete text
        val currentText = _transcriptionFlow.value
        val updatedText = if (currentText.isEmpty()) {
            newText
        } else {
            "$currentText\n$newText"
        }
        _transcriptionFlow.value = updatedText

        // Calculate risk score using the complete text
        val analysis = riskIndicators.analyzeText(updatedText)
        _riskScoreFlow.value = analysis.score

        // Check if risk score is above threshold and show alert if needed
        if (analysis.score >= 55.0f) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastAlertTime > alertCooldown) {
                (context as? FloatingWindowService)?.handleHighVoiceRisk(analysis.score)
                lastAlertTime = currentTime
            }
        }

        // Update graph data
        timeCounter += 1f
        val currentData = _graphDataFlow.value.toMutableList()
        currentData.add(Entry(timeCounter, analysis.score))
        _graphDataFlow.value = currentData
    }

    private fun showAlert(message: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                // Dismiss any existing alert
                currentAlert?.dismiss()
                
                // Create and show new alert
                val dialog = Dialog(context)
                dialog.setContentView(R.layout.alert_dialog)
                
                // Set dialog window attributes
                dialog.window?.apply {
                    setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    setBackgroundDrawableResource(android.R.color.transparent)
                    setGravity(Gravity.CENTER)
                    
                    // Set layout parameters
                    val params = attributes
                    params.width = WindowManager.LayoutParams.WRAP_CONTENT
                    params.height = WindowManager.LayoutParams.WRAP_CONTENT
                    params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    attributes = params
                }

                currentAlert = dialog
                dialog.show()

                // Automatically dismiss after 1 second
                Handler(Looper.getMainLooper()).postDelayed({
                    dialog.dismiss()
                    currentAlert = null
                }, 4000)
            } catch (e: Exception) {
                Log.e("VoiceAnalysis", "Error showing alert: ${e.message}")
            }
        }
    }

    fun startListening() {
        if (isProcessingSpeech) {
            Log.d("VoiceAnalysis", "Already processing speech, skipping new request")
            return
        }

        try {
            isListening = true
            currentSentence.clear() // Clear any previous sentence
            Log.d("VoiceAnalysis", "Starting audio capture and speech recognition...")
            startAudioCapture()
            
            speechRecognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
            })
            Log.d("VoiceAnalysis", "Speech recognition started")
        } catch (e: Exception) {
            Log.e("VoiceAnalysis", "Error starting listening: ${e.message}")
            isProcessingSpeech = false
            e.printStackTrace()
        }
    }

    fun stopListening() {
        try {
            isListening = false
            Log.d("VoiceAnalysis", "Stopping all recording...")
            
            // Save the last sentence if any
            if (currentSentence.isNotEmpty()) {
                val currentText = _transcriptionFlow.value
                val finalText = if (currentText.isEmpty()) {
                    currentSentence.toString().trim()
                } else {
                    "$currentText\n${currentSentence.toString().trim()}"
                }
                _transcriptionFlow.value = finalText
                currentSentence.clear()
            }
            
            recordingJob?.cancel()
            audioRecord?.stop()
            speechRecognizer?.stopListening()
            Log.d("VoiceAnalysis", "All recording stopped")
        } catch (e: Exception) {
            Log.e("VoiceAnalysis", "Error stopping listening: ${e.message}")
            e.printStackTrace()
        }
    }

    fun createLineDataSet(entries: List<Entry>): LineDataSet {
        return LineDataSet(entries, "Risk Score").apply {
            color = android.graphics.Color.RED
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            lineWidth = 2f
        }
    }

    private fun containsHindi(text: String): Boolean {
        return text.any { it in '\u0900'..'\u097F' }
    }

    fun destroy() {
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        audioRecord?.release()
        audioRecord = null
        mediaProjection = null
    }
} 