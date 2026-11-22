package com.privateai.jarvis.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
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
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class VoiceAssistantManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var wakeRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

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

    private val _voiceAmplitude = MutableStateFlow(0f)
    val voiceAmplitude: StateFlow<Float> = _voiceAmplitude.asStateFlow()

    @Volatile
    var isBusyProcessing: Boolean = false

    private var wakeCallback: ((String?) -> Unit)? = null
    private var focusRequest: AudioFocusRequest? = null

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    configureBritishButlerVoice()
                }
            }
        } catch (_: Throwable) {
            ttsReady = false
        }
    }

    private fun configureBritishButlerVoice() {
        val engine = tts ?: return
        val res = engine.setLanguage(Locale.UK)
        ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED

        try {
            val ukVoices = engine.voices?.filter {
                it.locale.language.equals("en", ignoreCase = true) &&
                    it.locale.country.equals("GB", ignoreCase = true) &&
                    !it.isNetworkConnectionRequired
            }.orEmpty()

            val britishMale = ukVoices.firstOrNull { v ->
                val n = v.name.lowercase(Locale.ROOT)
                n.contains("gbb") || n.contains("gbd") || n.contains("male") || n.contains("daniel") || n.contains("arthur")
            } ?: ukVoices.firstOrNull()

            if (britishMale != null) {
                engine.voice = britishMale
            }
        } catch (_: Throwable) {
        }

        engine.setPitch(0.91f)
        engine.setSpeechRate(1.03f)
    }

    /**
     * Uses transient exclusive audio focus (never touches system ringer/notification volumes,
     * so HyperOS Silent Mode is never toggled).
     */
    private fun acquireQuietFocus() {
        // No-op: HyperOS 3 triggers Silent/DND mode whenever AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE is requested.
    }

    private fun releaseQuietFocus() {
        try {
            focusRequest?.let { req ->
                audioManager.abandonAudioFocusRequest(req)
            }
        } catch (_: Throwable) {
        } finally {
            focusRequest = null
        }
    }

    fun playJarvisAckChime() {
        Thread {
            try {
                val sampleRate = 22050
                val durationSec = 0.16
                val numSamples = (sampleRate * durationSec).toInt()
                val samples = ShortArray(numSamples)
                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    val env = exp(-t * 18.0) * (1.0 - exp(-t * 160.0))
                    val wave = 0.6 * sin(2.0 * PI * 587.33 * t) + 0.4 * sin(2.0 * PI * 880.0 * t)
                    samples[i] = (wave * env * 14000.0).toInt().coerceIn(-32767, 32767).toShort()
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(samples, 0, samples.size)
                track.play()
                Thread.sleep(200L)
                track.release()
            } catch (_: Throwable) {
            }
        }.start()
    }

    fun startWakeWordDetection(onWakeTriggered: (String?) -> Unit) {
        wakeCallback = onWakeTriggered
        _isWakeWordActive.value = true
        scheduleWakeCycle(600L)
    }

    fun stopWakeWordDetection() {
        _isWakeWordActive.value = false
        wakeCallback = null
        mainHandler.removeCallbacksAndMessages(null)
        destroyWakeRecognizer()
        releaseQuietFocus()
    }

    private fun destroyWakeRecognizer() {
        try {
            wakeRecognizer?.stopListening()
        } catch (_: Throwable) {
        }
        try {
            wakeRecognizer?.destroy()
        } catch (_: Throwable) {
        }
        wakeRecognizer = null
    }

    private fun scheduleWakeCycle(delayMs: Long) {
        if (!_isWakeWordActive.value || _isListening.value) return
        mainHandler.postDelayed({
            if (!_isWakeWordActive.value || _isListening.value) return@postDelayed
            if (isBusyProcessing || audioManager.isMusicActive || _isSpeaking.value) {
                scheduleWakeCycle(1500L)
                return@postDelayed
            }
            runSingleWakePass()
        }, delayMs)
    }

    private fun runSingleWakePass() {
        destroyWakeRecognizer()
        val recognizer = createSafeRecognizer()
        if (recognizer == null) {
            _isWakeWordActive.value = false
            return
        }
        wakeRecognizer = recognizer

        try {
            acquireQuietFocus()
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    // Map typical speech RMS (-2 dB to 10 dB) into 0f..1f for the reactive orb
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    _voiceAmplitude.value = normalized
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    releaseQuietFocus()
                    destroyWakeRecognizer()
                    scheduleWakeCycle(1400L)
                }

                override fun onResults(results: Bundle?) {
                    releaseQuietFocus()
                    val heard = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    destroyWakeRecognizer()
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
                        releaseQuietFocus()
                        destroyWakeRecognizer()
                        handleWakeTranscript(partial)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            recognizer.startListening(buildRecognizerIntent(silentPrompt = true))
        } catch (_: Throwable) {
            releaseQuietFocus()
            destroyWakeRecognizer()
            scheduleWakeCycle(1600L)
        }
    }

    private fun handleWakeTranscript(transcript: String) {
        val lower = transcript.lowercase(Locale.ROOT)
        val wakeRegex = Regex("^(?:hey\\s+|ok\\s+)?jarvis[,\\s]*(.*)$", RegexOption.IGNORE_CASE)
        val match = wakeRegex.matchEntire(lower)
        if (match != null) {
            playJarvisAckChime()
            val trailingCommand = transcript
                .replace(Regex("^(?:hey\\s+|ok\\s+)?jarvis[,\\s]*", RegexOption.IGNORE_CASE), "")
                .trim()
            if (trailingCommand.isNotEmpty()) {
                wakeCallback?.invoke(trailingCommand)
                scheduleWakeCycle(2000L)
            } else {
                speak("At your service.")
                mainHandler.postDelayed({
                    wakeCallback?.invoke(null)
                }, 800L)
            }
        } else {
            scheduleWakeCycle(1100L)
        }
    }

    fun startListening(
        onFinalResult: (String) -> Unit,
        onFallbackToSystemDialog: () -> Unit
    ) {
        _voiceError.value = null
        stopSpeaking()
        stopListening()
        destroyWakeRecognizer()

        val recognizer = createSafeRecognizer()
        if (recognizer == null) {
            onFallbackToSystemDialog()
            return
        }

        speechRecognizer = recognizer

        try {
            acquireQuietFocus()
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                    _partialText.value = ""
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    // Map typical speech RMS (-2 dB to 10 dB) into 0f..1f for the reactive orb
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    _voiceAmplitude.value = normalized
                }
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    _isListening.value = false
                }

                override fun onError(error: Int) {
                    releaseQuietFocus()
                    _isListening.value = false
                    _voiceAmplitude.value = 0f
                    _partialText.value = ""
                    when (error) {
                        SpeechRecognizer.ERROR_CLIENT,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        SpeechRecognizer.ERROR_NO_MATCH -> {
                            _voiceError.value = "Didn't catch that. Tap mic or say \"Jarvis\"."
                            if (_isWakeWordActive.value) scheduleWakeCycle(1200L)
                        }
                        else -> {
                            stopListening()
                            onFallbackToSystemDialog()
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    releaseQuietFocus()
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
                    if (_isWakeWordActive.value) scheduleWakeCycle(1600L)
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
            releaseQuietFocus()
            stopListening()
            onFallbackToSystemDialog()
        }
    }

    fun buildRecognizerIntent(silentPrompt: Boolean = false): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
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
        _voiceAmplitude.value = 0f
        releaseQuietFocus()
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
            val estMs = (clean.length * 55L).coerceIn(1200L, 12000L)
            mainHandler.postDelayed({ _isSpeaking.value = false }, estMs)
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
