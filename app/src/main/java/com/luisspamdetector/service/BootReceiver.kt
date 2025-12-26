package com.luisspamdetector.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Receiver que inicia el servicio de Linphone cuando el dispositivo se reinicia.
 * Compatible con Android 15 (API 35)
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            Log.d(TAG, "Boot completed - verificando si debe iniciar servicio")

            // Verificar si el servicio estaba habilitado
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val serviceEnabled = prefs.getBoolean("service_enabled", false)
            val apiKey = prefs.getString("gemini_api_key", "") ?: ""

            if (serviceEnabled && apiKey.isNotEmpty()) {
                Log.d(TAG, "Iniciando LinphoneService después del boot")
                
                val serviceIntent = Intent(context, LinphoneService::class.java)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } else {
                Log.d(TAG, "Servicio no habilitado o sin API key - no iniciando")
            }
        }
    }
}
