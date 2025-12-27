package com.luisspamdetector.call

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import com.luisspamdetector.util.Logger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Implementación genérica de CallManager que funciona sin una llamada específica.
 * Útil para aplicaciones que solo necesitan grabar llamadas sin control directo.
 * 
 * LIMITACIONES:
 * - No puede descolgar/colgar programáticamente (requiere acción del usuario)
 * - Solo puede grabar cuando hay una llamada activa
 * - Depende del estado del sistema de telefonía
 */
class GenericCallManager(
    private val context: Context
) : CallManager {
    
    companion object {
        private const val TAG = "GenericCallManager"
    }
    
    private val callRecorder = CallRecorder(context)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val telecomManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
    } else {
        null
    }
    
    private val listeners = CopyOnWriteArrayList<CallManager.CallListener>()
    
    private var callStartTime: Long = 0
    private var currentState = CallManager.CallState.IDLE
    private var currentPhoneNumber: String? = null
    
    override fun getCurrentCall(): CallManager.CallInfo? {
        if (currentState == CallManager.CallState.IDLE) {
            return null
        }
        
        return CallManager.CallInfo(
            id = System.currentTimeMillis().toString(),
            phoneNumber = currentPhoneNumber ?: "unknown",
            displayName = null,
            isIncoming = true,
            state = currentState,
            startTime = callStartTime,
            recordingPath = callRecorder.getCurrentFilePath()
        )
    }
    
    override fun answerCall(): Boolean {
        Logger.w(TAG, "GenericCallManager no puede descolgar llamadas automáticamente")
        Logger.w(TAG, "El usuario debe descolgar manualmente desde la interfaz del sistema")
        return false
    }
    
    override fun hangupCall(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return try {
                telecomManager?.endCall() ?: false
            } catch (e: SecurityException) {
                Logger.e(TAG, "Falta permiso ANSWER_PHONE_CALLS para colgar", e)
                false
            } catch (e: Exception) {
                Logger.e(TAG, "Error al intentar colgar llamada", e)
                false
            }
        } else {
            Logger.w(TAG, "Colgar programáticamente requiere Android 9+ (API 28)")
            return false
        }
    }
    
    override fun setSpeakerphone(enabled: Boolean) {
        try {
            audioManager.mode = AudioManager.MODE_IN_CALL
            audioManager.isSpeakerphoneOn = enabled
            Logger.i(TAG, "Altavoz ${if (enabled) "activado" else "desactivado"}")
        } catch (e: Exception) {
            Logger.e(TAG, "Error cambiando altavoz", e)
        }
    }
    
    override fun setMuted(muted: Boolean) {
        try {
            audioManager.isMicrophoneMute = muted
            Logger.i(TAG, "Micrófono ${if (muted) "muteado" else "activado"}")
        } catch (e: Exception) {
            Logger.e(TAG, "Error cambiando estado del micrófono", e)
        }
    }
    
    override fun setOnHold(onHold: Boolean) {
        Logger.w(TAG, "GenericCallManager no puede controlar el estado de espera")
        Logger.w(TAG, "Use la interfaz del sistema o un CallManager específico")
    }
    
    override fun startRecording(autoRecord: Boolean): Boolean {
        if (callRecorder.isRecording()) {
            Logger.w(TAG, "La grabación ya está activa")
            return true
        }
        
        // Verificar que hay una llamada activa
        if (!isCallActive()) {
            Logger.w(TAG, "No hay llamada activa para grabar")
            return false
        }
        
        val phoneNumber = currentPhoneNumber ?: "unknown"
        val recordingPath = callRecorder.startRecording(phoneNumber)
        
        return if (recordingPath != null) {
            Logger.i(TAG, "Grabación iniciada: $recordingPath")
            Logger.i(TAG, "⚠️ Grabando AMBOS lados de la conversación con VOICE_CALL")
            true
        } else {
            Logger.e(TAG, "Error al iniciar grabación")
            false
        }
    }
    
    override fun stopRecording(): String? {
        if (!callRecorder.isRecording()) {
            return null
        }
        
        val recordingPath = callRecorder.stopRecording()
        
        recordingPath?.let { path ->
            Logger.i(TAG, "Grabación finalizada: $path")
            getCurrentCall()?.let { callInfo ->
                notifyRecordingReady(callInfo.copy(recordingPath = path), path)
            }
        }
        
        return recordingPath
    }
    
    override fun isRecording(): Boolean = callRecorder.isRecording()
    
    override fun addListener(listener: CallManager.CallListener) {
        listeners.add(listener)
    }
    
    override fun removeListener(listener: CallManager.CallListener) {
        listeners.remove(listener)
    }
    
    override fun cleanup() {
        stopRecording()
        callRecorder.release()
        listeners.clear()
    }
    
    // Métodos públicos adicionales
    
    /**
     * Notifica que una llamada ha iniciado (llamar desde BroadcastReceiver)
     */
    fun notifyCallStarted(phoneNumber: String) {
        currentPhoneNumber = phoneNumber
        currentState = CallManager.CallState.RINGING
        callStartTime = System.currentTimeMillis()
        
        getCurrentCall()?.let { callInfo ->
            listeners.forEach { listener ->
                try {
                    listener.onCallStateChanged(callInfo)
                    listener.onCallStarted(callInfo)
                } catch (e: Exception) {
                    Logger.e(TAG, "Error notificando inicio de llamada", e)
                }
            }
        }
    }
    
    /**
     * Notifica que una llamada se ha conectado
     */
    fun notifyCallConnected() {
        if (currentState != CallManager.CallState.IDLE) {
            currentState = CallManager.CallState.ACTIVE
            
            getCurrentCall()?.let { callInfo ->
                listeners.forEach { listener ->
                    try {
                        listener.onCallStateChanged(callInfo)
                    } catch (e: Exception) {
                        Logger.e(TAG, "Error notificando conexión", e)
                    }
                }
            }
        }
    }
    
    /**
     * Notifica que una llamada ha terminado
     */
    fun notifyCallEnded() {
        val duration = if (callStartTime > 0) {
            System.currentTimeMillis() - callStartTime
        } else {
            0L
        }
        
        // Detener grabación si está activa
        stopRecording()
        
        getCurrentCall()?.let { callInfo ->
            listeners.forEach { listener ->
                try {
                    listener.onCallEnded(callInfo, duration)
                } catch (e: Exception) {
                    Logger.e(TAG, "Error notificando fin de llamada", e)
                }
            }
        }
        
        currentState = CallManager.CallState.ENDED
        currentPhoneNumber = null
        callStartTime = 0
        
        // Después de un breve delay, volver a IDLE
        android.os.Handler(context.mainLooper).postDelayed({
            currentState = CallManager.CallState.IDLE
        }, 1000)
    }
    
    // Métodos privados
    
    private fun isCallActive(): Boolean {
        return try {
            audioManager.mode == AudioManager.MODE_IN_CALL ||
            audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
        } catch (e: Exception) {
            Logger.e(TAG, "Error verificando llamada activa", e)
            false
        }
    }
    
    private fun notifyRecordingReady(callInfo: CallManager.CallInfo, recordingPath: String) {
        listeners.forEach { listener ->
            try {
                listener.onCallRecordingReady(callInfo, recordingPath)
            } catch (e: Exception) {
                Logger.e(TAG, "Error notificando grabación lista", e)
            }
        }
    }
}
