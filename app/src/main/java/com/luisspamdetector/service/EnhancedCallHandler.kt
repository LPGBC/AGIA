package com.luisspamdetector.service

import android.content.Context
import android.content.Intent
import com.luisspamdetector.call.*
import com.luisspamdetector.data.CallRecordingRepository
import com.luisspamdetector.data.SpamDatabase
import com.luisspamdetector.ui.CallControlActivity
import com.luisspamdetector.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.linphone.core.Call
import java.io.File

/**
 * Extension de LinphoneService para integrar el nuevo sistema de grabación.
 * 
 * Este archivo muestra cómo integrar CallManager en el servicio existente.
 */

/**
 * Gestor de llamadas con grabación avanzada
 */
class EnhancedCallHandler(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "EnhancedCallHandler"
    }
    
    private val callRecordingRepository: CallRecordingRepository
    private val transcriptionService: CallTranscriptionService
    private val activeCallManagers = mutableMapOf<String, CallManager>()
    
    init {
        val database = SpamDatabase.getDatabase(context)
        callRecordingRepository = CallRecordingRepository(database.callRecordingDao())
        
        // Inicializar servicio de transcripción
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""
        val geminiService = com.luisspamdetector.api.GeminiApiService(apiKey)
        transcriptionService = CallTranscriptionService(context, geminiService)
    }
    
    /**
     * Maneja una nueva llamada entrante de Linphone
     */
    fun handleIncomingLinphoneCall(call: Call, phoneNumber: String, autoRecord: Boolean = true) {
        Logger.i(TAG, "Manejando llamada entrante de: $phoneNumber")
        
        // Crear CallManager para esta llamada
        val callManager = CallManagerFactory.createLinphoneCallManager(context, call)
        val callId = call.callLog?.callId ?: System.currentTimeMillis().toString()
        
        activeCallManagers[callId] = callManager
        
        // Registrar listeners
        callManager.addListener(object : CallManager.CallListener {
            override fun onCallStateChanged(callInfo: CallManager.CallInfo) {
                Logger.d(TAG, "Estado de llamada: ${callInfo.state}")
                
                when (callInfo.state) {
                    CallManager.CallState.ACTIVE -> {
                        // Llamada conectada - iniciar grabación si está habilitada
                        if (autoRecord) {
                            scope.launch {
                                callManager.startRecording(autoRecord = true)
                                Logger.i(TAG, "⏺️ Grabación automática iniciada")
                            }
                        }
                    }
                    CallManager.CallState.ENDED -> {
                        // Llamada terminada - limpiar
                        activeCallManagers.remove(callId)
                        callManager.cleanup()
                    }
                    else -> {}
                }
            }
            
            override fun onCallStarted(callInfo: CallManager.CallInfo) {
                Logger.i(TAG, "Llamada iniciada: ${callInfo.phoneNumber}")
                
                // Mostrar UI de control si no está en modo automático
                val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                val showControlUI = prefs.getBoolean("show_call_control_ui", true)
                
                if (showControlUI) {
                    showCallControlActivity(callInfo)
                }
            }
            
            override fun onCallEnded(callInfo: CallManager.CallInfo, duration: Long) {
                Logger.i(TAG, "Llamada finalizada - Duración: ${duration}ms")
                
                // La grabación ya fue guardada en onCallRecordingReady
            }
            
            override fun onCallRecordingReady(callInfo: CallManager.CallInfo, recordingPath: String) {
                Logger.i(TAG, "✅ Grabación lista: $recordingPath")
                
                // Guardar en base de datos
                scope.launch(Dispatchers.IO) {
                    saveRecordingToDatabase(callInfo, recordingPath)
                }
            }
        })
        
        // Por defecto, las llamadas se manejan según configuración
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val autoAnswer = prefs.getBoolean("auto_answer_screening", false)
        
        if (autoAnswer) {
            // Auto-contestar para screening
            callManager.answerCall()
        } else {
            // Mostrar UI para que el usuario decida
            showCallControlActivity(callManager.getCurrentCall()!!)
        }
    }
    
    /**
     * Guarda la grabación en la base de datos
     */
    private suspend fun saveRecordingToDatabase(
        callInfo: CallManager.CallInfo,
        recordingPath: String
    ) {
        try {
            val file = File(recordingPath)
            if (!file.exists()) {
                Logger.e(TAG, "Archivo de grabación no existe: $recordingPath")
                return
            }
            
            val recordingId = callRecordingRepository.saveRecording(
                phoneNumber = callInfo.phoneNumber,
                displayName = callInfo.displayName,
                isIncoming = callInfo.isIncoming,
                duration = System.currentTimeMillis() - callInfo.startTime,
                recordingPath = recordingPath,
                recordingSize = file.length(),
                recordingFormat = "aac",
                wasAnswered = callInfo.state != CallManager.CallState.IDLE
            )
            
            Logger.i(TAG, "✅ Grabación guardada en BD con ID: $recordingId")
            
            // Iniciar transcripción en background
            processRecordingWithAI(recordingId, callInfo, recordingPath)
            
        } catch (e: Exception) {
            Logger.e(TAG, "Error guardando grabación en BD", e)
        }
    }
    
    /**
     * Procesa la grabación con IA
     */
    private fun processRecordingWithAI(
        recordingId: Long,
        callInfo: CallManager.CallInfo,
        recordingPath: String
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                Logger.i(TAG, "🤖 Iniciando análisis con IA...")
                
                val duration = System.currentTimeMillis() - callInfo.startTime
                
                transcriptionService.processRecording(
                    recordingPath = recordingPath,
                    phoneNumber = callInfo.phoneNumber,
                    duration = duration,
                    callback = object : CallTranscriptionService.TranscriptionCallback {
                        override fun onTranscriptionStarted(recordingPath: String) {
                            Logger.d(TAG, "Transcripción iniciada")
                        }
                        
                        override fun onTranscriptionProgress(progress: Int) {
                            Logger.d(TAG, "Progreso: $progress%")
                        }
                        
                        override fun onTranscriptionCompleted(result: CallTranscriptionService.TranscriptionResult) {
                            Logger.i(TAG, "✅ Transcripción completada")
                            Logger.i(TAG, "Resumen: ${result.summary}")
                            Logger.i(TAG, "Spam: ${result.isSpam} (${result.spamConfidence})")
                            
                            // Actualizar en base de datos
                            scope.launch(Dispatchers.IO) {
                                try {
                                    callRecordingRepository.updateWithTranscription(recordingId, result)
                                    Logger.i(TAG, "✅ Base de datos actualizada con análisis IA")
                                    
                                    // Notificar al usuario
                                    showTranscriptionNotification(callInfo.phoneNumber, result)
                                } catch (e: Exception) {
                                    Logger.e(TAG, "Error actualizando BD", e)
                                }
                            }
                        }
                        
                        override fun onTranscriptionFailed(error: String) {
                            Logger.e(TAG, "❌ Transcripción falló: $error")
                            
                            // Marcar como fallida en BD
                            scope.launch(Dispatchers.IO) {
                                callRecordingRepository.markTranscriptionFailed(recordingId, error)
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Logger.e(TAG, "Error procesando con IA", e)
            }
        }
    }
    
    /**
     * Muestra la actividad de control de llamadas
     */
    private fun showCallControlActivity(callInfo: CallManager.CallInfo) {
        val intent = Intent(context, CallControlActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(CallControlActivity.EXTRA_PHONE_NUMBER, callInfo.phoneNumber)
            putExtra(CallControlActivity.EXTRA_DISPLAY_NAME, callInfo.displayName)
            // Aquí se pueden agregar datos de spam si están disponibles
        }
        
        context.startActivity(intent)
    }
    
    /**
     * Muestra notificación con el resumen de IA
     */
    private fun showTranscriptionNotification(
        phoneNumber: String,
        result: CallTranscriptionService.TranscriptionResult
    ) {
        // TODO: Implementar notificación con el resumen
        Logger.i(TAG, "📱 Mostrando notificación de resumen para $phoneNumber")
    }
    
    /**
     * Obtiene el CallManager activo para una llamada
     */
    fun getCallManager(callId: String): CallManager? {
        return activeCallManagers[callId]
    }
    
    /**
     * Limpia recursos
     */
    fun cleanup() {
        activeCallManagers.values.forEach { it.cleanup() }
        activeCallManagers.clear()
        transcriptionService.cleanup()
    }
}

/**
 * Ejemplo de integración en LinphoneService
 */
class LinphoneServiceIntegrationExample {
    
    private var enhancedCallHandler: EnhancedCallHandler? = null
    
    fun onCreate(context: Context, scope: CoroutineScope) {
        // Inicializar el handler mejorado
        enhancedCallHandler = EnhancedCallHandler(context, scope)
    }
    
    fun onCallStateChanged(call: Call, state: Call.State) {
        when (state) {
            Call.State.IncomingReceived, 
            Call.State.IncomingEarlyMedia -> {
                val phoneNumber = call.remoteAddress?.asStringUriOnly() ?: return
                
                // Verificar configuración de grabación automática
                val autoRecord = true // Leer de preferencias
                
                // Delegar al handler mejorado
                enhancedCallHandler?.handleIncomingLinphoneCall(call, phoneNumber, autoRecord)
            }
            else -> {
                // Otros estados se manejan por el listener del CallManager
            }
        }
    }
    
    fun onDestroy() {
        enhancedCallHandler?.cleanup()
    }
}
