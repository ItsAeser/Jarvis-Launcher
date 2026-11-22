package com.privateai.jarvis.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class VoiceAssistantManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _voiceError = MutableStateFlow<String?>(null)
    val voiceError: StateFlow<String?> = _voiceError.asStateFlow()

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val res = tts?.setLanguage(Locale.getDefault())
                    ttsReady = res != TextToSpeech.LANG_MISSING_DATA &&
                        res != TextToSpeech.LANG_NOT_SUPPORTED
                    tts?.setSpeechRate(1.04f)
                }
            }
        } catch (_: Exception) {
            ttsReady = false
        }
    }

    /**
     * Tries explicit Google Speech Services first (avoids HyperOS / Android System Intelligence
     * binding crashes on Android 16), then falls back to default SpeechRecognizer, and finally
     * signals false if inline recognition cannot bind so the UI can launch system voice dialog.
     */
    fun startListening(
        onFinalResult: (String) -> Unit,
        onFallbackToSystemDialog: () -> Unit
    ) {
        _voiceError.value = null
        stopSpeaking()
        stopListening()

        val recognizer = createSafeRecognizer()
        if (recognizer == null) {
            onFallbackToSystemDialog()
            return
        }

        speechRecognizer = recognizer

        try {
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                    _partialText.value = ""
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    _isListening.value = false
                }

                override fun onError(error: Int) {
                    _isListening.value = false
                    _partialText.value = ""
                    when (error) {
                        SpeechRecognizer.ERROR_CLIENT,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        SpeechRecognizer.ERROR_NO_MATCH -> {
                            _voiceError.value = "Didn't catch that. Tap mic to try again."
                        }
                        else -> {
                            // Service binding / HyperOS restriction error -> open standard voice dialog
                            stopListening()
                            onFallbackToSystemDialog()
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    _isListening.value = false
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    _partialText.value = ""
                    if (text.isNotEmpty()) {
                        onFinalResult(text)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (partial.isNotEmpty()) {
                        _partialText.value = partial
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = buildRecognizerIntent()
            recognizer.startListening(intent)
        } catch (_: Throwable) {
            stopListening()
            onFallbackToSystemDialog()
        }
    }

    fun buildRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to J.A.R.V.I.S.")
        }
    }

    private fun createSafeRecognizer(): SpeechRecognizer? {
        // 1. Prefer Google Speech Services directly (prevents HyperOS / AiAiSpeechRecognition crash)
        try {
            val googleComponent = ComponentName(
                "com.google.android.tts",
                "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"
            )
            val pm = context.packageManager
            if (pm.getServiceInfo(googleComponent, 0) != null) {
                return SpeechRecognizer.createSpeechRecognizer(context, googleComponent)
            }
        } catch (_: Throwable) {
        }

        // 2. Try standard system recognizer if available
        return try {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                SpeechRecognizer.createSpeechRecognizer(context)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (_: Throwable) {
        }
        try {
            speechRecognizer?.destroy()
        } catch (_: Throwable) {
        }
        speechRecognizer = null
        _isListening.value = false
    }

    fun speak(text: String) {
        if (!ttsReady || text.isBlank()) return
        val clean = text
            .replace(Regex("[*#`>⚠️🛡️⏰📝📅✉️]"), "")
            .trim()
        if (clean.isEmpty()) return

        try {
            _isSpeaking.value = true
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "jarvis_utterance")
        } catch (_: Throwable) {
            _isSpeaking.value = false
        }
    }

    fun stopSpeaking() {
        try {
            tts?.stop()
        } catch (_: Throwable) {
        }
        _isSpeaking.value = false
    }

    fun release() {
        stopListening()
        stopSpeaking()
        try {
            tts?.shutdown()
        } catch (_: Throwable) {
        }
        tts = null
    }
}
