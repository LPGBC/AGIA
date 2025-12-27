package com.luisspamdetector.service

import android.app.*
import android.content.*
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.luisspamdetector.MainActivity
import com.luisspamdetector.R
import com.luisspamdetector.api.GeminiApiService
import com.luisspamdetector.data.SpamCheckEntity
import com.luisspamdetector.data.SpamDatabase
import com.luisspamdetector.data.SpamRepository
import com.luisspamdetector.data.ScreeningHistoryRepository
import com.luisspamdetector.ui.IncomingCallActivity
import com.luisspamdetector.ui.ScreeningOverlayActivity
import com.luisspamdetector.util.ContactsHelper
import com.luisspamdetector.util.Logger
import kotlinx.coroutines.*
import org.linphone.core.*

/**
 * Servicio principal de Linphone para detección de spam.
 * Actualizado para Linphone SDK 5.4.x y Android 15 (API 35)
 */
class LinphoneService : Service() {

    companion object {
        private const val TAG = "LinphoneService"
        private const val CHANNEL_ID = "LinphoneSpamDetectorChannel"
        private const val CHANNEL_SCREENING_ID = "LinphoneScreeningChannel"
        private const val NOTIFICATION_ID = 1001
        
        // Estado del servicio
        var isRunning = false
            private set
        
        // Acción broadcast para notificar cambio de estado de registro
        const val ACTION_REGISTRATION_STATE_CHANGED = "com.luisspamdetector.REGISTRATION_STATE_CHANGED"
        const val EXTRA_IS_REGISTERED = "is_registered"
    }

    private var core: Core? = null
    private var coreListener: CoreListenerStub? = null
    
    private lateinit var geminiService: GeminiApiService
    private lateinit var contactsHelper: ContactsHelper
    private lateinit var spamRepository: SpamRepository
    private lateinit var screeningHistoryRepository: ScreeningHistoryRepository
    private var callScreeningService: CallScreeningService? = null
    private var silentScreeningService: SilentCallScreeningService? = null
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentScreeningCall: Call? = null

    // Receiver para acciones de la UI de screening
    private val callActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ScreeningOverlayActivity.ACTION_ACCEPT_CALL -> {
                    callScreeningService?.acceptCall()
                    silentScreeningService?.stopScreening()
                }
                ScreeningOverlayActivity.ACTION_REJECT_CALL -> {
                    // Terminar la llamada usando todos los métodos disponibles
                    callScreeningService?.rejectCall()
                    silentScreeningService?.rejectCall()
                    
                    // También terminar directamente la llamada actual si existe
                    currentScreeningCall?.let { call ->
                        Logger.i(TAG, "Terminando llamada desde reject: ${call.state}")
                        if (call.state != Call.State.End && call.state != Call.State.Released) {
                            call.terminate()
                            Logger.i(TAG, "Llamada terminada por usuario")
                        }
                    }
                    currentScreeningCall = null
                }
            }
        }
    }

    private var isReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        Logger.d(TAG, "LinphoneService onCreate")

        try {
            initializeComponents()
            createNotificationChannels()
            startForegroundServiceCompat()
            initializeLinphoneCore()
            registerReceivers()
            
            // Configurar cuenta SIP si está disponible
            configureSipAccountIfAvailable()
            
            isRunning = true
            Logger.i(TAG, "LinphoneService iniciado correctamente")
        } catch (e: Exception) {
            Logger.e(TAG, "Error crítico al iniciar LinphoneService", e)
            stopSelf()
        }
    }

    private fun initializeComponents() {
        val apiKey = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("gemini_api_key", "") ?: ""

        geminiService = GeminiApiService(apiKey)
        contactsHelper = ContactsHelper(this)
        
        val database = SpamDatabase.getDatabase(this)
        spamRepository = SpamRepository(database.spamCheckDao())
        screeningHistoryRepository = ScreeningHistoryRepository(database.screeningHistoryDao())
        
        // Inicializar call screening si hay API key
        if (apiKey.isNotEmpty()) {
            callScreeningService = CallScreeningService(this, geminiService)
            callScreeningService?.initialize(
                callback = object : CallScreeningService.ScreeningCallback {
                    override fun onScreeningCompleted(name: String?, purpose: String?, phoneNumber: String) {
                        handleScreeningCompleted(name, purpose, phoneNumber)
                    }

                    override fun onScreeningFailed(reason: String) {
                        Logger.e(TAG, "Screening falló: $reason")
                    }
                },
                micCallback = object : CallScreeningService.MicrophoneCallback {
                    override fun setMicrophoneMuted(muted: Boolean) {
                        setCallMicrophoneMuted(muted)
                    }
                }
            )
        }
        
        // Inicializar servicio de screening silencioso (estilo iOS)
        silentScreeningService = SilentCallScreeningService(this)
        silentScreeningService?.initialize(object : SilentCallScreeningService.ScreeningCallback {
            override fun onScreeningStarted(call: Call, phoneNumber: String) {
                Logger.i(TAG, "Silent screening started for: $phoneNumber")
            }
            
            override fun onScreeningCompleted(phoneNumber: String, recordingPath: String?) {
                Logger.i(TAG, "Silent screening completed for: $phoneNumber")
                handleSilentScreeningCompleted(phoneNumber, recordingPath)
            }
            
            override fun onScreeningFailed(reason: String) {
                Logger.e(TAG, "Silent screening failed: $reason")
            }
        })
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(ScreeningOverlayActivity.ACTION_ACCEPT_CALL)
            addAction(ScreeningOverlayActivity.ACTION_REJECT_CALL)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(callActionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(callActionReceiver, filter)
        }
        isReceiverRegistered = true
        Logger.d(TAG, "Receiver registrado correctamente")
    }

    private fun initializeLinphoneCore() {
        try {
            val factory = Factory.instance()
            factory.setDebugMode(true, "LinphoneSpam")
            
            // Crear el Core usando el factory
            core = factory.createCore(null, null, this)
            
            Logger.i(TAG, "Linphone Core creado exitosamente")
            
            // Configurar el listener usando CoreListenerStub (API moderna)
            coreListener = object : CoreListenerStub() {
                override fun onGlobalStateChanged(core: Core, state: GlobalState, message: String) {
                    Logger.d(TAG, "Global state changed: $state - $message")
                    handleGlobalStateChanged(state, message)
                }

                override fun onAccountRegistrationStateChanged(
                    core: Core,
                    account: Account,
                    state: RegistrationState,
                    message: String
                ) {
                    Logger.d(TAG, "Account registration state: $state - $message")
                    handleRegistrationStateChanged(account, state, message)
                }

                override fun onCallStateChanged(
                    core: Core,
                    call: Call,
                    state: Call.State,
                    message: String
                ) {
                    Logger.d(TAG, "Call state changed: $state - $message")
                    handleCallStateChanged(call, state, message)
                }

                override fun onAudioDeviceChanged(core: Core, audioDevice: AudioDevice) {
                    Logger.d(TAG, "Audio device changed: ${audioDevice.deviceName}")
                }

                override fun onAudioDevicesListUpdated(core: Core) {
                    Logger.d(TAG, "Audio devices list updated")
                }
            }

            core?.addListener(coreListener)
            
            // Configuraciones del Core para Android 15
            configureCore()
            
            // Iniciar el Core
            core?.start()
            
            Logger.d(TAG, "Linphone Core iniciado correctamente - versión: ${core?.version}")

        } catch (e: Exception) {
            Logger.e(TAG, "Error al inicializar Linphone Core", e)
        }
    }

    private fun configureCore() {
        core?.apply {
            // Configuración de audio
            isNativeRingingEnabled = false
            ringDuringIncomingEarlyMedia = false
            
            // IMPORTANTE: Configuración para grabación de llamadas
            // Asegurar que se graben ambos lados del audio
            // El archivo de grabación se establece por llamada en handleCallScreening
            
            // Configuración de red
            setUserAgent("LinphoneSpamDetector", "2.0")
            
            // Configurar transportes SIP - usar los puertos por defecto
            // -1 significa puerto aleatorio, 0 significa deshabilitado
            Logger.d(TAG, "Transportes SIP disponibles")
            
            // Habilitar IPv6 si está disponible
            isIpv6Enabled = true
            
            // Configuración de NAT
            natPolicy?.apply {
                isStunEnabled = true
                stunServer = "stun.linphone.org"
            }
            
            // Configuración de codecs para mejor calidad de grabación
            // Priorizar OPUS que tiene buena calidad para voz
            audioPayloadTypes.forEach { payloadType ->
                if (payloadType.mimeType.contains("opus", ignoreCase = true)) {
                    payloadType.enable(true)
                    Logger.d(TAG, "Codec OPUS habilitado")
                }
            }
            
            // Auto-iterate habilitado por defecto en SDK 5.4+
            // El SDK maneja automáticamente el iterate() en Android
            
            Logger.d(TAG, "Core configurado correctamente para grabación")
        }
    }

    private fun handleGlobalStateChanged(state: GlobalState, message: String) {
        when (state) {
            GlobalState.On -> {
                Logger.i(TAG, "Linphone Core está activo y listo")
            }
            GlobalState.Off -> {
                Logger.i(TAG, "Linphone Core se ha detenido")
            }
            GlobalState.Startup -> {
                Logger.i(TAG, "Linphone Core iniciando...")
            }
            GlobalState.Shutdown -> {
                Logger.i(TAG, "Linphone Core apagándose...")
            }
            GlobalState.Configuring -> {
                Logger.i(TAG, "Linphone Core configurándose...")
            }
            GlobalState.Ready -> {
                Logger.i(TAG, "Linphone Core listo")
            }
        }
    }

    private fun handleRegistrationStateChanged(
        account: Account,
        state: RegistrationState,
        message: String
    ) {
        when (state) {
            RegistrationState.Ok -> {
                Logger.i(TAG, "Cuenta registrada: ${account.params?.identityAddress?.asString()}")
                updateNotification("Conectado: ${account.params?.identityAddress?.username}")
                broadcastRegistrationState(true)
            }
            RegistrationState.Failed -> {
                Logger.e(TAG, "Registro fallido: $message")
                updateNotification("Error de conexión")
                broadcastRegistrationState(false)
            }
            RegistrationState.Progress -> {
                Logger.i(TAG, "Registrando...")
                updateNotification("Conectando...")
                broadcastRegistrationState(false)
            }
            RegistrationState.Cleared -> {
                Logger.i(TAG, "Registro limpiado")
                updateNotification("Desconectado")
                broadcastRegistrationState(false)
            }
            RegistrationState.None -> {
                Logger.i(TAG, "Sin registro")
                broadcastRegistrationState(false)
            }
            RegistrationState.Refreshing -> {
                Logger.i(TAG, "Refrescando registro...")
            }
        }
    }

    private fun broadcastRegistrationState(isRegistered: Boolean) {
        val intent = Intent(ACTION_REGISTRATION_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_REGISTERED, isRegistered)
        }
        sendBroadcast(intent)
        Logger.d(TAG, "Broadcasting registration state: $isRegistered")
    }

    private fun handleCallStateChanged(call: Call, state: Call.State, message: String) {
        when (state) {
            Call.State.IncomingReceived, 
            Call.State.IncomingEarlyMedia -> {
                val phoneNumber = call.remoteAddress?.asStringUriOnly() ?: return
                Logger.i(TAG, "Llamada entrante de: $phoneNumber")
                handleIncomingCall(call, phoneNumber)
            }
            Call.State.Connected -> {
                Logger.i(TAG, "Llamada conectada")
            }
            Call.State.End, 
            Call.State.Released -> {
                Logger.i(TAG, "Llamada terminada")
                currentScreeningCall = null
            }
            Call.State.Error -> {
                Logger.e(TAG, "Error en llamada: $message")
                currentScreeningCall = null
            }
            else -> {
                Logger.d(TAG, "Estado de llamada: $state")
            }
        }
    }

    private fun handleIncomingCall(call: Call, phoneNumber: String) {
        serviceScope.launch {
            try {
                // 1. Obtener configuración
                val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                val callScreeningEnabled = prefs.getBoolean("call_screening_enabled", false)
                val spamDetectionEnabled = prefs.getBoolean("spam_detection_enabled", true)
                val testModeEnabled = prefs.getBoolean("test_mode_enabled", false)

                // 2. Verificar si está en contactos (solo si NO está en modo prueba)
                if (!testModeEnabled && contactsHelper.isNumberInContacts(phoneNumber)) {
                    val name = contactsHelper.getContactName(phoneNumber)
                    Logger.d(TAG, "Número en contactos: $name - ignorando (modo prueba: desactivado)")
                    return@launch
                }

                if (testModeEnabled) {
                    Logger.i(TAG, "MODO PRUEBA: Procesando llamada de $phoneNumber")
                }

                // 3. Procesar según configuración

                when {
                    callScreeningEnabled -> {
                        // Modo call screening: auto-contestar y filtrar
                        handleCallScreening(call, phoneNumber)
                    }
                    spamDetectionEnabled -> {
                        // Modo solo detección de spam
                        handleSpamDetection(phoneNumber)
                    }
                }

            } catch (e: Exception) {
                Logger.e(TAG, "Error al procesar llamada entrante", e)
            }
        }
    }

    private suspend fun handleSpamDetection(phoneNumber: String) {
        try {
            // 1. Verificar caché
            val cachedResult = spamRepository.getCheckResult(phoneNumber)
            if (cachedResult != null) {
                Logger.d(TAG, "Resultado en caché para $phoneNumber")
                showSpamWarningScreen(
                    phoneNumber, 
                    cachedResult.isSpam, 
                    cachedResult.reason, 
                    cachedResult.confidence
                )
                return
            }

            // 2. Consultar Gemini
            Logger.d(TAG, "Consultando Gemini para: $phoneNumber")
            val result = withContext(Dispatchers.IO) {
                geminiService.checkIfSpam(phoneNumber)
            }

            // 3. Guardar en caché
            val checkEntity = SpamCheckEntity(
                phoneNumber = phoneNumber,
                isSpam = result.isSpam,
                confidence = result.confidence,
                reason = result.reason
            )
            spamRepository.insertCheck(checkEntity)

            // 4. Mostrar UI y notificación
            showSpamWarningScreen(phoneNumber, result.isSpam, result.reason, result.confidence)
            showSpamNotification(phoneNumber, result.isSpam, result.reason)

        } catch (e: Exception) {
            Logger.e(TAG, "Error en detección de spam", e)
        }
    }

    private fun handleCallScreening(call: Call, phoneNumber: String) {
        currentScreeningCall = call
        val recordingPath = getCallRecordingPath(phoneNumber)

        try {
            // Configurar parámetros para grabar la llamada
            val callParams = core?.createCallParams(call)
            callParams?.apply {
                isAudioEnabled = true
                isVideoEnabled = false
                // Configurar archivo de grabación - IMPORTANTE: debe ser .wav para Linphone
                recordFile = recordingPath
            }
            
            // También configurar en el Core para asegurar que se grabe
            core?.recordFile = recordingPath
            Logger.i(TAG, "Archivo de grabación configurado: $recordingPath")
            
            call.acceptWithParams(callParams)
            Logger.d(TAG, "Llamada auto-contestada para screening silencioso")

            // Configurar audio y empezar grabación cuando la llamada esté activa
            serviceScope.launch {
                // Esperar a que la llamada esté en estado StreamsRunning
                var attempts = 0
                while (call.state != Call.State.StreamsRunning && attempts < 20) {
                    delay(100)
                    attempts++
                }
                
                Logger.i(TAG, "Estado de llamada después de espera: ${call.state}")
                
                // Configurar audio silencioso (sin afectar la grabación)
                configureAudioForSilentScreening(call)
                
                // Iniciar grabación de la llamada - DEBE estar en StreamsRunning
                if (call.state == Call.State.StreamsRunning) {
                    call.startRecording()
                    Logger.i(TAG, "✓ Grabación de llamada iniciada: $recordingPath")
                    Logger.i(TAG, "  - isRecording: ${call.params?.isRecording}")
                    Logger.i(TAG, "  - recordFile: ${call.params?.recordFile}")
                } else {
                    Logger.w(TAG, "Llamada no está en StreamsRunning, intentando grabar de todos modos...")
                    call.startRecording()
                }
            }

            // Mostrar overlay de screening (modo silencioso)
            showScreeningOverlay(phoneNumber)

            // Iniciar proceso de screening SILENCIOSO (usa LinphonePlayer en lugar de TTS/altavoz)
            serviceScope.launch {
                delay(800) // Esperar a que se establezca la llamada
                silentScreeningService?.startScreening(call, phoneNumber)
            }

        } catch (e: Exception) {
            Logger.e(TAG, "Error al auto-contestar llamada", e)
        }
    }
    
    /**
     * Genera la ruta para guardar la grabación de la llamada
     */
    private fun getCallRecordingPath(phoneNumber: String): String {
        val timestamp = System.currentTimeMillis()
        val sanitizedNumber = phoneNumber.replace(Regex("[^0-9+]"), "_")
        val fileName = "screening_${sanitizedNumber}_$timestamp.wav"
        val recordingsDir = java.io.File(filesDir, "recordings")
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs()
        }
        return java.io.File(recordingsDir, fileName).absolutePath
    }
    
    /**
     * Configura el audio de la llamada para screening SILENCIOSO:
     * - El usuario NO escucha nada (volumen del sistema a 0)
     * - El micrófono del dispositivo captura el TTS para enviarlo al llamante
     * - La llamada se graba con AMBOS lados del audio
     * 
     * IMPORTANTE: No usamos speakerMuted porque afecta la grabación.
     * En su lugar, controlamos el volumen del sistema.
     */
    private fun configureAudioForSilentScreening(call: Call) {
        try {
            val c = core ?: return
            
            // Buscar micrófono para entrada (para capturar TTS y enviarlo)
            val micDevice = c.audioDevices.find { 
                it.type == AudioDevice.Type.Microphone && 
                it.hasCapability(AudioDevice.Capabilities.CapabilityRecord)
            }
            
            // Para salida, usar earpiece (el volumen se controla externamente)
            val earpieceDevice = c.audioDevices.find { 
                it.type == AudioDevice.Type.Earpiece && 
                it.hasCapability(AudioDevice.Capabilities.CapabilityPlay)
            }
            
            // Configurar dispositivos de audio
            earpieceDevice?.let { 
                call.outputAudioDevice = it
                Logger.i(TAG, "Audio output: ${it.deviceName}")
            }
            
            micDevice?.let { 
                call.inputAudioDevice = it
                Logger.i(TAG, "Audio input: ${it.deviceName}")
            }
            
            // IMPORTANTE: NO usar speakerMuted = true porque afecta la grabación
            // En su lugar, silenciamos usando el volumen del sistema
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                // Guardar volumen actual para restaurarlo después
                val currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL)
                // Establecer volumen a 0 para que el usuario no escuche
                audioManager.setStreamVolume(
                    android.media.AudioManager.STREAM_VOICE_CALL, 
                    0, 
                    0 // Sin flags para no mostrar UI
                )
                Logger.i(TAG, "Volumen de llamada reducido a 0 (anterior: $currentVolume)")
            } catch (e: Exception) {
                Logger.w(TAG, "No se pudo ajustar volumen del sistema", e)
            }
            
            Logger.i(TAG, "Audio configurado para screening silencioso (grabación habilitada)")
            
        } catch (e: Exception) {
            Logger.e(TAG, "Error configurando audio silencioso", e)
        }
    }
    
    /**
     * Mutea/desmutea el micrófono de la llamada actual
     */
    fun setCallMicrophoneMuted(muted: Boolean) {
        currentScreeningCall?.let { call ->
            call.microphoneMuted = muted
            Logger.d(TAG, "Micrófono de llamada ${if (muted) "muteado" else "desmuteado"}")
        }
    }
    
    /**
     * Obtiene la ruta del archivo de grabación actual
     */
    fun getCurrentRecordingPath(): String? {
        return currentScreeningCall?.params?.recordFile
    }
    
    /**
     * Detiene la grabación de la llamada actual
     */
    fun stopCallRecording() {
        currentScreeningCall?.let { call ->
            call.stopRecording()
            Logger.i(TAG, "Grabación de llamada detenida")
        }
    }

    private fun handleScreeningCompleted(name: String?, purpose: String?, phoneNumber: String) {
        Logger.d(TAG, "Screening completado - Name: $name, Purpose: $purpose")
        
        // Detener grabación y obtener ruta del archivo
        val recordingPath = getCurrentRecordingPath()
        stopCallRecording()
        
        // Transcribir el audio grabado en background
        if (recordingPath != null) {
            serviceScope.launch {
                transcribeAndSaveRecording(recordingPath, phoneNumber, name, purpose)
            }
        }

        val intent = Intent(ScreeningOverlayActivity.ACTION_UPDATE_SCREENING).apply {
            putExtra(ScreeningOverlayActivity.EXTRA_CALLER_NAME, name)
            putExtra(ScreeningOverlayActivity.EXTRA_CALLER_PURPOSE, purpose)
            putExtra(ScreeningOverlayActivity.EXTRA_SCREENING_STATUS, true)
            putExtra("recording_path", recordingPath)
        }
        sendBroadcast(intent)
    }
    
    /**
     * Maneja la finalización del screening silencioso (estilo iOS)
     * Solo graba la llamada y transcribe después - sin TTS ni STT durante la llamada
     */
    private fun handleSilentScreeningCompleted(phoneNumber: String, recordingPath: String?) {
        Logger.d(TAG, "Silent screening completed for: $phoneNumber, recording: $recordingPath")
        
        // Detener grabación
        stopCallRecording()
        
        // Transcribir el audio grabado en background
        if (recordingPath != null) {
            serviceScope.launch {
                transcribeRecordingWithGemini(recordingPath, phoneNumber)
            }
        }

        // Actualizar overlay con estado completado
        val intent = Intent(ScreeningOverlayActivity.ACTION_UPDATE_SCREENING).apply {
            putExtra(ScreeningOverlayActivity.EXTRA_CALLER_NAME, "Llamada grabada")
            putExtra(ScreeningOverlayActivity.EXTRA_CALLER_PURPOSE, "Transcripción en proceso...")
            putExtra(ScreeningOverlayActivity.EXTRA_SCREENING_STATUS, true)
            putExtra("recording_path", recordingPath)
        }
        sendBroadcast(intent)
    }
    
    /**
     * Transcribe una grabación usando Gemini y guarda en la base de datos
     */
    private suspend fun transcribeRecordingWithGemini(recordingPath: String, phoneNumber: String) {
        try {
            Logger.i(TAG, "Transcribiendo grabación silenciosa: $recordingPath")
            
            // Transcribir el audio real usando Gemini
            val result = geminiService.transcribeAudio(recordingPath, phoneNumber)
            
            val transcriptionText = result.transcription
            val summaryText = result.summary
            val isSpam = result.isSpam
            val spamConfidence = result.spamConfidence
            
            Logger.i(TAG, "Transcripción completada: ${transcriptionText.take(100)}...")
            
            // Calcular duración del archivo de audio
            val duration = try {
                val file = java.io.File(recordingPath)
                if (file.exists()) {
                    (file.length() / 32000).toInt() // Aproximación WAV 16kHz mono 16bit
                } else 0
            } catch (e: Exception) { 0 }
            
            // Guardar en el historial
            val screeningId = screeningHistoryRepository.addScreening(
                phoneNumber = phoneNumber,
                callerName = null,
                callerPurpose = null,
                transcription = transcriptionText,
                summary = summaryText,
                recordingPath = recordingPath,
                duration = duration,
                wasAccepted = false,
                isSpam = isSpam,
                spamConfidence = spamConfidence
            )
            
            Logger.i(TAG, "Screening silencioso guardado con ID: $screeningId - Spam: $isSpam")
            
            // Actualizar UI con resultado
            val updateIntent = Intent(ScreeningOverlayActivity.ACTION_UPDATE_SCREENING).apply {
                putExtra(ScreeningOverlayActivity.EXTRA_CALLER_NAME, if (isSpam) "⚠️ Posible Spam" else "Llamada analizada")
                putExtra(ScreeningOverlayActivity.EXTRA_CALLER_PURPOSE, summaryText)
                putExtra(ScreeningOverlayActivity.EXTRA_SCREENING_STATUS, true)
            }
            sendBroadcast(updateIntent)
            
        } catch (e: Exception) {
            Logger.e(TAG, "Error transcribiendo grabación", e)
        }
    }
    
    /**
     * Transcribe el audio grabado y guarda en la base de datos
     */
    private suspend fun transcribeAndSaveRecording(
        recordingPath: String,
        phoneNumber: String,
        name: String?,
        purpose: String?
    ) {
        try {
            Logger.i(TAG, "Transcribiendo grabación: $recordingPath")
            
            // Transcribir el audio real usando Gemini
            val result = geminiService.transcribeAudio(recordingPath, phoneNumber)
            
            // Si la transcripción falla o está vacía, usar los datos del screening
            val transcriptionText = if (result.transcription.isNotBlank() && 
                !result.transcription.contains("no encontrado", ignoreCase = true) &&
                !result.transcription.contains("error", ignoreCase = true)) {
                result.transcription
            } else {
                "Llamada de ${name ?: "desconocido"}: ${purpose ?: "motivo no especificado"}"
            }
            
            val summaryText = if (result.summary.isNotBlank() && 
                !result.summary.contains("error", ignoreCase = true)) {
                result.summary
            } else {
                purpose ?: "Llamada entrante"
            }
            
            val isSpam = result.isSpam
            val spamConfidence = result.spamConfidence
            
            Logger.i(TAG, "Transcripción completada: ${transcriptionText.take(100)}...")
            
            // Calcular duración del archivo de audio (aproximado)
            val duration = try {
                val file = java.io.File(recordingPath)
                if (file.exists()) {
                    // WAV: tamaño / (sample_rate * channels * bits/8)
                    // Aproximación: 16kHz, mono, 16bit = 32000 bytes/segundo
                    (file.length() / 32000).toInt()
                } else 0
            } catch (e: Exception) { 0 }
            
            // Guardar en el historial de screenings
            val screeningId = screeningHistoryRepository.addScreening(
                phoneNumber = phoneNumber,
                callerName = name,
                callerPurpose = purpose,
                transcription = transcriptionText,
                summary = summaryText,
                recordingPath = recordingPath,
                duration = duration,
                wasAccepted = false,
                isSpam = isSpam,
                spamConfidence = spamConfidence
            )
            
            Logger.i(TAG, "Screening guardado con ID: $screeningId - Spam: $isSpam ($spamConfidence)")
            
            // Notificar que hay nueva transcripción
            val intent = Intent("com.luisspamdetector.TRANSCRIPTION_READY").apply {
                putExtra("screening_id", screeningId)
                putExtra("phone_number", phoneNumber)
                putExtra("transcription", transcriptionText)
                putExtra("summary", summaryText)
                putExtra("recording_path", recordingPath)
                putExtra("caller_name", name)
                putExtra("caller_purpose", purpose)
                putExtra("is_spam", isSpam)
                putExtra("spam_confidence", spamConfidence)
            }
            sendBroadcast(intent)
            
        } catch (e: Exception) {
            Logger.e(TAG, "Error transcribiendo grabación", e)
        }
    }

    private fun showSpamWarningScreen(
        phoneNumber: String, 
        isSpam: Boolean, 
        reason: String, 
        confidence: Double
    ) {
        val intent = Intent(this, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("phone_number", phoneNumber)
            putExtra("is_spam", isSpam)
            putExtra("reason", reason)
            putExtra("confidence", confidence)
        }
        startActivity(intent)
    }

    private fun showScreeningOverlay(phoneNumber: String) {
        val intent = Intent(this, ScreeningOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ScreeningOverlayActivity.EXTRA_PHONE_NUMBER, phoneNumber)
        }
        startActivity(intent)
    }

    private fun showSpamNotification(phoneNumber: String, isSpam: Boolean, reason: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_SCREENING_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (isSpam) "⚠️ Posible SPAM" else "✓ Número verificado")
            .setContentText("$phoneNumber - $reason")
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(phoneNumber.hashCode(), notification)
    }

    // region Notification Management

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Canal principal del servicio
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Servicio de Detección",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene el servicio de detección activo"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(serviceChannel)

            // Canal para alertas de spam
            val screeningChannel = NotificationChannel(
                CHANNEL_SCREENING_ID,
                "Alertas de SPAM",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de llamadas sospechosas"
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(screeningChannel)
        }
    }

    private fun startForegroundServiceCompat() {
        val notification = createServiceNotification("Iniciando...")
        
        // Usar ServiceCompat para compatibilidad con Android 14+
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
        )
    }

    private fun createServiceNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Detector de SPAM activo")
            .setContentText(status)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val notification = createServiceNotification(status)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // endregion

    // region Account Management (API moderna 5.4+)

    /**
     * Crea y registra una cuenta SIP usando la API moderna de Account
     */
    fun createAndRegisterAccount(
        username: String,
        password: String,
        domain: String,
        protocol: String = "UDP"
    ): Boolean {
        return try {
            val core = this.core ?: return false
            val factory = Factory.instance()

            // Convertir protocolo a TransportType
            val transport = when (protocol.uppercase()) {
                "TCP" -> TransportType.Tcp
                "TLS" -> TransportType.Tls
                else -> TransportType.Udp
            }
            
            // Parsear dominio y puerto (el dominio puede venir como "server.com:5800")
            val (host, port) = if (domain.contains(":")) {
                val parts = domain.split(":")
                Pair(parts[0], parts[1].toIntOrNull() ?: 5060)
            } else {
                Pair(domain, 5060)
            }
            
            Logger.d(TAG, "Configurando SIP - Host: $host, Puerto: $port, Transporte: $transport")

            // Crear AccountParams
            val accountParams = core.createAccountParams()

            // Configurar identidad (usar solo el host, sin puerto)
            val identity = factory.createAddress("sip:$username@$host")
            accountParams.identityAddress = identity

            // Configurar servidor con puerto explícito
            val serverAddress = factory.createAddress("sip:$host:$port")
            serverAddress?.transport = transport
            accountParams.serverAddress = serverAddress

            // Habilitar registro
            accountParams.isRegisterEnabled = true
            accountParams.publishExpires = 120
            
            // Configurar timeout de registro (en segundos)
            accountParams.expires = 600

            // Crear AuthInfo
            val authInfo = factory.createAuthInfo(
                username,   // username
                null,       // userid
                password,   // password
                null,       // ha1
                null,       // realm
                host        // domain (solo el host, sin puerto)
            )

            // Crear Account
            val account = core.createAccount(accountParams)

            // Agregar AuthInfo y Account al Core
            core.addAuthInfo(authInfo)
            core.addAccount(account)

            // Establecer como cuenta por defecto
            core.defaultAccount = account

            Logger.i(TAG, "Cuenta creada y registrando: $username@$host:$port con protocolo $protocol")
            true

        } catch (e: Exception) {
            Logger.e(TAG, "Error al crear cuenta", e)
            false
        }
    }

    /**
     * Elimina todas las cuentas registradas
     */
    fun clearAccounts() {
        core?.clearAccounts()
        core?.clearAllAuthInfo()
        Logger.i(TAG, "Todas las cuentas eliminadas")
    }

    /**
     * Configura la cuenta SIP automáticamente si hay credenciales guardadas
     */
    private fun configureSipAccountIfAvailable() {
        try {
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val sipConfigured = prefs.getBoolean("sip_configured", false)
            
            if (!sipConfigured) {
                Logger.i(TAG, "No hay configuración SIP guardada")
                return
            }
            
            val username = prefs.getString("sip_username", "") ?: ""
            val password = prefs.getString("sip_password", "") ?: ""
            val domain = prefs.getString("sip_domain", "") ?: ""
            val protocol = prefs.getString("sip_protocol", "UDP") ?: "UDP"
            
            if (username.isBlank() || password.isBlank() || domain.isBlank()) {
                Logger.w(TAG, "Configuración SIP incompleta")
                return
            }
            
            Logger.i(TAG, "Configurando cuenta SIP: $username@$domain con protocolo $protocol")
            val success = createAndRegisterAccount(username, password, domain, protocol)
            
            if (success) {
                Logger.i(TAG, "Cuenta SIP configurada exitosamente")
            } else {
                Logger.e(TAG, "Error al configurar cuenta SIP")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Excepción al configurar cuenta SIP", e)
        }
    }

    // endregion

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.d(TAG, "onStartCommand")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Logger.d(TAG, "LinphoneService onDestroy")

        isRunning = false

        // Cancelar coroutines
        serviceScope.cancel()

        // Desregistrar receiver solo si fue registrado exitosamente
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(callActionReceiver)
                isReceiverRegistered = false
                Logger.d(TAG, "Receiver desregistrado correctamente")
            } catch (e: Exception) {
                Logger.e(TAG, "Error al desregistrar receiver", e)
            }
        }

        // Limpiar call screening service
        callScreeningService?.shutdown()
        callScreeningService = null
        
        // Limpiar silent screening service
        silentScreeningService?.shutdown()
        silentScreeningService = null

        // Limpiar Linphone Core
        coreListener?.let { core?.removeListener(it) }
        core?.stop()
        core = null
        coreListener = null
    }
}
