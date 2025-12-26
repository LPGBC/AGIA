package com.luisspamdetector.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Sistema de logging centralizado que escribe tanto en logcat como en archivo
 */
object Logger {
    private const val TAG = "AGIA"
    private var logFile: File? = null
    private var isEnabled = true
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun initialize(context: Context) {
        try {
            val logDir = File(context.getExternalFilesDir(null), "logs")
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            
            // Crear archivo de log con timestamp
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            logFile = File(logDir, "agia_log_$timestamp.txt")
            
            // Limpiar logs antiguos (mantener solo los últimos 5)
            cleanOldLogs(logDir)
            
            d(TAG, "Logger inicializado: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error al inicializar logger", e)
        }
    }

    private fun cleanOldLogs(logDir: File) {
        try {
            val logs = logDir.listFiles()?.filter { it.name.startsWith("agia_log_") }
                ?.sortedByDescending { it.lastModified() } ?: return
            
            if (logs.size > 5) {
                logs.drop(5).forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error limpiando logs antiguos", e)
        }
    }

    fun d(tag: String, message: String) {
        if (!isEnabled) return
        Log.d(tag, message)
        writeToFile("D", tag, message)
    }

    fun i(tag: String, message: String) {
        if (!isEnabled) return
        Log.i(tag, message)
        writeToFile("I", tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (!isEnabled) return
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
        writeToFile("W", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (!isEnabled) return
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
        writeToFile("E", tag, message, throwable)
    }

    private fun writeToFile(level: String, tag: String, message: String, throwable: Throwable? = null) {
        try {
            logFile?.let { file ->
                FileWriter(file, true).use { writer ->
                    val timestamp = dateFormat.format(Date())
                    writer.append("$timestamp $level/$tag: $message\n")
                    
                    throwable?.let {
                        val stringWriter = java.io.StringWriter()
                        it.printStackTrace(PrintWriter(stringWriter))
                        writer.append(stringWriter.toString())
                        writer.append("\n")
                    }
                }
            }
        } catch (e: Exception) {
            // No podemos loguear aquí para evitar recursión infinita
            Log.e(TAG, "Error escribiendo en archivo de log", e)
        }
    }

    fun getLogFile(): File? = logFile

    fun getAllLogFiles(context: Context): List<File> {
        val logDir = File(context.getExternalFilesDir(null), "logs")
        return logDir.listFiles()?.filter { it.name.startsWith("agia_log_") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun clearLogs(context: Context) {
        try {
            val logDir = File(context.getExternalFilesDir(null), "logs")
            logDir.listFiles()?.forEach { it.delete() }
            d(TAG, "Logs limpiados")
        } catch (e: Exception) {
            e(TAG, "Error limpiando logs", e)
        }
    }
}
