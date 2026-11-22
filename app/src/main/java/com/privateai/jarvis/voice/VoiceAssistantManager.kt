package com.privateai.jarvis.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var wakeRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isWakeWordActive = MutableStateFlow(false)
    val isWakeWordActive: StateFlow<Boolean> = _isWakeWordActive.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _voiceError = MutableStateFlow<String?>(null)
    val voiceError: StateFlow<String?> = _voiceError.asStateFlow()

    private var wakeCallback: ((String?) -> Unit)? = null

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
        } catch (_: Throwable) {
            ttsReady = false
        }
    }

    /**
     * Continuous wake-word loop listening for "Jarvis".
     * If user says "Jarvis open YouTube", passes "open YouTube" immediately.
     * If user says only "Jarvis", passes null so UI triggers active command capture.
     */
    fun startWakeWordDetection(onWakeTriggered: (String?) -> Unit) {
        wakeCallback = onWakeTriggered
        _isWakeWordActive.value = true
        scheduleWakeCycle(250L)
    }

    fun stopWakeWordDetection() {
        _isWakeWordActive.value = false
        wakeCallback = null
        mainHandler.removeCallbacksAndMessages(null)
        try {
            wakeRecognizer?.stopListening()
            wakeRecognizer?.destroy()
        } catch (_: Throwable) {
        }
        wakeRecognizer = null
    }

    private fun scheduleWakeCycle(delayMs: Long) {
        if (!_isWakeWordActive.value || _isListening.value) return
        mainHandler.postDelayed({
            if (!_isWakeWordActive.value || _isListening.value) return@postDelayed
            runSingleWakePass()
        }, delayMs)
    }

    private fun runSingleWakePass() {
        try {
            wakeRecognizer?.destroy()
        } catch (_: Throwable) {
        }
        val recognizer = createSafeRecognizer()
        if (recognizer == null) {
            _isWakeWordActive.value = false
            _voiceError.value = "Wake-word unavailable on this speech engine."
            return
        }
        wakeRecognizer = recognizer

        try {
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    scheduleWakeCycle(700L)
                }

                override fun onResults(results: Bundle?) {
                    val heard = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    handleWakeTranscript(heard)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (partial.equals("jarvis", ignoreCase = true) ||
                        partial.equals("hey jarvis", ignoreCase = true)
                    ) {
                        try {
                            recognizer.stopListening()
                        } catch (_: Throwable) {
                        }
                        handleWakeTranscript(partial)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            recognizer.startListening(buildRecognizerIntent(silentPrompt = true))
        } catch (_: Throwable) {
            scheduleWakeCycle(1200L)
        }
    }

    private fun handleWakeTranscript(transcript: String) {
        val lower = transcript.lowercase(Locale.ROOT)
        val wakeRegex = Regex("^(?:hey\\s+|ok\\s+)?jarvis[,\\s]*(.*)$", RegexOption.IGNORE_CASE)
        val match = wakeRegex.matchEntire(lower)
        if (match != null) {
            val trailingCommand = transcript
                .replace(Regex("^(?:hey\\s+|ok\\s+)?jarvis[,\\s]*", RegexOption.IGNORE_CASE), "")
                .trim()
            if (trailingCommand.isNotEmpty()) {
                wakeCallback?.invoke(trailingCommand)
                scheduleWakeCycle(1500L)
            } else {
                speak("Yes?")
                wakeCallback?.invoke(null)
            }
        } else {
            scheduleWakeCycle(400L)
        }
    }

    fun startListening(
        onFinalResult: (String) -> Unit,
        onFallbackToSystemDialog: () -> Unit
    ) {
        _voiceError.value = null
        stopSpeaking()
        stopListening()
        try {
            wakeRecognizer?.stopListening()
        } catch (_: Throwable) {
        }

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
                            if (_isWakeWordActive.value) scheduleWakeCycle(800L)
                        }
                        else -> {
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
                    if (_isWakeWordActive.value) scheduleWakeCycle(1200L)
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

            recognizer.startListening(buildRecognizerIntent(silentPrompt = false))
        } catch (_: Throwable) {
            stopListening()
            onFallbackToSystemDialog()
        }
    }

    fun buildRecognizerIntent(silentPrompt: Boolean = false): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (!silentPrompt) {
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to J.A.R.V.I.S.")
            }
        }
    }

    private fun createSafeRecognizer(): SpeechRecognizer? {
        try {
            val googleComponent = ComponentName(
                "com.google.android.tts",
                "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"
            )
            if (context.packageManager.getServiceInfo(googleComponent, 0) != null) {
                return SpeechRecognizer.createSpeechRecognizer(context, googleComponent)
            }
        } catch (_: Throwable) {
        }

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
            .replace(Regex("[*#`>⚠️🛡️⏰📝📅✉️📴📂]"), "")
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
        stopWakeWordDetection()
        stopListening()
        stopSpeaking()
        try {
            tts?.shutdown()
        } catch (_: Throwable) {
        }
        tts = null
    }
}
