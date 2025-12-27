package com.luisspamdetector.call

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.luisspamdetector.util.Logger
import java.io.File
import java.io.IOException

/**
 * Grabador de llamadas que captura AMBOS lados de la conversación.
 * 
 * IMPORTANTE: Este grabador usa AudioSource.VOICE_CALL que captura:
 * - El audio del micrófono (tu voz)
 * - El audio del altavoz (voz del que llama)
 * 
 * Requisitos:
 * - Permiso RECORD_AUDIO
 * - En Android 10+: También requiere acceso a almacenamiento
 * - En Android 14+: Puede requerir notificación al usuario
 */
class CallRecorder(private val context: Context) {
    
    companion object {
        private const val TAG = "CallRecorder"
        
        // Formatos soportados
        enum class AudioFormat {
            AAC,    // Mejor compresión, buena calidad
            AMR,    // Optimizado para voz, menor tamaño
            WAV     // Sin compresión, máxima calidad
        }
    }
    
    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var isRecording = false
    private var startTime: Long = 0
    
    /**
     * Inicia la grabación de la llamada
     * 
     * @param phoneNumber número de teléfono para nombrar el archivo
     * @param format formato de audio deseado
     * @return ruta del archivo donde se está grabando, o null si falla
     */
    fun startRecording(phoneNumber: String, format: AudioFormat = AudioFormat.AAC): String? {
        if (isRecording) {
            Logger.w(TAG, "Ya hay una grabación en curso")
            return recordingFile?.absolutePath
        }
        
        try {
            // Crear directorio de grabaciones
            val recordingsDir = File(context.filesDir, "call_recordings")
            if (!recordingsDir.exists()) {
                recordingsDir.mkdirs()
            }
            
            // Generar nombre de archivo
            val timestamp = System.currentTimeMillis()
            val sanitizedNumber = phoneNumber.replace(Regex("[^0-9+]"), "_")
            val extension = when (format) {
                AudioFormat.AAC -> "m4a"
                AudioFormat.AMR -> "amr"
                AudioFormat.WAV -> "wav"
            }
            val fileName = "call_${sanitizedNumber}_$timestamp.$extension"
            recordingFile = File(recordingsDir, fileName)
            
            // Configurar MediaRecorder
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                try {
                    // CLAVE: AudioSource.VOICE_CALL captura ambos lados de la llamada
                    setAudioSource(MediaRecorder.AudioSource.VOICE_CALL)
                    
                    // Configurar formato según preferencia
                    when (format) {
                        AudioFormat.AAC -> {
                            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                            setAudioEncodingBitRate(128000)
                            setAudioSamplingRate(44100)
                        }
                        AudioFormat.AMR -> {
                            setOutputFormat(MediaRecorder.OutputFormat.AMR_WB)
                            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_WB)
                            setAudioSamplingRate(16000)
                        }
                        AudioFormat.WAV -> {
                            // WAV no está directamente soportado, usar AAC de alta calidad
                            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                            setAudioEncodingBitRate(256000)
                            setAudioSamplingRate(48000)
                        }
                    }
                    
                    setOutputFile(recordingFile!!.absolutePath)
                    
                    prepare()
                    start()
                    
                    isRecording = true
                    startTime = System.currentTimeMillis()
                    
                    Logger.i(TAG, "Grabación iniciada: ${recordingFile!!.absolutePath}")
                    Logger.i(TAG, "Formato: $format - Capturando ambos lados de la conversación")
                    
                } catch (e: Exception) {
                    Logger.e(TAG, "Error configurando MediaRecorder", e)
                    release()
                    throw e
                }
            }
            
            return recordingFile?.absolutePath
            
        } catch (e: IOException) {
            Logger.e(TAG, "Error de I/O al iniciar grabación", e)
            cleanup()
            return null
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "Estado inválido al iniciar grabación", e)
            cleanup()
            return null
        } catch (e: SecurityException) {
            Logger.e(TAG, "Falta permiso RECORD_AUDIO", e)
            cleanup()
            return null
        } catch (e: Exception) {
            Logger.e(TAG, "Error inesperado al iniciar grabación", e)
            cleanup()
            return null
        }
    }
    
    /**
     * Detiene la grabación y devuelve la ruta del archivo
     */
    fun stopRecording(): String? {
        if (!isRecording) {
            Logger.w(TAG, "No hay grabación activa para detener")
            return null
        }
        
        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
            
            val duration = System.currentTimeMillis() - startTime
            val filePath = recordingFile?.absolutePath
            
            Logger.i(TAG, "Grabación detenida: $filePath")
            Logger.i(TAG, "Duración: ${duration / 1000} segundos")
            Logger.i(TAG, "Tamaño: ${recordingFile?.length() ?: 0} bytes")
            
            isRecording = false
            mediaRecorder = null
            
            return filePath
            
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "Error al detener grabación (estado inválido)", e)
            cleanup()
            return recordingFile?.absolutePath
        } catch (e: RuntimeException) {
            Logger.e(TAG, "Error al detener grabación", e)
            cleanup()
            return recordingFile?.absolutePath
        }
    }
    
    /**
     * Cancela la grabación y elimina el archivo
     */
    fun cancelRecording() {
        try {
            if (isRecording) {
                mediaRecorder?.apply {
                    stop()
                    reset()
                    release()
                }
            }
            
            recordingFile?.let { file ->
                if (file.exists()) {
                    file.delete()
                    Logger.i(TAG, "Archivo de grabación eliminado")
                }
            }
            
        } catch (e: Exception) {
            Logger.e(TAG, "Error al cancelar grabación", e)
        } finally {
            cleanup()
        }
    }
    
    /**
     * Verifica si hay una grabación en curso
     */
    fun isRecording(): Boolean = isRecording
    
    /**
     * Obtiene la duración actual de la grabación en milisegundos
     */
    fun getCurrentDuration(): Long {
        return if (isRecording) {
            System.currentTimeMillis() - startTime
        } else {
            0
        }
    }
    
    /**
     * Obtiene la ruta del archivo actual
     */
    fun getCurrentFilePath(): String? = recordingFile?.absolutePath
    
    /**
     * Limpia recursos internos
     */
    private fun cleanup() {
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            Logger.e(TAG, "Error liberando MediaRecorder", e)
        }
        
        mediaRecorder = null
        recordingFile = null
        isRecording = false
        startTime = 0
    }
    
    /**
     * Libera todos los recursos
     */
    fun release() {
        if (isRecording) {
            stopRecording()
        }
        cleanup()
    }
}
