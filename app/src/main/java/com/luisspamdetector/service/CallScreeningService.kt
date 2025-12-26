package com.luisspamdetector.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.luisspamdetector.api.GeminiApiService
import kotlinx.coroutines.*
import org.linphone.core.Call
import java.util.*

/**
 * Servicio que maneja el screening automático de llamadas:
 * 1. Auto-contesta llamadas desconocidas
 * 2. Usa TTS para preguntar quién es y qué quiere
 * 3. Usa STT para capturar la respuesta
 * 4. Envía la información a una actividad overlay para que el usuario decida
 * 
 * Actualizado para Linphone SDK 5.4.x y Android 15
 */
class CallScreeningService(
    private val context: Context,
    private val geminiService: GeminiApiService
) {
    companion object {
        private const val TAG = "CallScreeningService"
        private const val LISTEN_TIMEOUT_MS = 10000L
        private const val TTS_INIT_TIMEOUT_MS = 5000L
    }

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var isInitialized = false
    private var currentCall: Call? = null
    private var conversationState = ConversationState.IDLE
    private var callerName: String? = null
    private var callerPurpose: String? = null
    private var listeningJob: Job? = null

    enum class ConversationState {
        IDLE,
        GREETING,
        WAITING_NAME,
        ASKING_PURPOSE,
        WAITING_PURPOSE,
        COMPLETED,
        ERROR
    }

    interface ScreeningCallback {
        fun onScreeningCompleted(name: String?, purpose: String?, phoneNumber: String)
        fun onScreeningFailed(reason: String)
    }

    private var screeningCallback: ScreeningCallback? = null

    fun initialize(callback: ScreeningCallback) {
        this.screeningCallback = callback

        scope.launch {
            try {
                initializeTTS()
                initializeSpeechRecognizer()
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing screening service", e)
                screeningCallback?.onScreeningFailed("Error de inicialización: ${e.message}")
            }
        }
    }

    private suspend fun initializeTTS() = suspendCancellableCoroutine { continuation ->
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("es", "ES"))
                
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Idioma español no disponible, usando predeterminado")
                    tts?.setLanguage(Locale.getDefault())
                }

                tts?.setSpeechRate(0.9f)
                tts?.setPitch(1.0f)

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS started: $utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "TTS done: $utteranceId")
                        handleTTSCompleted(utteranceId)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS error: $utteranceId")
                        handleTTSError(utteranceId)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.e(TAG, "TTS error: $utteranceId, code: $errorCode")
                        handleTTSError(utteranceId)
                    }
                })

                isInitialized = true
                Log.d(TAG, "TTS initialized successfully")
                
                if (continuation.isActive) {
                    continuation.resumeWith(Result.success(Unit))
                }
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(
                        Exception("TTS initialization failed")
                    ))
                }
            }
        }

        // Timeout para inicialización
        scope.launch {
            delay(TTS_INIT_TIMEOUT_MS)
            if (continuation.isActive) {
                continuation.resumeWith(Result.failure(
                    Exception("TTS initialization timeout")
                ))
            }
        }
    }

    private fun initializeSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available")
            screeningCallback?.onScreeningFailed("Reconocimiento de voz no disponible")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(createRecognitionListener())
        Log.d(TAG, "SpeechRecognizer initialized")
    }

    /**
     * Inicia el proceso de screening para una llamada
     */
    fun startScreening(call: Call, phoneNumber: String) {
        if (!isInitialized) {
            Log.e(TAG, "Service not initialized")
            screeningCallback?.onScreeningFailed("Servicio no inicializado")
            return
        }

        // Reiniciar estado
        callerName = null
        callerPurpose = null
        currentCall = call
        conversationState = ConversationState.GREETING

        Log.d(TAG, "Starting screening for: $phoneNumber")

        // Esperar a que la llamada se establezca
        scope.launch {
            delay(1500)
            
            // Verificar que la llamada sigue activa
            if (call.state == Call.State.StreamsRunning || 
                call.state == Call.State.Connected) {
                greetCaller()
            } else {
                Log.w(TAG, "Call no longer active, state: ${call.state}")
                conversationState = ConversationState.ERROR
            }
        }
    }

    private fun greetCaller() {
        conversationState = ConversationState.GREETING
        speak(
            "Hola, ha llamado a un asistente automático. ¿Podría decirme su nombre, por favor?",
            "greeting"
        )
    }

    private fun askPurpose() {
        conversationState = ConversationState.ASKING_PURPOSE
        val nameResponse = if (callerName.isNullOrBlank() || callerName == "No respondió") {
            ""
        } else {
            "Gracias, $callerName. "
        }
        speak(
            "${nameResponse}¿Cuál es el motivo de su llamada?",
            "asking_purpose"
        )
    }

    private fun speak(text: String, utteranceId: String) {
        Log.d(TAG, "Speaking: $text")
        
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_VOICE_CALL)
        }
        
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    private fun handleTTSCompleted(utteranceId: String?) {
        scope.launch {
            delay(300) // Pequeña pausa antes de escuchar

            when (utteranceId) {
                "greeting" -> {
                    conversationState = ConversationState.WAITING_NAME
                    startListeningWithTimeout()
                }
                "asking_purpose" -> {
                    conversationState = ConversationState.WAITING_PURPOSE
                    startListeningWithTimeout()
                }
            }
        }
    }

    private fun handleTTSError(utteranceId: String?) {
        Log.e(TAG, "TTS error for: $utteranceId")
        // Intentar continuar de todos modos
        handleTTSCompleted(utteranceId)
    }

    private fun startListeningWithTimeout() {
        listeningJob?.cancel()
        
        startListening()
        
        // Timeout para escuchar
        listeningJob = scope.launch {
            delay(LISTEN_TIMEOUT_MS)
            Log.d(TAG, "Listening timeout reached")
            speechRecognizer?.stopListening()
            handleNoResponse()
        }
    }

    private fun startListening() {
        Log.d(TAG, "Starting to listen...")

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL, 
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 
                2500L
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 
                2000L
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 
                1000L
            )
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition", e)
            handleNoResponse()
        }
    }

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Beginning of speech")
            // Cancelar timeout ya que el usuario está hablando
            listeningJob?.cancel()
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Nivel de audio - no necesitamos hacer nada
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // Buffer de audio recibido
        }

        override fun onEndOfSpeech() {
            Log.d(TAG, "End of speech")
        }

        override fun onError(error: Int) {
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                else -> "Unknown error: $error"
            }
            Log.e(TAG, "Speech recognition error: $errorMessage")
            
            listeningJob?.cancel()

            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    handleNoResponse()
                }
                else -> {
                    // Para otros errores, intentar continuar
                    handleNoResponse()
                }
            }
        }

        override fun onResults(results: Bundle?) {
            listeningJob?.cancel()
            
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)

            if (!matches.isNullOrEmpty()) {
                val recognized = matches[0]
                Log.d(TAG, "Recognized: $recognized")
                handleRecognizedSpeech(recognized)
            } else {
                handleNoResponse()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                Log.d(TAG, "Partial: ${matches[0]}")
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            Log.d(TAG, "Recognition event: $eventType")
        }
    }

    private fun handleRecognizedSpeech(text: String) {
        when (conversationState) {
            ConversationState.WAITING_NAME -> {
                callerName = text
                Log.d(TAG, "Caller name captured: $callerName")

                scope.launch {
                    try {
                        callerName = enhanceNameWithGemini(text)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error enhancing name", e)
                    }
                    askPurpose()
                }
            }
            ConversationState.WAITING_PURPOSE -> {
                callerPurpose = text
                Log.d(TAG, "Caller purpose captured: $callerPurpose")

                scope.launch {
                    try {
                        callerPurpose = enhancePurposeWithGemini(text)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error enhancing purpose", e)
                    }
                    finishScreening()
                }
            }
            else -> {
                Log.w(TAG, "Unexpected conversation state: $conversationState")
            }
        }
    }

    private fun handleNoResponse() {
        Log.d(TAG, "No response detected in state: $conversationState")

        when (conversationState) {
            ConversationState.WAITING_NAME -> {
                callerName = "No respondió"
                askPurpose()
            }
            ConversationState.WAITING_PURPOSE -> {
                callerPurpose = "No especificado"
                finishScreening()
            }
            else -> {
                finishScreening()
            }
        }
    }

    private suspend fun enhanceNameWithGemini(rawName: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val prompt = """
                    El siguiente texto fue capturado por reconocimiento de voz cuando se preguntó el nombre de una persona.
                    Extrae y formatea correctamente el nombre. Si no parece un nombre válido, devuelve "Desconocido".

                    Texto capturado: "$rawName"

                    Responde SOLO con el nombre formateado, sin explicaciones adicionales.
                """.trimIndent()

                val result = geminiService.askGemini(prompt)
                result.ifBlank { rawName }.take(50) // Limitar longitud
            } catch (e: Exception) {
                Log.e(TAG, "Error in enhanceNameWithGemini", e)
                rawName
            }
        }
    }

    private suspend fun enhancePurposeWithGemini(rawPurpose: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val prompt = """
                    El siguiente texto fue capturado por reconocimiento de voz cuando se preguntó el motivo de una llamada telefónica.
                    Resume el motivo de forma clara y concisa en máximo 2 frases cortas. Si no se entiende, di "No especificado".

                    Texto capturado: "$rawPurpose"

                    Responde SOLO con el resumen del motivo, sin explicaciones adicionales.
                """.trimIndent()

                val result = geminiService.askGemini(prompt)
                result.ifBlank { rawPurpose }.take(200) // Limitar longitud
            } catch (e: Exception) {
                Log.e(TAG, "Error in enhancePurposeWithGemini", e)
                rawPurpose
            }
        }
    }

    private fun finishScreening() {
        conversationState = ConversationState.COMPLETED

        val phoneNumber = currentCall?.remoteAddress?.asStringUriOnly() ?: "Desconocido"

        Log.d(TAG, "Screening completed - Name: $callerName, Purpose: $callerPurpose")

        screeningCallback?.onScreeningCompleted(
            callerName ?: "Desconocido",
            callerPurpose ?: "No especificado",
            phoneNumber
        )
    }

    /**
     * Acepta la llamada (reanuda si está en pausa)
     */
    fun acceptCall() {
        currentCall?.let { call ->
            when (call.state) {
                Call.State.Paused,
                Call.State.PausedByRemote -> {
                    call.resume()
                }
                else -> {
                    Log.d(TAG, "Call in state ${call.state}, no action needed")
                }
            }
        }
        cleanup()
    }

    /**
     * Rechaza/termina la llamada
     */
    fun rejectCall() {
        currentCall?.terminate()
        cleanup()
    }

    private fun cleanup() {
        listeningJob?.cancel()
        callerName = null
        callerPurpose = null
        currentCall = null
        conversationState = ConversationState.IDLE
    }

    fun shutdown() {
        Log.d(TAG, "Shutting down CallScreeningService")
        
        scope.cancel()
        
        listeningJob?.cancel()
        
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying speech recognizer", e)
        }
        
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TTS", e)
        }
        
        speechRecognizer = null
        tts = null
        isInitialized = false
        
        cleanup()
    }
}
