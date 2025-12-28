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
import com.luisspamdetector.ui.CallControlActivity
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
        
        // Referencia al servicio para acceso externo
        var instance: LinphoneService? = null
            private set
        
        // Acción broadcast para notificar cambio de estado de registro
        const val ACTION_REGISTRATION_STATE_CHANGED = "com.luisspamdetector.REGISTRATION_STATE_CHANGED"
        const val EXTRA_IS_REGISTERED = "is_registered"
        
        // Acciones para solicitar operaciones desde la UI
        const val ACTION_REFRESH_REGISTRATION = "com.luisspamdetector.REFRESH_REGISTRATION"
        const val ACTION_GET_REGISTRATION_STATE = "com.luisspamdetector.GET_REGISTRATION_STATE"
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
    
    // Datos para registrar la llamada en el Call Log del sistema
    private var currentCallStartTime: Long = 0L
    private var currentCallPhoneNumber: String? = null
    private var currentCallContactName: String? = null
    private var currentCallWasAnswered: Boolean = false

    // Receiver para acciones de la UI de screening
    private val callActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ScreeningOverlayActivity.ACTION_ACCEPT_CALL -> {
                    Logger.i(TAG, "Usuario aceptó la llamada manualmente")
                    
                    // Detener el screening silencioso
                    silentScreeningService?.stopScreening()
                    callScreeningService?.acceptCall()
                    
                    // Cuando el usuario acepta, grabar AMBOS lados de la conversación
                    currentScreeningCall?.let { call ->
                        handleUserAcceptedCall(call)
                    }
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
                
                // Acciones de CallControlActivity (modo softphone normal)
                CallControlActivity.ACTION_ANSWER_CALL -> {
                    Logger.i(TAG, "Contestando llamada desde CallControlActivity")
                    currentScreeningCall?.let { call ->
                        if (call.state == Call.State.IncomingReceived) {
                            call.accept()
                            Logger.i(TAG, "Llamada aceptada")
                            // Iniciar grabación de ambos lados
                            startFullCallRecording(call)
                        }
                    }
                }
                CallControlActivity.ACTION_HANGUP_CALL -> {
                    Logger.i(TAG, "Terminando llamada desde CallControlActivity")
                    currentScreeningCall?.let { call ->
                        // Detener grabación si existe
                        stopCurrentRecording(call)
                        if (call.state != Call.State.End && call.state != Call.State.Released) {
                            call.terminate()
                            Logger.i(TAG, "Llamada terminada")
                        }
                    }
                    currentScreeningCall = null
                }
                CallControlActivity.ACTION_TOGGLE_RECORDING -> {
                    Logger.i(TAG, "Toggle grabación desde CallControlActivity")
                    currentScreeningCall?.let { call ->
                        toggleCallRecording(call)
                    }
                }
                CallControlActivity.ACTION_TOGGLE_MUTE -> {
                    Logger.i(TAG, "Toggle mute desde CallControlActivity")
                    currentScreeningCall?.let { call ->
                        call.microphoneMuted = !call.microphoneMuted
                        Logger.i(TAG, "Micrófono muted: ${call.microphoneMuted}")
                    }
                }
                CallControlActivity.ACTION_TOGGLE_SPEAKER -> {
                    Logger.i(TAG, "Toggle speaker desde CallControlActivity")
                    core?.let { c ->
                        // Toggle speaker usando AudioDevice
                        val currentDevice = c.outputAudioDevice
                        val speakerDevice = c.audioDevices.find { 
                            it.type == AudioDevice.Type.Speaker 
                        }
                        val earpieceDevice = c.audioDevices.find { 
                            it.type == AudioDevice.Type.Earpiece 
                        }
                        
                        if (currentDevice?.type == AudioDevice.Type.Speaker) {
                            earpieceDevice?.let { c.outputAudioDevice = it }
                            Logger.i(TAG, "Cambiado a auricular")
                        } else {
                            speakerDevice?.let { c.outputAudioDevice = it }
                            Logger.i(TAG, "Cambiado a altavoz")
                        }
                    }
                }
            }
        }
    }

    private var isReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        Logger.d(TAG, "LinphoneService onCreate")
        
        // Guardar referencia para acceso externo
        instance = this

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
            // Acciones de CallControlActivity
            addAction(CallControlActivity.ACTION_ANSWER_CALL)
            addAction(CallControlActivity.ACTION_HANGUP_CALL)
            addAction(CallControlActivity.ACTION_TOGGLE_RECORDING)
            addAction(CallControlActivity.ACTION_TOGGLE_MUTE)
            addAction(CallControlActivity.ACTION_TOGGLE_SPEAKER)
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
                Logger.i(TAG, "✓ Cuenta registrada: ${account.params?.identityAddress?.asString()}")
                updateNotification("Conectado: ${account.params?.identityAddress?.username}")
                broadcastRegistrationState(true)
            }
            RegistrationState.Failed -> {
                Logger.e(TAG, "✗ Registro fallido: $message")
                Logger.e(TAG, "  - Server: ${account.params?.serverAddress?.asString()}")
                Logger.e(TAG, "  - Identity: ${account.params?.identityAddress?.asString()}")
                Logger.e(TAG, "  - Transport: ${account.params?.serverAddress?.transport}")
                
                // Diagnóstico adicional
                val errorInfo = account.errorInfo
                if (errorInfo != null) {
                    Logger.e(TAG, "  - Error Code: ${errorInfo.protocolCode}")
                    Logger.e(TAG, "  - Error Phrase: ${errorInfo.phrase}")
                    Logger.e(TAG, "  - Error Reason: ${errorInfo.reason}")
                }
                
                updateNotification("Error: $message")
                broadcastRegistrationState(false)
            }
            RegistrationState.Progress -> {
                Logger.i(TAG, "⋯ Registrando...")
                Logger.d(TAG, "  - Server: ${account.params?.serverAddress?.asString()}")
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
                
                // Inicializar datos para el Call Log
                currentCallStartTime = System.currentTimeMillis()
                currentCallPhoneNumber = phoneNumber
                currentCallWasAnswered = false
                
                handleIncomingCall(call, phoneNumber)
            }
            Call.State.Connected,
            Call.State.StreamsRunning -> {
                Logger.i(TAG, "Llamada conectada")
                currentCallWasAnswered = true
                // Actualizar tiempo de inicio cuando se conecta realmente
                if (state == Call.State.Connected) {
                    currentCallStartTime = System.currentTimeMillis()
                }
            }
            Call.State.End, 
            Call.State.Released -> {
                Logger.i(TAG, "Llamada terminada")
                
                // Calcular duración y registrar en Call Log del sistema
                currentCallPhoneNumber?.let { phoneNumber ->
                    val duration = if (currentCallWasAnswered) {
                        (System.currentTimeMillis() - currentCallStartTime) / 1000
                    } else {
                        0L
                    }
                    logIncomingCall(phoneNumber, currentCallWasAnswered, duration, currentCallContactName)
                }
                
                // Limpiar datos de la llamada
                currentScreeningCall = null
                currentCallPhoneNumber = null
                currentCallContactName = null
                currentCallWasAnswered = false
            }
            Call.State.Error -> {
                Logger.e(TAG, "Error en llamada: $message")
                
                // Registrar como llamada perdida
                currentCallPhoneNumber?.let { phoneNumber ->
                    logIncomingCall(phoneNumber, wasAnswered = false, duration = 0L, currentCallContactName)
                }
                
                currentScreeningCall = null
                currentCallPhoneNumber = null
                currentCallContactName = null
            }
            else -> {
                Logger.d(TAG, "Estado de llamada: $state")
            }
        }
    }

    private fun handleIncomingCall(call: Call, phoneNumber: String) {
        Logger.i(TAG, "=== handleIncomingCall iniciado ===")
        Logger.i(TAG, "Número: $phoneNumber, Estado de llamada: ${call.state}")
        
        // Guardar la llamada actual
        currentScreeningCall = call
        
        serviceScope.launch {
            try {
                // 1. Obtener configuración
                val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                val callScreeningEnabled = prefs.getBoolean("call_screening_enabled", false)
                val spamDetectionEnabled = prefs.getBoolean("spam_detection_enabled", true)
                val testModeEnabled = prefs.getBoolean("test_mode_enabled", false)
                
                Logger.i(TAG, "Configuración: screening=$callScreeningEnabled, spam=$spamDetectionEnabled, test=$testModeEnabled")

                // 2. Verificar si está en contactos
                var isContact = false
                var contactName: String? = null
                if (!testModeEnabled) {
                    try {
                        if (contactsHelper.isNumberInContacts(phoneNumber)) {
                            contactName = contactsHelper.getContactName(phoneNumber)
                            isContact = true
                            Logger.d(TAG, "Número en contactos: $contactName")
                        }
                    } catch (e: Exception) {
                        Logger.w(TAG, "Error verificando contactos", e)
                    }
                }
                
                // Guardar nombre del contacto para el Call Log
                currentCallContactName = contactName

                if (testModeEnabled) {
                    Logger.i(TAG, "MODO PRUEBA: Procesando llamada de $phoneNumber")
                }

                // 3. Procesar según configuración
                when {
                    // Si es un contacto conocido, mostrar pantalla de llamada normal
                    isContact -> {
                        Logger.i(TAG, ">>> Contacto conocido, mostrando pantalla de llamada")
                        showIncomingCallScreen(call, phoneNumber, contactName, isSpam = false)
                    }
                    // Call screening automático (auto-descuelga)
                    callScreeningEnabled -> {
                        Logger.i(TAG, ">>> Iniciando call screening para $phoneNumber")
                        handleCallScreening(call, phoneNumber)
                    }
                    // Solo detección de spam (muestra alerta pero no auto-descuelga)
                    spamDetectionEnabled -> {
                        Logger.i(TAG, ">>> Iniciando detección de spam para $phoneNumber")
                        // Mostrar pantalla de llamada mientras se verifica spam
                        showIncomingCallScreen(call, phoneNumber, null, isSpam = false)
                        // Verificar spam en paralelo
                        handleSpamDetection(phoneNumber)
                    }
                    // Ningún modo activo - funcionar como softphone normal
                    else -> {
                        Logger.i(TAG, ">>> Modo softphone normal")
                        showIncomingCallScreen(call, phoneNumber, null, isSpam = false)
                    }
                }

            } catch (e: Exception) {
                Logger.e(TAG, "Error al procesar llamada entrante", e)
            }
        }
    }
    
    /**
     * Muestra la pantalla de llamada entrante para que el usuario pueda aceptar/rechazar
     */
    private fun showIncomingCallScreen(call: Call, phoneNumber: String, contactName: String?, isSpam: Boolean) {
        Logger.i(TAG, "Mostrando pantalla de llamada entrante: $phoneNumber (contacto: $contactName, spam: $isSpam)")
        
        val intent = Intent(this, CallControlActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(CallControlActivity.EXTRA_PHONE_NUMBER, phoneNumber)
            putExtra(CallControlActivity.EXTRA_DISPLAY_NAME, contactName)
            putExtra(CallControlActivity.EXTRA_IS_SPAM, isSpam)
        }
        startActivity(intent)
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
        Logger.i(TAG, "=== handleCallScreening iniciado ===")
        Logger.i(TAG, "Número: $phoneNumber, Estado: ${call.state}")
        
        currentScreeningCall = call
        val recordingPath = getCallRecordingPath(phoneNumber)

        try {
            // Configurar parámetros para la llamada
            val callParams = core?.createCallParams(call)
            if (callParams == null) {
                Logger.e(TAG, "Error: No se pudieron crear CallParams")
                return
            }
            
            callParams.apply {
                isAudioEnabled = true
                isVideoEnabled = false
                // Configurar archivo de grabación para después
                // NO iniciamos grabación aquí, la iniciaremos después del TTS
                recordFile = recordingPath
            }
            
            Logger.i(TAG, "CallParams configurados, intentando aceptar llamada...")
            
            // Aceptar la llamada
            val result = call.acceptWithParams(callParams)
            Logger.i(TAG, "Resultado de acceptWithParams: $result")
            Logger.i(TAG, "Llamada auto-contestada para screening silencioso")

            // Configurar audio y preparar grabación
            serviceScope.launch {
                // Esperar a que la llamada esté en estado StreamsRunning
                var attempts = 0
                while (call.state != Call.State.StreamsRunning && attempts < 30) {
                    delay(100)
                    attempts++
                }
                
                Logger.i(TAG, "Estado de llamada después de espera ($attempts intentos): ${call.state}")
                
                // Configurar audio silencioso para el usuario
                configureAudioForSilentScreening(call)
                
                // IMPORTANTE: NO iniciamos la grabación aquí
                // La grabación se iniciará cuando termine el TTS en SilentCallScreeningService
                // Esto es para grabar solo la respuesta del interlocutor, no el TTS
                Logger.i(TAG, "Audio configurado. Grabación se iniciará después del TTS.")
                
                // Guardar la ruta de grabación para usarla después
                core?.recordFile = recordingPath
            }

            // Mostrar overlay de screening (modo silencioso)
            showScreeningOverlay(phoneNumber)

            // Iniciar proceso de screening SILENCIOSO
            serviceScope.launch {
                delay(500) // Esperar a que se establezca la llamada
                Logger.i(TAG, "Iniciando SilentCallScreeningService...")
                silentScreeningService?.startScreening(call, phoneNumber)
            }

        } catch (e: Exception) {
            Logger.e(TAG, "Error al auto-contestar llamada", e)
        }
    }
    
    /**
     * Maneja cuando el usuario acepta manualmente la llamada durante el screening.
     * En este caso, grabamos AMBOS lados de la conversación (micrófono + interlocutor).
     */
    private fun handleUserAcceptedCall(call: Call) {
        Logger.i(TAG, "=== Usuario aceptó llamada, iniciando grabación de ambos lados ===")
        
        val phoneNumber = call.remoteAddress?.asStringUriOnly() ?: "unknown"
        val recordingPath = getCallRecordingPath(phoneNumber).replace("screening_", "call_")
        
        try {
            // Desmutear el micrófono para que se grabe nuestra voz también
            call.microphoneMuted = false
            Logger.i(TAG, "Micrófono desmuteado para grabación completa")
            
            // Restaurar el volumen de la llamada para que el usuario escuche
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL)
                audioManager.setStreamVolume(
                    android.media.AudioManager.STREAM_VOICE_CALL,
                    maxVolume / 2, // Volumen medio
                    0
                )
                Logger.i(TAG, "Volumen de llamada restaurado")
            } catch (e: Exception) {
                Logger.w(TAG, "Error restaurando volumen", e)
            }
            
            // Configurar y comenzar grabación de ambos lados
            val callParams = core?.createCallParams(call)
            callParams?.recordFile = recordingPath
            
            // Actualizar parámetros de la llamada
            call.update(callParams)
            
            // Iniciar grabación
            serviceScope.launch {
                delay(200) // Pequeña espera para que se apliquen los cambios
                call.startRecording()
                Logger.i(TAG, "✓ Grabación de llamada completa iniciada: $recordingPath")
                Logger.i(TAG, "  - Grabando: micrófono + interlocutor")
            }
            
        } catch (e: Exception) {
            Logger.e(TAG, "Error iniciando grabación de llamada aceptada", e)
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

    /**
     * Inicia grabación completa de la llamada (ambos lados del audio)
     * Para cuando el usuario contesta manualmente desde la UI de softphone
     */
    private fun startFullCallRecording(call: Call) {
        try {
            val remoteAddress = call.remoteAddress?.asStringUriOnly() ?: "unknown"
            val recordingPath = getCallRecordingPath(remoteAddress)
            
            Logger.i(TAG, "Iniciando grabación completa de llamada: $recordingPath")
            
            // Configurar params para grabación
            val callParams = core?.createCallParams(call)
            callParams?.recordFile = recordingPath
            
            // Asegurar que el micrófono NO esté muted para grabar ambos lados
            call.microphoneMuted = false
            
            // Actualizar parámetros
            call.update(callParams)
            
            // Iniciar grabación con pequeña espera
            serviceScope.launch {
                delay(200)
                call.startRecording()
                Logger.i(TAG, "✓ Grabación completa iniciada")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error iniciando grabación completa", e)
        }
    }
    
    /**
     * Detiene la grabación de una llamada específica
     */
    private fun stopCurrentRecording(call: Call) {
        try {
            val recordingPath = call.params?.recordFile
            if (recordingPath != null) {
                call.stopRecording()
                Logger.i(TAG, "Grabación detenida: $recordingPath")
                
                // Guardar en base de datos
                val phoneNumber = call.remoteAddress?.asStringUriOnly() ?: "unknown"
                serviceScope.launch {
                    saveCallRecordingToDatabase(phoneNumber, recordingPath)
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error deteniendo grabación", e)
        }
    }
    
    /**
     * Toggle grabación (iniciar/detener)
     */
    private fun toggleCallRecording(call: Call) {
        val isRecording = call.params?.recordFile != null && 
                          java.io.File(call.params?.recordFile ?: "").exists()
        
        if (call.isRecording) {
            call.stopRecording()
            Logger.i(TAG, "Grabación pausada")
        } else {
            val recordingPath = call.params?.recordFile
            if (recordingPath != null) {
                call.startRecording()
                Logger.i(TAG, "Grabación reanudada")
            } else {
                // No hay grabación configurada, iniciar una nueva
                startFullCallRecording(call)
            }
        }
    }
    
    /**
     * Guarda la grabación en la base de datos
     */
    private suspend fun saveCallRecordingToDatabase(phoneNumber: String, recordingPath: String) {
        try {
            val file = java.io.File(recordingPath)
            if (file.exists()) {
                val database = SpamDatabase.getDatabase(this@LinphoneService)
                // Limpiar el número antes de guardar
                val cleanNumber = extractPhoneNumber(phoneNumber)
                val recording = com.luisspamdetector.data.CallRecordingEntity(
                    phoneNumber = cleanNumber,
                    displayName = null,
                    isIncoming = true,
                    duration = 0L, // Se puede calcular del archivo WAV
                    recordingPath = recordingPath,
                    recordingSize = file.length(),
                    recordingFormat = "wav",
                    transcription = null,
                    summary = null,
                    callerName = null,
                    purpose = null,
                    keyPoints = null,
                    isSpam = false,
                    spamConfidence = 0.0,
                    urgency = "LOW",
                    sentiment = "NEUTRAL",
                    wasAnswered = true,
                    wasRecordedBothSides = true,
                    transcriptionStatus = "PENDING",
                    timestamp = System.currentTimeMillis()
                )
                database.callRecordingDao().insert(recording)
                Logger.i(TAG, "Grabación guardada en base de datos: $cleanNumber -> $recordingPath")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error guardando grabación en base de datos", e)
        }
    }
    
    /**
     * Extrae solo el número de teléfono de una URI SIP
     * Ejemplos:
     * - "sip:913688160@161.22.43.99" -> "913688160"
     * - "sip:+34913688160@domain.com" -> "+34913688160"
     * - "913688160" -> "913688160"
     */
    private fun extractPhoneNumber(sipUri: String): String {
        var number = sipUri
        
        // Quitar prefijo "sip:"
        if (number.startsWith("sip:", ignoreCase = true)) {
            number = number.substring(4)
        }
        
        // Quitar todo después de "@"
        val atIndex = number.indexOf('@')
        if (atIndex > 0) {
            number = number.substring(0, atIndex)
        }
        
        // Quitar parámetros después de ";"
        val semicolonIndex = number.indexOf(';')
        if (semicolonIndex > 0) {
            number = number.substring(0, semicolonIndex)
        }
        
        return number.trim()
    }
    
    /**
     * Registra la llamada en el Call Log del sistema Android para que aparezca
     * en el historial de llamadas del teléfono y se pueda rellamar
     */
    private fun addCallToSystemCallLog(
        phoneNumber: String,
        callType: Int, // CallLog.Calls.INCOMING_TYPE, OUTGOING_TYPE, MISSED_TYPE
        duration: Long, // duración en segundos
        contactName: String? = null
    ) {
        try {
            // Verificar permiso
            if (checkSelfPermission(android.Manifest.permission.WRITE_CALL_LOG) 
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Logger.w(TAG, "No hay permiso WRITE_CALL_LOG, no se puede registrar la llamada")
                return
            }
            
            // Extraer solo el número, sin sip: ni @dominio
            val cleanNumber = extractPhoneNumber(phoneNumber)
            Logger.d(TAG, "Número limpio para Call Log: $cleanNumber (original: $phoneNumber)")
            
            val values = android.content.ContentValues().apply {
                put(android.provider.CallLog.Calls.NUMBER, cleanNumber)
                put(android.provider.CallLog.Calls.TYPE, callType)
                put(android.provider.CallLog.Calls.DATE, System.currentTimeMillis())
                put(android.provider.CallLog.Calls.DURATION, duration)
                put(android.provider.CallLog.Calls.NEW, 1)
                
                // Agregar nombre del contacto si está disponible
                if (contactName != null) {
                    put(android.provider.CallLog.Calls.CACHED_NAME, contactName)
                }
                
                // Indicar que es una llamada VoIP
                put(android.provider.CallLog.Calls.PHONE_ACCOUNT_ID, "linphone_sip")
            }
            
            contentResolver.insert(android.provider.CallLog.Calls.CONTENT_URI, values)
            Logger.i(TAG, "Llamada registrada en Call Log del sistema: $cleanNumber (tipo: $callType, duración: ${duration}s)")
            
        } catch (e: Exception) {
            Logger.e(TAG, "Error registrando llamada en Call Log del sistema", e)
        }
    }
    
    /**
     * Registra una llamada entrante en el Call Log
     */
    private fun logIncomingCall(phoneNumber: String, wasAnswered: Boolean, duration: Long, contactName: String? = null) {
        val callType = if (wasAnswered) {
            android.provider.CallLog.Calls.INCOMING_TYPE
        } else {
            android.provider.CallLog.Calls.MISSED_TYPE
        }
        addCallToSystemCallLog(phoneNumber, callType, duration, contactName)
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
     * IMPORTANTE: Limpia cuentas anteriores para evitar doble registro
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

            // IMPORTANTE: Limpiar cuentas anteriores para evitar doble registro
            val existingAccounts = core.accountList.size
            if (existingAccounts > 0) {
                Logger.i(TAG, "Limpiando $existingAccounts cuenta(s) existente(s) antes de registrar nueva")
                clearAccounts()
            }

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
            if (identity == null) {
                Logger.e(TAG, "Error creando dirección de identidad")
                return false
            }
            accountParams.identityAddress = identity

            // Configurar servidor con puerto explícito
            val serverAddress = factory.createAddress("sip:$host:$port")
            if (serverAddress == null) {
                Logger.e(TAG, "Error creando dirección del servidor")
                return false
            }
            serverAddress.transport = transport
            accountParams.serverAddress = serverAddress

            // Habilitar registro
            accountParams.isRegisterEnabled = true
            accountParams.publishExpires = 120
            
            // Configurar timeout de registro (en segundos)
            accountParams.expires = 300
            
            // Configurar NAT policy para la cuenta
            val natPolicy = core.createNatPolicy()
            natPolicy.isStunEnabled = true
            natPolicy.stunServer = "stun.linphone.org"
            natPolicy.isIceEnabled = true
            natPolicy.isTurnEnabled = false
            accountParams.natPolicy = natPolicy
            Logger.d(TAG, "NAT Policy configurada con STUN: stun.linphone.org, ICE habilitado")

            // Crear AuthInfo con realm vacío para auto-detectar
            val authInfo = factory.createAuthInfo(
                username,   // username
                null,       // userid (null para usar username)
                password,   // password
                null,       // ha1
                "",         // realm vacío para auto-detectar
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
            Logger.d(TAG, "  - Identity: ${identity.asString()}")
            Logger.d(TAG, "  - Server: ${serverAddress.asString()}")
            Logger.d(TAG, "  - Transport: $transport")
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
        broadcastRegistrationState(false)
    }

    /**
     * Refresca el registro SIP de la cuenta actual
     */
    fun refreshRegistration() {
        try {
            val account = core?.defaultAccount
            if (account != null) {
                Logger.i(TAG, "Refrescando registro SIP...")
                account.refreshRegister()
            } else {
                Logger.w(TAG, "No hay cuenta SIP configurada para refrescar")
                broadcastRegistrationState(false)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error al refrescar registro", e)
        }
    }

    /**
     * Obtiene el estado actual del registro SIP
     */
    fun getRegistrationState(): RegistrationState {
        return core?.defaultAccount?.state ?: RegistrationState.None
    }

    /**
     * Devuelve true si la cuenta SIP está registrada correctamente
     */
    fun isRegistered(): Boolean {
        return core?.defaultAccount?.state == RegistrationState.Ok
    }

    /**
     * Fuerza el reenvío del estado de registro actual via broadcast
     */
    fun broadcastCurrentRegistrationState() {
        val isRegistered = isRegistered()
        broadcastRegistrationState(isRegistered)
        Logger.d(TAG, "Estado de registro actual enviado: $isRegistered")
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
    
    // region Métodos públicos para control de llamadas desde UI
    
    /**
     * Contesta la llamada entrante actual e inicia grabación automática
     */
    fun answerCurrentCall() {
        Logger.i(TAG, "answerCurrentCall() llamado")
        currentScreeningCall?.let { call ->
            if (call.state == Call.State.IncomingReceived || 
                call.state == Call.State.IncomingEarlyMedia) {
                try {
                    // Preparar parámetros con configuración de grabación
                    val phoneNumber = call.remoteAddress?.asStringUriOnly() ?: "unknown"
                    val recordingPath = getCallRecordingPath(phoneNumber)
                    
                    val callParams = core?.createCallParams(call)
                    callParams?.apply {
                        isAudioEnabled = true
                        isVideoEnabled = false
                        recordFile = recordingPath
                    }
                    
                    Logger.i(TAG, "Aceptando llamada con grabación automática: $recordingPath")
                    
                    // Aceptar la llamada con los parámetros
                    val result = call.acceptWithParams(callParams)
                    Logger.i(TAG, "Resultado de acceptWithParams: $result")
                    
                    // Iniciar grabación automáticamente después de un pequeño delay
                    serviceScope.launch {
                        delay(500) // Esperar a que se establezca el audio
                        try {
                            call.startRecording()
                            Logger.i(TAG, "✓ Grabación automática iniciada")
                        } catch (e: Exception) {
                            Logger.e(TAG, "Error iniciando grabación automática", e)
                        }
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "Error aceptando llamada", e)
                }
            } else {
                Logger.w(TAG, "La llamada no está en estado IncomingReceived: ${call.state}")
            }
        } ?: run {
            Logger.e(TAG, "No hay llamada activa para contestar")
        }
    }
    
    /**
     * Cuelga la llamada actual
     */
    fun hangupCurrentCall() {
        Logger.i(TAG, "hangupCurrentCall() llamado")
        currentScreeningCall?.let { call ->
            try {
                // Detener grabación si existe
                stopCurrentRecording(call)
                
                if (call.state != Call.State.End && call.state != Call.State.Released) {
                    call.terminate()
                    Logger.i(TAG, "Llamada terminada exitosamente")
                } else {
                    Logger.d(TAG, "La llamada ya estaba terminada: ${call.state}")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Error terminando llamada", e)
            }
            currentScreeningCall = null
        } ?: run {
            Logger.w(TAG, "No hay llamada activa para colgar")
        }
    }
    
    /**
     * Alterna la grabación de la llamada actual
     */
    fun toggleCurrentCallRecording() {
        Logger.i(TAG, "toggleCurrentCallRecording() llamado")
        currentScreeningCall?.let { call ->
            toggleCallRecording(call)
        } ?: run {
            Logger.w(TAG, "No hay llamada activa para toggle recording")
        }
    }
    
    /**
     * Alterna el mute del micrófono de la llamada actual
     */
    fun toggleCurrentCallMute() {
        Logger.i(TAG, "toggleCurrentCallMute() llamado")
        currentScreeningCall?.let { call ->
            call.microphoneMuted = !call.microphoneMuted
            Logger.i(TAG, "Micrófono muted: ${call.microphoneMuted}")
        } ?: run {
            Logger.w(TAG, "No hay llamada activa para toggle mute")
        }
    }
    
    /**
     * Alterna el altavoz
     */
    fun toggleSpeaker() {
        Logger.i(TAG, "toggleSpeaker() llamado")
        core?.let { c ->
            val currentDevice = c.outputAudioDevice
            val speakerDevice = c.audioDevices.find { 
                it.type == AudioDevice.Type.Speaker 
            }
            val earpieceDevice = c.audioDevices.find { 
                it.type == AudioDevice.Type.Earpiece 
            }
            
            if (currentDevice?.type == AudioDevice.Type.Speaker) {
                earpieceDevice?.let { 
                    c.outputAudioDevice = it 
                    Logger.i(TAG, "Cambiado a auricular")
                }
            } else {
                speakerDevice?.let { 
                    c.outputAudioDevice = it 
                    Logger.i(TAG, "Cambiado a altavoz")
                }
            }
        } ?: run {
            Logger.w(TAG, "Core no disponible para toggle speaker")
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
        
        // Limpiar referencia de instancia
        instance = null
    }
}
