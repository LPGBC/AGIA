package com.luisspamdetector.call

import android.content.Context
import android.media.AudioManager
import com.luisspamdetector.util.Logger
import org.linphone.core.Call
import org.linphone.core.AudioDevice
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Implementación de CallManager para llamadas Linphone (SIP/VoIP).
 * Combina el control de Linphone con grabación nativa de Android.
 */
class LinphoneCallManager(
    private val context: Context,
    private val linphoneCall: Call
) : CallManager {
    
    companion object {
        private const val TAG = "LinphoneCallManager"
    }
    
    private val callRecorder = CallRecorder(context)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val listeners = CopyOnWriteArrayList<CallManager.CallListener>()
    
    private var callStartTime: Long = 0
    private var currentState = CallManager.CallState.IDLE
    private var autoRecordEnabled = false
    
    init {
        updateCallState()
    }
    
    override fun getCurrentCall(): CallManager.CallInfo? {
        val phoneNumber = linphoneCall.remoteAddress?.asStringUriOnly() ?: return null
        val displayName = linphoneCall.remoteAddress?.displayName
        
        return CallManager.CallInfo(
            id = linphoneCall.callLog?.callId ?: System.currentTimeMillis().toString(),
            phoneNumber = phoneNumber,
            displayName = displayName,
            isIncoming = linphoneCall.dir == Call.Dir.Incoming,
            state = currentState,
            startTime = callStartTime,
            recordingPath = callRecorder.getCurrentFilePath()
        )
    }
    
    override fun answerCall(): Boolean {
        return try {
            if (linphoneCall.state == Call.State.IncomingReceived || 
                linphoneCall.state == Call.State.IncomingEarlyMedia) {
                
                Logger.i(TAG, "Descolgando llamada Linphone")
                linphoneCall.accept()
                
                callStartTime = System.currentTimeMillis()
                updateCallState()
                
                // Si auto-grabación está habilitada, iniciar
                if (autoRecordEnabled) {
                    startRecording(false)
                }
                
                notifyCallStarted()
                true
            } else {
                Logger.w(TAG, "La llamada no está en estado para ser contestada: ${linphoneCall.state}")
                false
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error al descolgar llamada", e)
            false
        }
    }
    
    override fun hangupCall(): Boolean {
        return try {
            Logger.i(TAG, "Colgando llamada Linphone")
            
            // Detener grabación si está activa
            stopRecording()
            
            val duration = if (callStartTime > 0) {
                System.currentTimeMillis() - callStartTime
            } else {
                0L
            }
            
            linphoneCall.terminate()
            
            updateCallState()
            notifyCallEnded(duration)
            
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Error al colgar llamada", e)
            false
        }
    }
    
    override fun setSpeakerphone(enabled: Boolean) {
        try {
            val core = linphoneCall.core ?: return
            
            if (enabled) {
                // Buscar altavoz
                val speakerDevice = core.audioDevices.find { 
                    it.type == AudioDevice.Type.Speaker &&
                    it.hasCapability(AudioDevice.Capabilities.CapabilityPlay)
                }
                
                speakerDevice?.let {
                    linphoneCall.outputAudioDevice = it
                    Logger.i(TAG, "Altavoz activado: ${it.deviceName}")
                }
            } else {
                // Buscar auricular/earpiece
                val earpieceDevice = core.audioDevices.find { 
                    it.type == AudioDevice.Type.Earpiece &&
                    it.hasCapability(AudioDevice.Capabilities.CapabilityPlay)
                }
                
                earpieceDevice?.let {
                    linphoneCall.outputAudioDevice = it
                    Logger.i(TAG, "Auricular activado: ${it.deviceName}")
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error cambiando altavoz", e)
        }
    }
    
    override fun setMuted(muted: Boolean) {
        try {
            linphoneCall.microphoneMuted = muted
            Logger.i(TAG, "Micrófono ${if (muted) "muteado" else "activado"}")
        } catch (e: Exception) {
            Logger.e(TAG, "Error cambiando estado del micrófono", e)
        }
    }
    
    override fun setOnHold(onHold: Boolean) {
        try {
            if (onHold) {
                linphoneCall.pause()
                Logger.i(TAG, "Llamada en espera")
            } else {
                linphoneCall.resume()
                Logger.i(TAG, "Llamada reanudada")
            }
            updateCallState()
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
        
        val phoneNumber = linphoneCall.remoteAddress?.asStringUriOnly() ?: "unknown"
        val recordingPath = callRecorder.startRecording(phoneNumber)
        
        return if (recordingPath != null) {
            Logger.i(TAG, "Grabación de llamada iniciada: $recordingPath")
            Logger.i(TAG, "⚠️ IMPORTANTE: Grabando AMBOS lados de la conversación con VOICE_CALL")
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
        listeners.clear()
    }
    
    // Métodos privados
    
    private fun updateCallState() {
        val newState = when (linphoneCall.state) {
            Call.State.Idle -> CallManager.CallState.IDLE
            Call.State.IncomingReceived,
            Call.State.IncomingEarlyMedia -> CallManager.CallState.RINGING
            Call.State.OutgoingInit,
            Call.State.OutgoingProgress,
            Call.State.OutgoingRinging,
            Call.State.OutgoingEarlyMedia -> CallManager.CallState.DIALING
            Call.State.Connected,
            Call.State.StreamsRunning -> CallManager.CallState.ACTIVE
            Call.State.Paused,
            Call.State.Pausing,
            Call.State.PausedByRemote -> CallManager.CallState.HOLDING
            Call.State.End,
            Call.State.Released -> CallManager.CallState.ENDED
            Call.State.Updating,
            Call.State.UpdatedByRemote,
            Call.State.Resuming -> CallManager.CallState.ACTIVE
            Call.State.Referred,
            Call.State.Error -> CallManager.CallState.ENDED
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
