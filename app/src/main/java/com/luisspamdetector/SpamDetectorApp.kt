package com.luisspamdetector

import android.app.Application
import com.luisspamdetector.util.Logger
import org.linphone.core.Factory
import org.linphone.core.LogCollectionState
import org.linphone.core.LogLevel

/**
 * Clase Application para inicialización global
 */
class SpamDetectorApp : Application() {

    companion object {
        private const val TAG = "SpamDetectorApp"
    }

    override fun onCreate() {
        super.onCreate()
        
        // Inicializar sistema de logging
        Logger.initialize(this)
        Logger.d(TAG, "Application onCreate")

        initializeLinphoneFactory()
    }

    private fun initializeLinphoneFactory() {
        try {
            // Inicializar el Factory de Linphone
            val factory = Factory.instance()
            
            // Configurar logging
            factory.setDebugMode(BuildConfig.DEBUG, "LinphoneSpam")
            factory.enableLogCollection(LogCollectionState.Enabled)
            factory.setLogCollectionPath(filesDir.absolutePath)
            
            // Configurar nivel de log
            factory.loggingService.setLogLevel(
                if (BuildConfig.DEBUG) LogLevel.Debug else LogLevel.Warning
            )

            Logger.i(TAG, "Linphone Factory initialized successfully")
            
        } catch (e: Exception) {
            Logger.e(TAG, "Error initializing Linphone Factory", e)
        }
    }
}
