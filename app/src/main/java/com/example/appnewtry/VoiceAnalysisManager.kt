package com.example.appnewtry

import android.content.Context
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import android.content.Intent
import android.util.Log
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class VoiceAnalysisManager(private val context: Context) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var currentLanguage = "hi-IN"
    private val riskIndicators = RiskIndicators()
    private var lastToastTime = 0L
    private val toastCooldown = 10000L // 10 seconds cooldown between toasts
    
    private val _transcriptionFlow = MutableStateFlow("")
    val transcriptionFlow: StateFlow<String> = _transcriptionFlow

    private val _riskScoreFlow = MutableStateFlow(0f)
    val riskScoreFlow: StateFlow<Float> = _riskScoreFlow

    private val _graphDataFlow = MutableStateFlow<List<Entry>>(emptyList())
    val graphDataFlow: StateFlow<List<Entry>> = _graphDataFlow

    private var timeCounter = 0f

    init {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            setupSpeechRecognizer()
        } else {
            Log.e("VoiceAnalysis", "Speech recognition not available")
        }
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    if (isListening) startListening()
                }

                override fun onError(error: Int) {
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> if (isListening) startListening()
                        else -> Log.e("VoiceAnalysis", "Error code: $error")
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        processRecognizedText(text)
                        
                        // Update language for next recognition
                        currentLanguage = if (containsHindi(text)) "hi-IN" else "en-IN"
                        if (isListening) startListening()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun processRecognizedText(newText: String) {
        val currentText = _transcriptionFlow.value
        val updatedText = if (currentText.isEmpty()) newText else "$currentText\n$newText"
        _transcriptionFlow.value = updatedText

        // Calculate risk score
        val analysis = riskIndicators.analyzeText(updatedText)
        _riskScoreFlow.value = analysis.score

        // Check if risk score is above threshold and show toast if needed
        if (analysis.score >= 55.0f) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastToastTime > toastCooldown) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        context,
                        "Alert: Risk level at ${String.format("%.1f", analysis.score)}%. Call has been reported.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                lastToastTime = currentTime
            }
        }

        // Update graph data
        timeCounter += 1f
        val currentData = _graphDataFlow.value.toMutableList()
        currentData.add(Entry(timeCounter, analysis.score))
        _graphDataFlow.value = currentData
    }

    fun startListening() {
        speechRecognizer?.let {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            it.startListening(intent)
            isListening = true
        }
    }

    fun stopListening() {
        isListening = false
        speechRecognizer?.stopListening()
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
    }
} 