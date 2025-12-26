package com.luisspamdetector.service
import com.luisspamdetector.util.Logger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import com.luisspamdetector.util.PermissionsHelper

/**
 * Receiver para detectar cambios de estado del teléfono.
 * Actúa como respaldo cuando Linphone no detecta la llamada directamente.
 * Compatible con Android 15 (API 35)
 */
class CallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            return
        }

        if (!PermissionsHelper.hasPhoneStatePermission(context)) {
            Logger.w(TAG, "No hay permisos de teléfono")
            return
        }

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // En Android 10+ puede que no tengamos acceso al número
                @Suppress("DEPRECATION")
                val phoneNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // En Android 10+ el número puede estar restringido
                    intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                } else {
                    intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                }

                if (phoneNumber != null) {
                    Logger.d(TAG, "Llamada entrante detectada (GSM): $phoneNumber")
                    ensureServiceRunning(context)
                } else {
                    Logger.d(TAG, "Llamada entrante detectada pero número no disponible")
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                Logger.d(TAG, "Llamada contestada")
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                Logger.d(TAG, "Llamada terminada")
            }
        }
    }

    private fun ensureServiceRunning(context: Context) {
        // Verificar si el servicio ya está corriendo
        if (LinphoneService.isRunning) {
            Logger.d(TAG, "LinphoneService ya está corriendo")
            return
        }

        // Verificar configuración
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""

        if (apiKey.isEmpty()) {
            Logger.d(TAG, "API key no configurada - no iniciando servicio")
            return
        }

        // Iniciar servicio
        Logger.d(TAG, "Iniciando LinphoneService desde CallReceiver")
        val serviceIntent = Intent(context, LinphoneService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
