package com.luisspamdetector.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

/**
 * Helper para operaciones con contactos del dispositivo
 */
class ContactsHelper(private val context: Context) {

    companion object {
        private const val TAG = "ContactsHelper"
    }

    /**
     * Verifica si un número de teléfono está en los contactos del dispositivo
     */
    fun isNumberInContacts(phoneNumber: String): Boolean {
        if (!PermissionsHelper.hasContactsPermission(context)) {
            Log.w(TAG, "No hay permiso de contactos")
            return false
        }

        val normalizedNumber = normalizePhoneNumber(phoneNumber)
        
        var cursor: Cursor? = null
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(normalizedNumber)
            )

            cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null,
                null,
                null
            )

            return cursor?.count ?: 0 > 0

        } catch (e: Exception) {
            Log.e(TAG, "Error checking contacts", e)
            return false
        } finally {
            cursor?.close()
        }
    }

    /**
     * Obtiene el nombre del contacto asociado a un número de teléfono
     */
    fun getContactName(phoneNumber: String): String? {
        if (!PermissionsHelper.hasContactsPermission(context)) {
            return null
        }

        val normalizedNumber = normalizePhoneNumber(phoneNumber)
        
        var cursor: Cursor? = null
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(normalizedNumber)
            )

            cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )

            if (cursor?.moveToFirst() == true) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    return cursor.getString(nameIndex)
                }
            }
            return null

        } catch (e: Exception) {
            Log.e(TAG, "Error getting contact name", e)
            return null
        } finally {
            cursor?.close()
        }
    }

    /**
     * Obtiene la foto del contacto si existe
     */
    fun getContactPhotoUri(phoneNumber: String): Uri? {
        if (!PermissionsHelper.hasContactsPermission(context)) {
            return null
        }

        val normalizedNumber = normalizePhoneNumber(phoneNumber)
        
        var cursor: Cursor? = null
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(normalizedNumber)
            )

            cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.PHOTO_URI),
                null,
                null,
                null
            )

            if (cursor?.moveToFirst() == true) {
                val photoIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.PHOTO_URI)
                if (photoIndex >= 0) {
                    val photoUri = cursor.getString(photoIndex)
                    return if (photoUri != null) Uri.parse(photoUri) else null
                }
            }
            return null

        } catch (e: Exception) {
            Log.e(TAG, "Error getting contact photo", e)
            return null
        } finally {
            cursor?.close()
        }
    }

    /**
     * Normaliza un número de teléfono eliminando caracteres especiales
     */
    private fun normalizePhoneNumber(phoneNumber: String): String {
        // Eliminar protocolo SIP si existe
        var number = phoneNumber
            .replace("sip:", "")
            .replace("tel:", "")

        // Eliminar dominio SIP si existe
        val atIndex = number.indexOf('@')
        if (atIndex > 0) {
            number = number.substring(0, atIndex)
        }

        // Eliminar caracteres no numéricos excepto el + inicial
        val hasPlus = number.startsWith("+")
        number = number.replace(Regex("[^0-9]"), "")

        if (hasPlus) {
            number = "+$number"
        }

        return number
    }

    /**
     * Formatea un número de teléfono para mostrar
     */
    fun formatPhoneNumber(phoneNumber: String): String {
        val normalized = normalizePhoneNumber(phoneNumber)
        
        // Formato para España: +34 XXX XXX XXX
        return if (normalized.startsWith("+34") && normalized.length == 12) {
            "${normalized.substring(0, 3)} ${normalized.substring(3, 6)} ${normalized.substring(6, 9)} ${normalized.substring(9)}"
        } else if (normalized.length == 9) {
            "${normalized.substring(0, 3)} ${normalized.substring(3, 6)} ${normalized.substring(6)}"
        } else {
            normalized
        }
    }
}
