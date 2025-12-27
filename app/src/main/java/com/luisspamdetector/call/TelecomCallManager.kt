package com.luisspamdetector.call

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.telecom.Call as TelecomCall
import com.luisspamdetector.util.Logger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Implementación de CallManager para llamadas GSM nativas usando TelecomManager.
 * Requiere Android 6.0+ (API 23) para funcionalidad completa.
 */
class TelecomCallManager(
    private val context: Context,
    private val telecomCall: TelecomCall
) : CallManager {
    
    companion object {
        private const val TAG = "TelecomCallManager"
    }
    
    private val callRecorder = CallRecorder(context)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val listeners = CopyOnWriteArrayList<CallManager.CallListener>()
    
    private var callStartTime: Long = 0
    private var currentState = CallManager.CallState.IDLE
    private var autoRecordEnabled = false
    private var phoneNumber: String = ""
    
    private val telecomCallback = object : TelecomCall.Callback() {
        override fun onStateChanged(call: TelecomCall, state: Int) {
            updateCallState(state)
        }
        
        override fun onDetailsChanged(call: TelecomCall, details: TelecomCall.Details) {
            phoneNumber = details.handle?.schemeSpecificPart ?: ""
        }
    }
    
    init {
        telecomCall.registerCallback(telecomCallback)
        phoneNumber = telecomCall.details.handle?.schemeSpecificPart ?: "unknown"
        updateCallState(telecomCall.state)
    }
    
    override fun getCurrentCall(): CallManager.CallInfo? {
        val details = telecomCall.details ?: return null
        
        return CallManager.CallInfo(
            id = details.telecomCallId ?: System.currentTimeMillis().toString(),
            phoneNumber = phoneNumber,
            displayName = details.callerDisplayName?.toString(),
            isIncoming = details.callDirection == TelecomCall.Details.DIRECTION_INCOMING,
            state = currentState,
            startTime = callStartTime,
            recordingPath = callRecorder.getCurrentFilePath()
        )
    }
    
    override fun answerCall(): Boolean {
        return try {
            if (currentState == CallManager.CallState.RINGING) {
                Logger.i(TAG, "Descolgando llamada GSM")
                
                telecomCall.answer(TelecomCall.Details.ROUTE_EARPIECE)
                callStartTime = System.currentTimeMillis()
                
                // Si auto-grabación está habilitada, iniciar
                if (autoRecordEnabled) {
                    // Esperar un poco para que la llamada se establezca
                    android.os.Handler(context.mainLooper).postDelayed({
                        startRecording(false)
                    }, 1000)
                }
                
                notifyCallStarted()
                true
            } else {
                Logger.w(TAG, "La llamada no está en estado para ser contestada: $currentState")
                false
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error al descolgar llamada", e)
            false
        }
    }
    
    override fun hangupCall(): Boolean {
        return try {
            Logger.i(TAG, "Colgando llamada GSM")
            
            // Detener grabación si está activa
            stopRecording()
            
            val duration = if (callStartTime > 0) {
                System.currentTimeMillis() - callStartTime
            } else {
                0L
            }
            
            telecomCall.disconnect()
            
            notifyCallEnded(duration)
            
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Error al colgar llamada", e)
            false
        }
    }
    
    override fun setSpeakerphone(enabled: Boolean) {
        try {
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
        try {
            if (onHold) {
                telecomCall.hold()
                Logger.i(TAG, "Llamada en espera")
            } else {
                telecomCall.unhold()
                Logger.i(TAG, "Llamada reanudada")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error cambiando estado de espera", e)
        }
    }
    
    override fun startRecording(autoRecord: Boolean): Boolean {
        if (callRecorder.isRecording()) {
            Logger.w(TAG, "La grabación ya está activa")
            return true
        }
        
        autoRecordEnabled = autoRecord
        
        val recordingPath = callRecorder.startRecording(phoneNumber)
        
        return if (recordingPath != null) {
            Logger.i(TAG, "Grabación de llamada GSM iniciada: $recordingPath")
            Logger.i(TAG, "⚠️ IMPORTANTE: Grabando AMBOS lados con AudioSource.VOICE_CALL")
            true
        } else {
            Logger.e(TAG, "Error al iniciar grabación de llamada")
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
        telecomCall.unregisterCallback(telecomCallback)
        listeners.clear()
    }
    
    // Métodos privados
    
    private fun updateCallState(telecomState: Int) {
        val newState = when (telecomState) {
            TelecomCall.STATE_NEW -> CallManager.CallState.IDLE
            TelecomCall.STATE_RINGING -> CallManager.CallState.RINGING
            TelecomCall.STATE_DIALING -> CallManager.CallState.DIALING
            TelecomCall.STATE_ACTIVE -> CallManager.CallState.ACTIVE
            TelecomCall.STATE_HOLDING -> CallManager.CallState.HOLDING
            TelecomCall.STATE_DISCONNECTED -> CallManager.CallState.ENDED
            else -> CallManager.CallState.IDLE
        }
        
        if (newState != currentState) {
            currentState = newState
            getCurrentCall()?.let { callInfo ->
                notifyStateChanged(callInfo)
            }
        }
    }
    
    private fun notifyStateChanged(callInfo: CallManager.CallInfo) {
        listeners.forEach { listener ->
            try {
                listener.onCallStateChanged(callInfo)
            } catch (e: Exception) {
                Logger.e(TAG, "Error notificando cambio de estado", e)
            }
        }
    }
    
    private fun notifyCallStarted() {
        getCurrentCall()?.let { callInfo ->
            listeners.forEach { listener ->
                try {
                    listener.onCallStarted(callInfo)
                } catch (e: Exception) {
                    Logger.e(TAG, "Error notificando inicio de llamada", e)
                }
            }
        }
    }
    
    private fun notifyCallEnded(duration: Long) {
        getCurrentCall()?.let { callInfo ->
            listeners.forEach { listener ->
                try {
                    listener.onCallEnded(callInfo, duration)
                } catch (e: Exception) {
                    Logger.e(TAG, "Error notificando fin de llamada", e)
                }
            }
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
