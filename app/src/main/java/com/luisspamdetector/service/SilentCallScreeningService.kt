package com.luisspamdetector.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.luisspamdetector.util.Logger
import kotlinx.coroutines.*
import org.linphone.core.Call
import org.linphone.core.Player
import org.linphone.core.PlayerListener
import java.io.File
import java.util.*

/**
 * Servicio de screening de llamadas SILENCIOSO (estilo iOS)
 * 
 * Arquitectura:
 * 1. Pre-genera archivos WAV con los prompts usando TTS offline
 * 2. Durante la llamada, usa LinphonePlayer para reproducir los WAV directamente en el stream RTP
 * 3. El usuario NO escucha nada (audio va directo al stream SIP)
 * 4. La llamada se graba y se transcribe después de colgar
 * 5. No hay SpeechRecognizer durante la llamada (solo grabación)
 */
class SilentCallScreeningService(
    private val context: Context
) {
    companion object {
        private const val TAG = "SilentCallScreeningService"
        
        // Nombres de los archivos de audio pregrabados
        private const val AUDIO_GREETING = "screening_greeting.wav"
        private const val AUDIO_ASK_PURPOSE = "screening_purpose.wav"
        private const val AUDIO_GOODBYE = "screening_goodbye.wav"
        private const val AUDIO_WAIT = "screening_wait.wav"
        
        // Textos para los prompts
        private const val TEXT_GREETING = "Hola, ha llamado a un asistente automático. ¿Podría decirme su nombre y el motivo de su llamada, por favor?"
        private const val TEXT_ASK_PURPOSE = "Gracias. ¿Cuál es el motivo de su llamada?"
        private const val TEXT_GOODBYE = "Gracias por su paciencia. Su llamada será atendida en breve."
        private const val TEXT_WAIT = "Un momento por favor."
    }
    
    private var tts: TextToSpeech? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isInitialized = false
    private var audioFilesReady = false
    
    private var currentCall: Call? = null
    private var player: Player? = null
    private var screeningPhase = ScreeningPhase.IDLE
    
    private var screeningCallback: ScreeningCallback? = null
    
    enum class ScreeningPhase {
        IDLE,
        PLAYING_GREETING,
        WAITING_RESPONSE,
        PLAYING_GOODBYE,
        COMPLETED
    }
    
    interface ScreeningCallback {
        fun onScreeningStarted(call: Call, phoneNumber: String)
        fun onScreeningCompleted(phoneNumber: String, recordingPath: String?)
        fun onScreeningFailed(reason: String)
    }
    
    fun initialize(callback: ScreeningCallback) {
        this.screeningCallback = callback
        
        scope.launch {
            try {
                initializeTTS()
                generateAudioFilesIfNeeded()
            } catch (e: Exception) {
                Logger.e(TAG, "Error initializing service", e)
            }
        }
    }
    
    private suspend fun initializeTTS() = suspendCancellableCoroutine { continuation ->
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("es", "ES"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.getDefault())
                }
                tts?.setSpeechRate(0.95f)
                isInitialized = true
                Logger.i(TAG, "TTS initialized for audio file generation")
                
                if (continuation.isActive) {
                    continuation.resumeWith(Result.success(Unit))
                }
            } else {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(Exception("TTS init failed")))
                }
            }
        }
    }
    
    /**
     * Genera los archivos WAV pregrabados si no existen
     */
    private suspend fun generateAudioFilesIfNeeded() {
        val audioDir = getAudioDir()
        if (!audioDir.exists()) {
            audioDir.mkdirs()
        }
        
        val greetingFile = File(audioDir, AUDIO_GREETING)
        val purposeFile = File(audioDir, AUDIO_ASK_PURPOSE)
        val goodbyeFile = File(audioDir, AUDIO_GOODBYE)
        val waitFile = File(audioDir, AUDIO_WAIT)
        
        // Solo regenerar si no existen
        if (!greetingFile.exists()) {
            synthesizeToFile(TEXT_GREETING, greetingFile)
        }
        if (!purposeFile.exists()) {
            synthesizeToFile(TEXT_ASK_PURPOSE, purposeFile)
        }
        if (!goodbyeFile.exists()) {
            synthesizeToFile(TEXT_GOODBYE, goodbyeFile)
        }
        if (!waitFile.exists()) {
            synthesizeToFile(TEXT_WAIT, waitFile)
        }
        
        audioFilesReady = true
        Logger.i(TAG, "Audio files ready at: ${audioDir.absolutePath}")
    }
    
    private suspend fun synthesizeToFile(text: String, outputFile: File) = suspendCancellableCoroutine { continuation ->
        val utteranceId = "synth_${System.currentTimeMillis()}"
        
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            
            override fun onDone(id: String?) {
                if (id == utteranceId && continuation.isActive) {
                    Logger.d(TAG, "Synthesized: ${outputFile.name}")
                    continuation.resumeWith(Result.success(Unit))
                }
            }
            
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == utteranceId && continuation.isActive) {
                    continuation.resumeWith(Result.failure(Exception("TTS synthesis error")))
                }
            }
            
            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId && continuation.isActive) {
                    continuation.resumeWith(Result.failure(Exception("TTS error: $errorCode")))
                }
            }
        })
        
        val result = tts?.synthesizeToFile(text, null, outputFile, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            if (continuation.isActive) {
                continuation.resumeWith(Result.failure(Exception("synthesizeToFile failed")))
            }
        }
    }
    
    private fun getAudioDir(): File {
        return File(context.filesDir, "screening_audio")
    }
    
    /**
     * Inicia el proceso de screening para una llamada
     */
    fun startScreening(call: Call, phoneNumber: String) {
        if (!audioFilesReady) {
            Logger.e(TAG, "Audio files not ready")
            screeningCallback?.onScreeningFailed("Audio files not ready")
            return
        }
        
        currentCall = call
        screeningPhase = ScreeningPhase.PLAYING_GREETING
        
        Logger.i(TAG, "Starting silent screening for: $phoneNumber")
        screeningCallback?.onScreeningStarted(call, phoneNumber)
        
        // Obtener el player de la llamada
        player = call.player
        
        if (player == null) {
            Logger.e(TAG, "Could not get call player")
            screeningCallback?.onScreeningFailed("Could not get call player")
            return
        }
        
        // Reproducir el saludo
        scope.launch {
            delay(1000) // Esperar a que la llamada se estabilice
            playAudioInCall(AUDIO_GREETING) {
                onGreetingComplete()
            }
        }
    }
    
    private fun playAudioInCall(filename: String, onComplete: () -> Unit) {
        val audioFile = File(getAudioDir(), filename)
        if (!audioFile.exists()) {
            Logger.e(TAG, "Audio file not found: ${audioFile.absolutePath}")
            onComplete()
            return
        }
        
        val p = player ?: return
        
        // Configurar listener para saber cuando termina
        val listener = object : PlayerListener {
            override fun onEofReached(player: Player) {
                Logger.d(TAG, "Audio playback finished: $filename")
                player.close()
                scope.launch(Dispatchers.Main) {
                    onComplete()
                }
            }
        }
        
        p.addListener(listener)
        
        // Abrir y reproducir el archivo
        val openResult = p.open(audioFile.absolutePath)
        if (openResult != 0) {
            Logger.e(TAG, "Failed to open audio file: $filename, error: $openResult")
            p.removeListener(listener)
            onComplete()
            return
        }
        
        val startResult = p.start()
        if (startResult != 0) {
            Logger.e(TAG, "Failed to start audio playback: $filename, error: $startResult")
            p.removeListener(listener)
            p.close()
            onComplete()
            return
        }
        
        Logger.d(TAG, "Playing audio in call: $filename")
    }
    
    private fun onGreetingComplete() {
        Logger.d(TAG, "Greeting complete, waiting for caller response")
        screeningPhase = ScreeningPhase.WAITING_RESPONSE
        
        // Esperar un tiempo para que el llamante responda (la grabación captura todo)
        scope.launch {
            delay(8000) // 8 segundos para responder
            
            // Reproducir despedida
            screeningPhase = ScreeningPhase.PLAYING_GOODBYE
            playAudioInCall(AUDIO_GOODBYE) {
                onScreeningComplete()
            }
        }
    }
    
    private fun onScreeningComplete() {
        screeningPhase = ScreeningPhase.COMPLETED
        
        val phoneNumber = currentCall?.remoteAddress?.asStringUriOnly() ?: "unknown"
        val recordingPath = currentCall?.params?.recordFile
        
        Logger.i(TAG, "Screening completed for: $phoneNumber, recording: $recordingPath")
        
        screeningCallback?.onScreeningCompleted(phoneNumber, recordingPath)
        
        cleanup()
    }
    
    fun stopScreening() {
        player?.let {
            if (it.state == Player.State.Playing) {
                it.pause()
            }
            it.close()
        }
        cleanup()
    }
    
    private fun cleanup() {
        currentCall = null
        player = null
        screeningPhase = ScreeningPhase.IDLE
    }
    
    fun shutdown() {
        scope.cancel()
        stopScreening()
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        audioFilesReady = false
    }
    
    /**
     * Regenera los archivos de audio (útil si cambia el idioma)
     */
    fun regenerateAudioFiles() {
        scope.launch {
            // Eliminar archivos existentes
            getAudioDir().listFiles()?.forEach { it.delete() }
            audioFilesReady = false
            generateAudioFilesIfNeeded()
        }
    }
}
