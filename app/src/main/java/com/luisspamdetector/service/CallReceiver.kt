package com.luisspamdetector.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
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
            Log.w(TAG, "No hay permisos de teléfono")
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
                    Log.d(TAG, "Llamada entrante detectada (GSM): $phoneNumber")
                    ensureServiceRunning(context)
                } else {
                    Log.d(TAG, "Llamada entrante detectada pero número no disponible")
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                Log.d(TAG, "Llamada contestada")
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                Log.d(TAG, "Llamada terminada")
            }
        }
    }

    private fun ensureServiceRunning(context: Context) {
        // Verificar si el servicio ya está corriendo
        if (LinphoneService.isRunning) {
            Log.d(TAG, "LinphoneService ya está corriendo")
            return
        }

        // Verificar configuración
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""

        if (apiKey.isEmpty()) {
            Log.d(TAG, "API key no configurada - no iniciando servicio")
            return
        }

        // Iniciar servicio
        Log.d(TAG, "Iniciando LinphoneService desde CallReceiver")
        val serviceIntent = Intent(context, LinphoneService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
