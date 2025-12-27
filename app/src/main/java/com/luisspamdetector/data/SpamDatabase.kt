package com.luisspamdetector.data

import android.content.Context
import androidx.room.*

/**
 * Entidad para almacenar resultados de verificación de spam
 */
@Entity(tableName = "spam_checks")
data class SpamCheckEntity(
    @PrimaryKey
    val phoneNumber: String,
    val isSpam: Boolean,
    val confidence: Double,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Entidad para almacenar historial de llamadas procesadas
 */
@Entity(tableName = "call_history")
data class CallHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val phoneNumber: String,
    val callerName: String?,
    val callerPurpose: String?,
    val wasAccepted: Boolean,
    val isSpam: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Entidad para almacenar historial de screenings con grabaciones y transcripciones
 */
@Entity(tableName = "screening_history")
data class ScreeningHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val phoneNumber: String,
    val callerName: String?,
    val callerPurpose: String?,
    val transcription: String?,
    val summary: String? = null, // Resumen generado por IA de la llamada
    val recordingPath: String?,
    val duration: Int = 0, // duración en segundos
    val wasAccepted: Boolean = false,
    val isSpam: Boolean = false,
    val spamConfidence: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * DAO para operaciones de spam checks
 */
@Dao
interface SpamCheckDao {
    @Query("SELECT * FROM spam_checks WHERE phoneNumber = :phoneNumber LIMIT 1")
    suspend fun getByPhoneNumber(phoneNumber: String): SpamCheckEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(check: SpamCheckEntity)

    @Query("DELETE FROM spam_checks WHERE phoneNumber = :phoneNumber")
    suspend fun delete(phoneNumber: String)

    @Query("DELETE FROM spam_checks WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)

    @Query("SELECT * FROM spam_checks ORDER BY timestamp DESC")
    suspend fun getAll(): List<SpamCheckEntity>

    @Query("SELECT COUNT(*) FROM spam_checks WHERE isSpam = 1")
    suspend fun getSpamCount(): Int

    @Query("DELETE FROM spam_checks")
    suspend fun deleteAll()
}

/**
 * DAO para operaciones de historial de llamadas
 */
@Dao
interface CallHistoryDao {
    @Query("SELECT * FROM call_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<CallHistoryEntity>

    @Insert
    suspend fun insert(call: CallHistoryEntity): Long

    @Query("DELETE FROM call_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM call_history WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)

    @Query("SELECT COUNT(*) FROM call_history")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM call_history WHERE isSpam = 1")
    suspend fun getSpamCount(): Int

    @Query("SELECT COUNT(*) FROM call_history WHERE wasAccepted = 0")
    suspend fun getRejectedCount(): Int

    @Query("DELETE FROM call_history")
    suspend fun deleteAll()
}

/**
 * DAO para operaciones de historial de screenings
 */
@Dao
interface ScreeningHistoryDao {
    @Query("SELECT * FROM screening_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<ScreeningHistoryEntity>

    @Query("SELECT * FROM screening_history WHERE id = :id")
    suspend fun getById(id: Long): ScreeningHistoryEntity?

    @Insert
    suspend fun insert(screening: ScreeningHistoryEntity): Long

    @Update
    suspend fun update(screening: ScreeningHistoryEntity)

    @Query("DELETE FROM screening_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM screening_history WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)

    @Query("SELECT COUNT(*) FROM screening_history")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM screening_history WHERE isSpam = 1")
    suspend fun getSpamCount(): Int

    @Query("SELECT * FROM screening_history WHERE phoneNumber = :phoneNumber ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestByPhoneNumber(phoneNumber: String): ScreeningHistoryEntity?

    @Query("DELETE FROM screening_history")
    suspend fun deleteAll()
}

/**
 * Base de datos Room principal
 */
@Database(
    entities = [SpamCheckEntity::class, CallHistoryEntity::class, ScreeningHistoryEntity::class, CallRecordingEntity::class],
    version = 5,
    exportSchema = false
)
abstract class SpamDatabase : RoomDatabase() {
    abstract fun spamCheckDao(): SpamCheckDao
    abstract fun callHistoryDao(): CallHistoryDao
    abstract fun screeningHistoryDao(): ScreeningHistoryDao
    abstract fun callRecordingDao(): CallRecordingDao

    companion object {
        @Volatile
        private var INSTANCE: SpamDatabase? = null

        fun getDatabase(context: Context): SpamDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SpamDatabase::class.java,
                    "spam_detector_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * Repositorio para acceder a los datos
 */
class SpamRepository(private val spamCheckDao: SpamCheckDao) {
    
    companion object {
        // Caché válida por 7 días
        private const val CACHE_VALIDITY_MS = 7 * 24 * 60 * 60 * 1000L
    }

    suspend fun getCheckResult(phoneNumber: String): SpamCheckEntity? {
        val result = spamCheckDao.getByPhoneNumber(phoneNumber)
        
        // Verificar si el caché sigue siendo válido
        if (result != null) {
            val age = System.currentTimeMillis() - result.timestamp
            if (age > CACHE_VALIDITY_MS) {
                spamCheckDao.delete(phoneNumber)
                return null
            }
        }
        
        return result
    }

    suspend fun insertCheck(check: SpamCheckEntity) {
        spamCheckDao.insert(check)
    }

    suspend fun getAllChecks(): List<SpamCheckEntity> {
        return spamCheckDao.getAll()
    }

    suspend fun getSpamCount(): Int {
        return spamCheckDao.getSpamCount()
    }

    suspend fun clearOldCache() {
        val cutoff = System.currentTimeMillis() - CACHE_VALIDITY_MS
        spamCheckDao.deleteOlderThan(cutoff)
    }

    suspend fun clearAllCache() {
        spamCheckDao.deleteAll()
    }
}

/**
 * Repositorio para historial de llamadas
 */
class CallHistoryRepository(private val callHistoryDao: CallHistoryDao) {

    suspend fun addCall(
        phoneNumber: String,
        callerName: String?,
        callerPurpose: String?,
        wasAccepted: Boolean,
        isSpam: Boolean
    ): Long {
        val entity = CallHistoryEntity(
            phoneNumber = phoneNumber,
            callerName = callerName,
            callerPurpose = callerPurpose,
            wasAccepted = wasAccepted,
            isSpam = isSpam
        )
        return callHistoryDao.insert(entity)
    }

    suspend fun getRecentCalls(limit: Int = 50): List<CallHistoryEntity> {
        return callHistoryDao.getRecent(limit)
    }

    suspend fun getStats(): CallStats {
        return CallStats(
            totalCalls = callHistoryDao.getCount(),
            spamCalls = callHistoryDao.getSpamCount(),
            rejectedCalls = callHistoryDao.getRejectedCount()
        )
    }

    suspend fun clearHistory() {
        callHistoryDao.deleteAll()
    }
}

data class CallStats(
    val totalCalls: Int,
    val spamCalls: Int,
    val rejectedCalls: Int
)

/**
 * Repositorio para historial de screenings
 */
class ScreeningHistoryRepository(private val screeningHistoryDao: ScreeningHistoryDao) {

    suspend fun addScreening(
        phoneNumber: String,
        callerName: String?,
        callerPurpose: String?,
        transcription: String?,
        summary: String? = null,
        recordingPath: String?,
        duration: Int = 0,
        wasAccepted: Boolean = false,
        isSpam: Boolean = false,
        spamConfidence: Double = 0.0
    ): Long {
        val entity = ScreeningHistoryEntity(
            phoneNumber = phoneNumber,
            callerName = callerName,
            callerPurpose = callerPurpose,
            transcription = transcription,
            summary = summary,
            recordingPath = recordingPath,
            duration = duration,
            wasAccepted = wasAccepted,
            isSpam = isSpam,
            spamConfidence = spamConfidence
        )
        return screeningHistoryDao.insert(entity)
    }

    suspend fun getRecentScreenings(limit: Int = 100): List<ScreeningHistoryEntity> {
        return screeningHistoryDao.getRecent(limit)
    }

    suspend fun getScreeningById(id: Long): ScreeningHistoryEntity? {
        return screeningHistoryDao.getById(id)
    }

    suspend fun updateScreening(screening: ScreeningHistoryEntity) {
        screeningHistoryDao.update(screening)
    }

    suspend fun deleteScreening(id: Long) {
        screeningHistoryDao.delete(id)
    }

    suspend fun getScreeningCount(): Int {
        return screeningHistoryDao.getCount()
    }

    suspend fun getSpamScreeningCount(): Int {
        return screeningHistoryDao.getSpamCount()
    }

    suspend fun clearHistory() {
        screeningHistoryDao.deleteAll()
    }

    suspend fun clearOldHistory(daysToKeep: Int = 30) {
        val cutoff = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)
        screeningHistoryDao.deleteOlderThan(cutoff)
    }
}
