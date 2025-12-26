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
    }

    private var core: Core? = null
    private var coreListener: CoreListenerStub? = null
    
    private lateinit var geminiService: GeminiApiService
    private lateinit var contactsHelper: ContactsHelper
    private lateinit var spamRepository: SpamRepository
    private var callScreeningService: CallScreeningService? = null
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentScreeningCall: Call? = null

    // Receiver para acciones de la UI de screening
    private val callActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ScreeningOverlayActivity.ACTION_ACCEPT_CALL -> {
                    callScreeningService?.acceptCall()
                }
                ScreeningOverlayActivity.ACTION_REJECT_CALL -> {
                    callScreeningService?.rejectCall()
                }
            }
        }
    }

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
        
        // Inicializar call screening si hay API key
        if (apiKey.isNotEmpty()) {
            callScreeningService = CallScreeningService(this, geminiService)
            callScreeningService?.initialize(object : CallScreeningService.ScreeningCallback {
                override fun onScreeningCompleted(name: String?, purpose: String?, phoneNumber: String) {
                    handleScreeningCompleted(name, purpose, phoneNumber)
                }

                override fun onScreeningFailed(reason: String) {
                    Logger.e(TAG, "Screening falló: $reason")
                }
            })
        }
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
            
            // Configuración de red
            setUserAgent("LinphoneSpamDetector", "2.0")
            
            // Habilitar IPv6 si está disponible
            isIpv6Enabled = true
            
            // Configuración de NAT
            natPolicy?.apply {
                isStunEnabled = true
                stunServer = "stun.linphone.org"
            }
            
            // Auto-iterate habilitado por defecto en SDK 5.4+
            // El SDK maneja automáticamente el iterate() en Android
            
            Logger.d(TAG, "Core configurado correctamente")
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
            }
            RegistrationState.Failed -> {
                Logger.e(TAG, "Registro fallido: $message")
                updateNotification("Error de conexión")
            }
            RegistrationState.Progress -> {
                Logger.i(TAG, "Registrando...")
                updateNotification("Conectando...")
            }
            RegistrationState.Cleared -> {
                Logger.i(TAG, "Registro limpiado")
                updateNotification("Desconectado")
            }
            RegistrationState.None -> {
                Logger.i(TAG, "Sin registro")
            }
            RegistrationState.Refreshing -> {
                Logger.i(TAG, "Refrescando registro...")
            }
        }
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
                // 1. Verificar si está en contactos
                if (contactsHelper.isNumberInContacts(phoneNumber)) {
                    val name = contactsHelper.getContactName(phoneNumber)
                    Logger.d(TAG, "Número en contactos: $name - ignorando")
                    return@launch
                }

                // 2. Obtener configuración
                val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                val callScreeningEnabled = prefs.getBoolean("call_screening_enabled", false)
                val spamDetectionEnabled = prefs.getBoolean("spam_detection_enabled", true)

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

        try {
            // Aceptar la llamada con parámetros
            val callParams = core?.createCallParams(call)
            callParams?.apply {
                isAudioEnabled = true
                isVideoEnabled = false
            }
            
            call.acceptWithParams(callParams)
            Logger.d(TAG, "Llamada auto-contestada para screening")

            // Mostrar overlay de screening
            showScreeningOverlay(phoneNumber)

            // Iniciar proceso de screening
            serviceScope.launch {
                delay(500) // Esperar a que se establezca
                callScreeningService?.startScreening(call, phoneNumber)
            }

        } catch (e: Exception) {
            Logger.e(TAG, "Error al auto-contestar llamada", e)
        }
    }

    private fun handleScreeningCompleted(name: String?, purpose: String?, phoneNumber: String) {
        Logger.d(TAG, "Screening completado - Name: $name, Purpose: $purpose")

        val intent = Intent(ScreeningOverlayActivity.ACTION_UPDATE_SCREENING).apply {
            putExtra(ScreeningOverlayActivity.EXTRA_CALLER_NAME, name)
            putExtra(ScreeningOverlayActivity.EXTRA_CALLER_PURPOSE, purpose)
            putExtra(ScreeningOverlayActivity.EXTRA_SCREENING_STATUS, true)
        }
        sendBroadcast(intent)
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
        transport: TransportType = TransportType.Tls
    ): Boolean {
        return try {
            val core = this.core ?: return false
            val factory = Factory.instance()

            // Crear AccountParams
            val accountParams = core.createAccountParams()

            // Configurar identidad
            val identity = factory.createAddress("sip:$username@$domain")
            accountParams.identityAddress = identity

            // Configurar servidor
            val serverAddress = factory.createAddress("sip:$domain")
            serverAddress?.transport = transport
            accountParams.serverAddress = serverAddress

            // Habilitar registro
            accountParams.isRegisterEnabled = true
            accountParams.publishExpires = 120

            // Crear AuthInfo
            val authInfo = factory.createAuthInfo(
                username,   // username
                null,       // userid
                password,   // password
                null,       // ha1
                null,       // realm
                domain      // domain
            )

            // Crear Account
            val account = core.createAccount(accountParams)

            // Agregar AuthInfo y Account al Core
            core.addAuthInfo(authInfo)
            core.addAccount(account)

            // Establecer como cuenta por defecto
            core.defaultAccount = account

            Logger.i(TAG, "Cuenta creada y registrando: $username@$domain")
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
            
            if (username.isBlank() || password.isBlank() || domain.isBlank()) {
                Logger.w(TAG, "Configuración SIP incompleta")
                return
            }
            
            Logger.i(TAG, "Configurando cuenta SIP: $username@$domain")
            val success = createAndRegisterAccount(username, password, domain)
            
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

        // Desregistrar receiver
        try {
            unregisterReceiver(callActionReceiver)
        } catch (e: Exception) {
            Logger.e(TAG, "Error unregistering receiver", e)
        }

        // Limpiar call screening service
        callScreeningService?.shutdown()
        callScreeningService = null

        // Limpiar Linphone Core
        coreListener?.let { core?.removeListener(it) }
        core?.stop()
        core = null
        coreListener = null
    }
}
