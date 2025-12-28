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
    private var currentPhoneNumber: String? = null
    private var player: Player? = null
    private var screeningPhase = ScreeningPhase.IDLE
    private var callMonitoringJob: Job? = null
    
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
        currentPhoneNumber = phoneNumber
        screeningPhase = ScreeningPhase.PLAYING_GREETING
        
        Logger.i(TAG, "Starting silent screening for: $phoneNumber")
        screeningCallback?.onScreeningStarted(call, phoneNumber)
        
        // Iniciar monitoreo del estado de la llamada
        startCallStateMonitoring(call)
        
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
    
    /**
     * Monitorea el estado de la llamada para detectar cuando el remoto cuelga
     */
    private fun startCallStateMonitoring(call: Call) {
        callMonitoringJob?.cancel()
        callMonitoringJob = scope.launch {
            while (isActive) {
                val state = call.state
                if (state == Call.State.End || state == Call.State.Released || state == Call.State.Error) {
                    Logger.i(TAG, "Llamada remota terminada durante screening: $state")
                    handleRemoteHangup()
                    break
                }
                delay(500) // Verificar cada 500ms
            }
        }
    }
    
    /**
     * Maneja cuando el remoto cuelga la llamada
     */
    private fun handleRemoteHangup() {
        Logger.i(TAG, "El interlocutor colgó la llamada")
        
        // Detener cualquier reproducción en curso
        player?.let {
            if (it.state == Player.State.Playing) {
                it.pause()
            }
            it.close()
        }
        
        // Detener grabación si estaba activa
        currentCall?.let { call ->
            try {
                call.stopRecording()
                Logger.i(TAG, "Grabación detenida por cuelgue remoto")
            } catch (e: Exception) {
                Logger.w(TAG, "Error deteniendo grabación", e)
            }
        }
        
        val phoneNumber = currentPhoneNumber ?: "unknown"
        val recordingPath = currentCall?.params?.recordFile
        
        // Notificar que el screening terminó (por cuelgue remoto)
        screeningCallback?.onScreeningCompleted(phoneNumber, recordingPath)
        
        cleanup()
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
        Logger.d(TAG, "Greeting complete, starting recording for caller response ONLY")
        screeningPhase = ScreeningPhase.WAITING_RESPONSE
        
        // Obtener duración configurada desde preferencias
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val screeningDurationSeconds = prefs.getInt("screening_duration_seconds", 8)
        val screeningDurationMs = screeningDurationSeconds * 1000L
        
        Logger.i(TAG, "Duración del screening configurada: ${screeningDurationSeconds}s")
        
        // IMPORTANTE: Mutear el micrófono antes de grabar
        // Así solo se graba el audio REMOTO (la voz del interlocutor)
        currentCall?.let { call ->
            try {
                // Mutear micrófono para que NO se grabe nuestro audio
                call.microphoneMuted = true
                Logger.i(TAG, "Micrófono muteado para grabar solo audio remoto")
                
                // Iniciar grabación - solo capturará el audio del interlocutor
                call.startRecording()
                Logger.i(TAG, "✓ Grabación iniciada (solo audio remoto/interlocutor)")
                Logger.i(TAG, "  - recordFile: ${call.params?.recordFile}")
            } catch (e: Exception) {
                Logger.e(TAG, "Error iniciando grabación", e)
            }
        }
        
        // Esperar el tiempo configurado para que el llamante responda
        scope.launch {
            delay(screeningDurationMs)
            
            // Detener grabación y desmutear antes de reproducir despedida
            currentCall?.let { call ->
                try {
                    call.stopRecording()
                    Logger.i(TAG, "Grabación detenida")
                    
                    // Desmutear para poder reproducir la despedida
                    call.microphoneMuted = false
                    Logger.i(TAG, "Micrófono desmuteado para despedida")
                } catch (e: Exception) {
                    Logger.w(TAG, "Error deteniendo grabación", e)
                }
            }
            
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
        
        // Terminar la llamada automáticamente (estilo iOS)
        currentCall?.terminate()
        Logger.i(TAG, "Call terminated automatically after screening")
        
        screeningCallback?.onScreeningCompleted(phoneNumber, recordingPath)
        
        cleanup()
    }
    
    /**
     * Rechaza/termina la llamada actual
     */
    fun rejectCall() {
        currentCall?.let { call ->
            Logger.i(TAG, "Rechazando llamada silenciosa: ${call.state}")
            if (call.state != Call.State.End && call.state != Call.State.Released) {
                call.terminate()
                Logger.i(TAG, "Llamada silenciosa terminada")
            }
        }
        stopScreening()
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
        callMonitoringJob?.cancel()
        callMonitoringJob = null
        currentCall = null
        currentPhoneNumber = null
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
