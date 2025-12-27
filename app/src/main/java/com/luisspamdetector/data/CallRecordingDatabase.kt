package com.luisspamdetector.data

import androidx.room.*
import com.luisspamdetector.call.CallTranscriptionService

/**
 * Entidad para almacenar grabaciones de llamadas con análisis de IA completo
 */
@Entity(tableName = "call_recordings")
data class CallRecordingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // Información de la llamada
    val phoneNumber: String,
    val displayName: String?,
    val isIncoming: Boolean,
    val duration: Long, // duración en milisegundos
    
    // Ruta al archivo de grabación
    val recordingPath: String,
    val recordingSize: Long, // tamaño del archivo en bytes
    val recordingFormat: String, // "aac", "amr", "wav"
    
    // Análisis de IA
    val transcription: String?, // Transcripción completa
    val summary: String?, // Resumen generado por IA
    val callerName: String?, // Nombre identificado por IA
    val purpose: String?, // Motivo de la llamada identificado
    val keyPoints: String?, // JSON array de puntos clave
    
    // Clasificación
    val isSpam: Boolean,
    val spamConfidence: Double,
    val urgency: String, // LOW, MEDIUM, HIGH, CRITICAL
    val sentiment: String, // POSITIVE, NEUTRAL, NEGATIVE, SUSPICIOUS
    
    // Estado
    val wasAnswered: Boolean, // Si se descolgó
    val wasRecordedBothSides: Boolean, // Si se grabaron ambos lados correctamente
    val transcriptionStatus: String, // PENDING, PROCESSING, COMPLETED, FAILED
    
    // Metadatos
    val timestamp: Long = System.currentTimeMillis(),
    val notes: String? = null // Notas del usuario
)

/**
 * DAO para operaciones de grabaciones de llamadas
 */
@Dao
interface CallRecordingDao {
    
    @Query("SELECT * FROM call_recordings ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<CallRecordingEntity>
    
    @Query("SELECT * FROM call_recordings WHERE id = :id")
    suspend fun getById(id: Long): CallRecordingEntity?
    
    @Query("SELECT * FROM call_recordings WHERE phoneNumber = :phoneNumber ORDER BY timestamp DESC")
    suspend fun getByPhoneNumber(phoneNumber: String): List<CallRecordingEntity>
    
    @Query("SELECT * FROM call_recordings WHERE isSpam = 1 ORDER BY timestamp DESC")
    suspend fun getSpamRecordings(): List<CallRecordingEntity>
    
    @Query("SELECT * FROM call_recordings WHERE transcriptionStatus = :status ORDER BY timestamp ASC")
    suspend fun getByTranscriptionStatus(status: String): List<CallRecordingEntity>
    
    @Insert
    suspend fun insert(recording: CallRecordingEntity): Long
    
    @Update
    suspend fun update(recording: CallRecordingEntity)
    
    @Query("DELETE FROM call_recordings WHERE id = :id")
    suspend fun delete(id: Long)
    
    @Query("DELETE FROM call_recordings WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)
    
    @Query("SELECT COUNT(*) FROM call_recordings")
    suspend fun getCount(): Int
    
    @Query("SELECT COUNT(*) FROM call_recordings WHERE isSpam = 1")
    suspend fun getSpamCount(): Int
    
    @Query("SELECT SUM(recordingSize) FROM call_recordings")
    suspend fun getTotalStorageUsed(): Long?
    
    @Query("DELETE FROM call_recordings")
    suspend fun deleteAll()
}

/**
 * Repositorio para gestionar grabaciones de llamadas
 */
class CallRecordingRepository(private val dao: CallRecordingDao) {
    
    /**
     * Guarda una nueva grabación en la base de datos
     */
    suspend fun saveRecording(
        phoneNumber: String,
        displayName: String?,
        isIncoming: Boolean,
        duration: Long,
        recordingPath: String,
        recordingSize: Long,
        recordingFormat: String,
        wasAnswered: Boolean,
        isSpam: Boolean = false,
        spamConfidence: Double = 0.0
    ): Long {
        val entity = CallRecordingEntity(
            phoneNumber = phoneNumber,
            displayName = displayName,
            isIncoming = isIncoming,
            duration = duration,
            recordingPath = recordingPath,
            recordingSize = recordingSize,
            recordingFormat = recordingFormat,
            transcription = null,
            summary = null,
            callerName = null,
            purpose = null,
            keyPoints = null,
            isSpam = isSpam,
            spamConfidence = spamConfidence,
            urgency = "MEDIUM",
            sentiment = "NEUTRAL",
            wasAnswered = wasAnswered,
            wasRecordedBothSides = true, // Asumimos que sí con VOICE_CALL
            transcriptionStatus = "PENDING"
        )
        return dao.insert(entity)
    }
    
    /**
     * Actualiza una grabación con el análisis de transcripción
     */
    suspend fun updateWithTranscription(
        id: Long,
        result: CallTranscriptionService.TranscriptionResult
    ) {
        val recording = dao.getById(id) ?: return
        
        val keyPointsJson = result.keyPoints.joinToString(separator = "\",\"", prefix = "[\"", postfix = "\"]")
        
        val updated = recording.copy(
            transcription = result.transcription,
            summary = result.summary,
            callerName = result.callerName,
            purpose = result.purpose,
            keyPoints = keyPointsJson,
            isSpam = result.isSpam,
            spamConfidence = result.spamConfidence,
            urgency = result.urgency.name,
            sentiment = result.sentiment.name,
            transcriptionStatus = "COMPLETED"
        )
        
        dao.update(updated)
    }
    
    /**
     * Marca una grabación como fallida en la transcripción
     */
    suspend fun markTranscriptionFailed(id: Long, error: String) {
        val recording = dao.getById(id) ?: return
        val updated = recording.copy(
            transcriptionStatus = "FAILED",
            notes = error
        )
        dao.update(updated)
    }
    
    /**
     * Obtiene grabaciones recientes
     */
    suspend fun getRecentRecordings(limit: Int = 100): List<CallRecordingEntity> {
        return dao.getRecent(limit)
    }
    
    /**
     * Obtiene grabaciones de un número específico
     */
    suspend fun getRecordingsByPhoneNumber(phoneNumber: String): List<CallRecordingEntity> {
        return dao.getByPhoneNumber(phoneNumber)
    }
    
    /**
     * Obtiene grabaciones identificadas como spam
     */
    suspend fun getSpamRecordings(): List<CallRecordingEntity> {
        return dao.getSpamRecordings()
    }
    
    /**
     * Obtiene grabaciones pendientes de transcribir
     */
    suspend fun getPendingTranscriptions(): List<CallRecordingEntity> {
        return dao.getByTranscriptionStatus("PENDING")
    }
    
    /**
     * Elimina una grabación (y opcionalmente el archivo)
     */
    suspend fun deleteRecording(id: Long, deleteFile: Boolean = false) {
        if (deleteFile) {
            val recording = dao.getById(id)
            recording?.let {
                try {
                    java.io.File(it.recordingPath).delete()
                } catch (e: Exception) {
                    // Ignorar errores al eliminar archivo
                }
            }
        }
        dao.delete(id)
    }
    
    /**
     * Limpia grabaciones antiguas
     */
    suspend fun cleanOldRecordings(daysToKeep: Int = 30, deleteFiles: Boolean = false) {
        val cutoff = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)
        
        if (deleteFiles) {
            val oldRecordings = dao.getRecent(Int.MAX_VALUE)
                .filter { it.timestamp < cutoff }
            
            oldRecordings.forEach { recording ->
                try {
                    java.io.File(recording.recordingPath).delete()
                } catch (e: Exception) {
                    // Ignorar errores
                }
            }
        }
        
        dao.deleteOlderThan(cutoff)
    }
    
    /**
     * Obtiene estadísticas de almacenamiento
     */
    suspend fun getStorageStats(): StorageStats {
        return StorageStats(
            totalRecordings = dao.getCount(),
            spamRecordings = dao.getSpamCount(),
            totalStorageBytes = dao.getTotalStorageUsed() ?: 0L
        )
    }
    
    /**
     * Limpia todas las grabaciones
     */
    suspend fun clearAll(deleteFiles: Boolean = false) {
        if (deleteFiles) {
            val allRecordings = dao.getRecent(Int.MAX_VALUE)
            allRecordings.forEach { recording ->
                try {
                    java.io.File(recording.recordingPath).delete()
                } catch (e: Exception) {
                    // Ignorar errores
                }
            }
        }
        dao.deleteAll()
    }
}

data class StorageStats(
    val totalRecordings: Int,
    val spamRecordings: Int,
    val totalStorageBytes: Long
) {
    val totalStorageMB: Double
        get() = totalStorageBytes / (1024.0 * 1024.0)
}
