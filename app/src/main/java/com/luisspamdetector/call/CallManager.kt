package com.luisspamdetector.call

import android.content.Context
import android.telecom.TelecomManager
import android.telecom.Call as TelecomCall
import com.luisspamdetector.util.Logger
import org.linphone.core.Call as LinphoneCall

/**
 * Interfaz unificada para gestionar llamadas telefónicas.
 * Soporta tanto llamadas GSM nativas (TelecomManager) como llamadas SIP (Linphone).
 * Permite control manual (descolgar/colgar) en cualquier situación.
 */
interface CallManager {
    
    /**
     * Información de una llamada activa
     */
    data class CallInfo(
        val id: String,
        val phoneNumber: String,
        val displayName: String?,
        val isIncoming: Boolean,
        val state: CallState,
        val startTime: Long,
        val recordingPath: String? = null
    )
    
    /**
     * Estados posibles de una llamada
     */
    enum class CallState {
        IDLE,           // Sin llamada
        RINGING,        // Llamada entrante sonando
        DIALING,        // Llamada saliente marcando
        ACTIVE,         // Llamada en curso (conectada)
        HOLDING,        // Llamada en espera
        ENDING,         // Llamada finalizando
        ENDED           // Llamada terminada
    }
    
    /**
     * Callback para eventos de llamadas
     */
    interface CallListener {
        fun onCallStateChanged(callInfo: CallInfo)
        fun onCallStarted(callInfo: CallInfo)
        fun onCallEnded(callInfo: CallInfo, duration: Long)
        fun onCallRecordingReady(callInfo: CallInfo, recordingPath: String)
    }
    
    /**
     * Obtiene la información de la llamada actual
     */
    fun getCurrentCall(): CallInfo?
    
    /**
     * Descuelga la llamada actual (si está sonando)
     * @return true si se pudo descolgar, false en caso contrario
     */
    fun answerCall(): Boolean
    
    /**
     * Cuelga la llamada actual
     * @return true si se pudo colgar, false en caso contrario
     */
    fun hangupCall(): Boolean
    
    /**
     * Activa/desactiva el altavoz
     */
    fun setSpeakerphone(enabled: Boolean)
    
    /**
     * Activa/desactiva el micrófono
     */
    fun setMuted(muted: Boolean)
    
    /**
     * Pone la llamada en espera o la recupera
     */
    fun setOnHold(onHold: Boolean)
    
    /**
     * Inicia la grabación de la llamada
     * @param autoRecord si es true, graba automáticamente todas las llamadas
     */
    fun startRecording(autoRecord: Boolean = false): Boolean
    
    /**
     * Detiene la grabación de la llamada
     */
    fun stopRecording(): String?
    
    /**
     * Verifica si la grabación está activa
     */
    fun isRecording(): Boolean
    
    /**
     * Registra un listener para eventos de llamadas
     */
    fun addListener(listener: CallListener)
    
    /**
     * Elimina un listener
     */
    fun removeListener(listener: CallListener)
    
    /**
     * Limpia recursos
     */
    fun cleanup()
}

/**
 * Factory para crear instancias de CallManager según el tipo de llamada
 */
object CallManagerFactory {
    
    private const val TAG = "CallManagerFactory"
    
    /**
     * Crea un CallManager para llamadas Linphone (SIP)
     */
    fun createLinphoneCallManager(
        context: Context,
        call: LinphoneCall
    ): CallManager {
        Logger.d(TAG, "Creando LinphoneCallManager para llamada SIP")
        return LinphoneCallManager(context, call)
    }
    
    /**
     * Crea un CallManager para llamadas GSM nativas
     */
    fun createTelecomCallManager(
        context: Context,
        call: TelecomCall
    ): CallManager {
        Logger.d(TAG, "Creando TelecomCallManager para llamada GSM")
        return TelecomCallManager(context, call)
    }
    
    /**
     * Crea un CallManager genérico basado en el contexto actual
     */
    fun createCallManager(context: Context): CallManager {
        // Por defecto, intentar usar el sistema de telefonía nativo
        Logger.d(TAG, "Creando CallManager genérico")
        return GenericCallManager(context)
    }
}
