package com.luisspamdetector.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Helper para manejo de permisos en Android 14/15
 */
object PermissionsHelper {

    const val PERMISSION_REQUEST_CODE = 1001

    /**
     * Lista de permisos requeridos para la aplicación
     */
    fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET
        )

        // Android 12+ requiere BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // Android 13+ requiere POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Android 14+ requiere FOREGROUND_SERVICE_PHONE_CALL y FOREGROUND_SERVICE_MICROPHONE
        // Estos se declaran en el manifest, no se solicitan en runtime

        return permissions.toTypedArray()
    }

    /**
     * Verifica si todos los permisos requeridos están concedidos
     */
    fun hasAllPermissions(context: Context): Boolean {
        return getRequiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Obtiene la lista de permisos que faltan
     */
    fun getMissingPermissions(context: Context): List<String> {
        return getRequiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Solicita los permisos faltantes
     */
    fun requestMissingPermissions(activity: Activity) {
        val missing = getMissingPermissions(activity)
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                missing.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    /**
     * Verifica si tiene permiso de teléfono
     */
    fun hasPhoneStatePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Verifica si tiene permiso de contactos
     */
    fun hasContactsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Verifica si tiene permiso de micrófono
     */
    fun hasMicrophonePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Verifica si tiene permiso de notificaciones (Android 13+)
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Verifica si tiene permiso de overlay (mostrar sobre otras apps)
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Abre la configuración para conceder permiso de overlay
     */
    fun requestOverlayPermission(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivity(intent)
    }

    /**
     * Verifica si la app está excluida de optimización de batería
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Solicita exclusión de optimización de batería
     */
    fun requestBatteryOptimizationExemption(activity: Activity) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        activity.startActivity(intent)
    }

    /**
     * Abre la configuración de la aplicación
     */
    fun openAppSettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
        }
        activity.startActivity(intent)
    }

    /**
     * Procesa el resultado de la solicitud de permisos
     */
    fun handlePermissionResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): PermissionResult {
        if (requestCode != PERMISSION_REQUEST_CODE) {
            return PermissionResult(false, emptyList(), emptyList())
        }

        val granted = mutableListOf<String>()
        val denied = mutableListOf<String>()

        permissions.forEachIndexed { index, permission ->
            if (grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED) {
                granted.add(permission)
            } else {
                denied.add(permission)
            }
        }

        return PermissionResult(
            allGranted = denied.isEmpty(),
            grantedPermissions = granted,
            deniedPermissions = denied
        )
    }

    data class PermissionResult(
        val allGranted: Boolean,
        val grantedPermissions: List<String>,
        val deniedPermissions: List<String>
    )

    /**
     * Obtiene un nombre legible para cada permiso
     */
    fun getPermissionName(permission: String): String {
        return when (permission) {
            Manifest.permission.READ_PHONE_STATE -> "Estado del teléfono"
            Manifest.permission.READ_CALL_LOG -> "Registro de llamadas"
            Manifest.permission.READ_CONTACTS -> "Contactos"
            Manifest.permission.RECORD_AUDIO -> "Micrófono"
            Manifest.permission.POST_NOTIFICATIONS -> "Notificaciones"
            Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth"
            Manifest.permission.CAMERA -> "Cámara"
            else -> permission.substringAfterLast(".")
        }
    }
}
