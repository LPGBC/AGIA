package com.luisspamdetector.api

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Servicio para interactuar con la API de Gemini.
 * Proporciona detección de spam y procesamiento de lenguaje natural.
 */
class GeminiApiService(private var apiKey: String) {

    companion object {
        private const val TAG = "GeminiApiService"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val UPLOAD_URL = "https://generativelanguage.googleapis.com/upload/v1beta/files"
        private const val MODEL = "gemini-2.5-flash" // Modelo estable más reciente
        private const val TIMEOUT_SECONDS = 120L // Más tiempo para upload de audio
        private const val MAX_AUDIO_SIZE_MB = 20 // Límite de tamaño de audio
        private const val MAX_RETRIES = 3 // Número máximo de reintentos
        private const val INITIAL_DELAY_MS = 5000L // Delay inicial 5 segundos (recomendado por Google)
        private const val USE_FILE_API_THRESHOLD_MB = 1 // Usar File API para archivos > 1MB
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val gson = Gson()

    fun updateApiKey(newApiKey: String) {
        apiKey = newApiKey
    }

    fun hasValidApiKey(): Boolean = apiKey.isNotBlank()

    /**
     * Transcribe un archivo de audio y genera un resumen
     * @param audioPath Ruta al archivo de audio (WAV, MP3, etc.)
     * @param phoneNumber Número de teléfono para contexto
     * @return TranscriptionResult con transcripción, resumen e info de spam
     */
    suspend fun transcribeAudio(audioPath: String, phoneNumber: String): TranscriptionResult {
        return withContext(Dispatchers.IO) {
            try {
                val audioFile = File(audioPath)
                if (!audioFile.exists()) {
                    Log.e(TAG, "Archivo de audio no encontrado: $audioPath")
                    return@withContext TranscriptionResult(
                        transcription = "Archivo de audio no encontrado",
                        summary = "Error al procesar",
                        isSpam = false,
                        spamConfidence = 0.0
                    )
                }
                
                // Verificar tamaño del archivo
                val fileSizeMB = audioFile.length() / (1024 * 1024)
                if (fileSizeMB > MAX_AUDIO_SIZE_MB) {
                    Log.w(TAG, "Archivo de audio muy grande: ${fileSizeMB}MB")
                    return@withContext TranscriptionResult(
                        transcription = "Archivo de audio demasiado grande para procesar",
                        summary = "Audio muy largo",
                        isSpam = false,
                        spamConfidence = 0.0
                    )
                }
                
                // Leer y codificar el audio en base64
                val audioBytes = audioFile.readBytes()
                val audioBase64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
                
                // Determinar el tipo MIME
                val mimeType = when {
                    audioPath.endsWith(".wav", ignoreCase = true) -> "audio/wav"
                    audioPath.endsWith(".mp3", ignoreCase = true) -> "audio/mp3"
                    audioPath.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
                    audioPath.endsWith(".ogg", ignoreCase = true) -> "audio/ogg"
                    audioPath.endsWith(".flac", ignoreCase = true) -> "audio/flac"
                    else -> "audio/wav" // Default
                }
                
                Log.i(TAG, "Transcribiendo audio: ${audioFile.name}, tamaño: ${fileSizeMB}MB, tipo: $mimeType")
                
                // Decidir si usar File API o inline basado en el tamaño
                val response = if (fileSizeMB >= USE_FILE_API_THRESHOLD_MB) {
                    // Usar File API para archivos grandes (recomendado por Google)
                    Log.i(TAG, "Usando File API para archivo de ${fileSizeMB}MB")
                    transcribeWithFileApi(audioFile, mimeType, phoneNumber)
                } else {
                    // Usar inline para archivos pequeños
                    Log.i(TAG, "Usando método inline para archivo pequeño")
                    makeAudioApiRequestWithRetry(audioBase64, mimeType, phoneNumber)
                }
                parseTranscriptionResponse(response)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error transcribiendo audio", e)
                TranscriptionResult(
                    transcription = "Error al transcribir: ${e.message}",
                    summary = "Error de transcripción",
                    isSpam = false,
                    spamConfidence = 0.0
                )
            }
        }
    }
    
    /**
     * Transcribe audio usando la File API de Gemini (recomendado para archivos > 1MB)
     * Paso 1: Sube el archivo a files.upload
     * Paso 2: Usa la URI devuelta en el prompt
     */
    private suspend fun transcribeWithFileApi(audioFile: File, mimeType: String, phoneNumber: String): String {
        var lastException: Exception? = null
        var delayMs = INITIAL_DELAY_MS
        
        repeat(MAX_RETRIES) { attempt ->
            try {
                Log.d(TAG, "File API - Intento ${attempt + 1} de $MAX_RETRIES")
                
                // Paso 1: Subir el archivo
                val fileUri = uploadFileToGemini(audioFile, mimeType)
                Log.i(TAG, "Archivo subido exitosamente, URI: $fileUri")
                
                // Paso 2: Usar la URI en el prompt
                return makeApiRequestWithFileUri(fileUri, mimeType, phoneNumber)
                
            } catch (e: IOException) {
                lastException = e
                if (e.message?.contains("429") == true) {
                    Log.w(TAG, "Rate limit alcanzado (429), esperando ${delayMs}ms antes de reintentar...")
                    delay(delayMs)
                    delayMs *= 2
                } else {
                    throw e
                }
            }
        }
        
        throw lastException ?: IOException("Error desconocido después de $MAX_RETRIES intentos")
    }
    
    /**
     * Sube un archivo a la File API de Gemini
     * @return URI del archivo (ej: "files/abc-123")
     */
    private fun uploadFileToGemini(audioFile: File, mimeType: String): String {
        val uploadUrl = "$UPLOAD_URL?key=$apiKey"
        
        // Leer el archivo y codificar en base64
        val audioBytes = audioFile.readBytes()
        val audioBase64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        
        // Crear el request body para upload
        val requestJson = """
            {
                "file": {
                    "display_name": "${audioFile.name}",
                    "mime_type": "$mimeType"
                }
            }
        """.trimIndent()
        
        // Usar multipart upload con el archivo
        val boundary = "----GeminiFileUpload${System.currentTimeMillis()}"
        val mediaType = "multipart/related; boundary=$boundary".toMediaType()
        
        val multipartBody = buildString {
            // Parte 1: Metadata JSON
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n\r\n")
            append(requestJson)
            append("\r\n")
            
            // Parte 2: Datos del archivo
            append("--$boundary\r\n")
            append("Content-Type: $mimeType\r\n")
            append("Content-Transfer-Encoding: base64\r\n\r\n")
            append(audioBase64)
            append("\r\n")
            append("--$boundary--")
        }
        
        val body = multipartBody.toRequestBody(mediaType)
        
        val request = Request.Builder()
            .url(uploadUrl)
            .post(body)
            .addHeader("Content-Type", "multipart/related; boundary=$boundary")
            .build()
        
        Log.d(TAG, "Subiendo archivo a File API: ${audioFile.name}")
        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            Log.e(TAG, "Error subiendo archivo: ${response.code} - $errorBody")
            throw IOException("File upload error: ${response.code} - $errorBody")
        }
        
        val responseBody = response.body?.string() ?: throw IOException("Empty response from file upload")
        Log.d(TAG, "Respuesta de upload: $responseBody")
        
        // Parsear la respuesta para obtener el file URI
        return parseFileUri(responseBody)
    }
    
    /**
     * Parsea la respuesta del upload para extraer el file URI
     */
    private fun parseFileUri(responseBody: String): String {
        try {
            val jsonObject = gson.fromJson(responseBody, Map::class.java)
            val file = jsonObject["file"] as? Map<*, *>
            val uri = file?.get("uri") as? String
            if (uri != null) {
                return uri
            }
            // Alternativa: buscar name
            val name = file?.get("name") as? String
            if (name != null) {
                return name
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parseando respuesta de upload", e)
        }
        throw IOException("No se pudo obtener el URI del archivo subido")
    }
    
    /**
     * Hace la solicitud de transcripción usando el file URI
     */
    private fun makeApiRequestWithFileUri(fileUri: String, mimeType: String, phoneNumber: String): String {
        if (apiKey.isBlank()) {
            throw IllegalStateException("API key no configurada")
        }

        val url = "$BASE_URL/$MODEL:generateContent?key=$apiKey"
        
        val prompt = buildString {
            append("Escucha atentamente este audio de una llamada telefónica y proporciona: ")
            append("1. Una transcripción completa de todo lo que se dice en el audio. ")
            append("2. Un resumen breve del motivo de la llamada. ")
            append("3. Análisis de si parece ser spam/telemarketing/estafa. ")
            append("Número de teléfono del llamante: $phoneNumber. ")
            append("Responde EXACTAMENTE en este formato JSON sin markdown: ")
            append("{")
            append("transcripcion: transcripción completa palabra por palabra, ")
            append("resumen: resumen breve en 1-2 líneas, ")
            append("es_spam: true o false, ")
            append("confianza_spam: número entre 0.0 y 1.0, ")
            append("idioma_detectado: español o inglés u otro")
            append("}. ")
            append("Si el audio está vacío o en silencio indica Audio sin contenido audible en la transcripción.")
        }
        
        val escapedPrompt = prompt
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        
        // Request body usando file_data con la URI del archivo subido
        val requestJson = """
            {
                "contents": [{
                    "parts": [
                        {
                            "file_data": {
                                "mime_type": "$mimeType",
                                "file_uri": "$fileUri"
                            }
                        },
                        {
                            "text": "$escapedPrompt"
                        }
                    ]
                }],
                "generationConfig": {
                    "temperature": 0.2,
                    "maxOutputTokens": 2048
                }
            }
        """.trimIndent()
        
        val mediaType = "application/json".toMediaType()
        val body = requestJson.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .build()

        Log.d(TAG, "Enviando solicitud con file_uri: $fileUri")
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            Log.e(TAG, "API error transcribiendo con file URI: ${response.code} - $errorBody")
            throw IOException("API error: ${response.code} - $errorBody")
        }

        val responseBody = response.body?.string() ?: throw IOException("Empty response")
        return extractTextFromResponse(responseBody)
    }
    
    /**
     * Hace una solicitud a la API con reintentos para manejar errores 429
     */
    private suspend fun makeAudioApiRequestWithRetry(audioBase64: String, mimeType: String, phoneNumber: String): String {
        var lastException: Exception? = null
        var delayMs = INITIAL_DELAY_MS
        
        repeat(MAX_RETRIES) { attempt ->
            try {
                Log.d(TAG, "Intento ${attempt + 1} de $MAX_RETRIES")
                return makeAudioApiRequest(audioBase64, mimeType, phoneNumber)
            } catch (e: IOException) {
                lastException = e
                // Si es error 429 (rate limit), esperar y reintentar
                if (e.message?.contains("429") == true) {
                    Log.w(TAG, "Rate limit alcanzado (429), esperando ${delayMs}ms antes de reintentar...")
                    delay(delayMs)
                    delayMs *= 2 // Backoff exponencial
                } else {
                    // Para otros errores, no reintentar
                    throw e
                }
            }
        }
        
        throw lastException ?: IOException("Error desconocido después de $MAX_RETRIES intentos")
    }
    
    /**
     * Hace una solicitud a la API con audio incluido
     */
    private fun makeAudioApiRequest(audioBase64: String, mimeType: String, phoneNumber: String): String {
        if (apiKey.isBlank()) {
            throw IllegalStateException("API key no configurada")
        }

        val url = "$BASE_URL/$MODEL:generateContent?key=$apiKey"
        
        val prompt = buildString {
            append("Escucha atentamente este audio de una llamada telefónica y proporciona: ")
            append("1. Una transcripción completa de todo lo que se dice en el audio. ")
            append("2. Un resumen breve del motivo de la llamada. ")
            append("3. Análisis de si parece ser spam/telemarketing/estafa. ")
            append("Número de teléfono del llamante: $phoneNumber. ")
            append("Responde EXACTAMENTE en este formato JSON sin markdown: ")
            append("{")
            append("transcripcion: transcripción completa palabra por palabra, ")
            append("resumen: resumen breve en 1-2 líneas, ")
            append("es_spam: true o false, ")
            append("confianza_spam: número entre 0.0 y 1.0, ")
            append("idioma_detectado: español o inglés u otro")
            append("}. ")
            append("Si el audio está vacío o en silencio indica Audio sin contenido audible en la transcripción.")
        }
        
        // Escapar el prompt para JSON
        val escapedPrompt = prompt
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        
        // Crear el request body con audio inline
        val requestJson = """
            {
                "contents": [{
                    "parts": [
                        {
                            "inline_data": {
                                "mime_type": "$mimeType",
                                "data": "$audioBase64"
                            }
                        },
                        {
                            "text": "$escapedPrompt"
                        }
                    ]
                }],
                "generationConfig": {
                    "temperature": 0.2,
                    "maxOutputTokens": 2048
                }
            }
        """.trimIndent()
        
        val mediaType = "application/json".toMediaType()
        val body = requestJson.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            Log.e(TAG, "API error transcribiendo audio: ${response.code} - $errorBody")
            throw IOException("API error: ${response.code} - $errorBody")
        }

        val responseBody = response.body?.string() ?: throw IOException("Empty response")
        return extractTextFromResponse(responseBody)
    }
    
    /**
     * Parsea la respuesta de transcripción
     */
    private fun parseTranscriptionResponse(response: String): TranscriptionResult {
        return try {
            val cleanJson = response
                .replace("```json", "")
                .replace("```", "")
                .trim()
            
            val jsonMatch = Regex("""\{[\s\S]*\}""").find(cleanJson)
            if (jsonMatch != null) {
                val json = org.json.JSONObject(jsonMatch.value)
                TranscriptionResult(
                    transcription = json.optString("transcripcion", "Sin transcripción"),
                    summary = json.optString("resumen", "Sin resumen"),
                    isSpam = json.optBoolean("es_spam", false),
                    spamConfidence = json.optDouble("confianza_spam", 0.0)
                )
            } else {
                // Si no es JSON, usar la respuesta como transcripción
                TranscriptionResult(
                    transcription = response.take(1000),
                    summary = response.take(100),
                    isSpam = false,
                    spamConfidence = 0.0
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parseando respuesta de transcripción: $response", e)
            TranscriptionResult(
                transcription = response.take(1000),
                summary = "Error al procesar respuesta",
                isSpam = false,
                spamConfidence = 0.0
            )
        }
    }

    /**
     * Verifica si un número de teléfono es probable spam
     */
    suspend fun checkIfSpam(phoneNumber: String): SpamCheckResult {
        return withContext(Dispatchers.IO) {
            try {
                val prompt = buildSpamCheckPrompt(phoneNumber)
                val response = makeApiRequest(prompt)
                parseSpamCheckResponse(response, phoneNumber)
            } catch (e: Exception) {
                Log.e(TAG, "Error checking spam for $phoneNumber", e)
                SpamCheckResult(
                    phoneNumber = phoneNumber,
                    isSpam = false,
                    confidence = 0.0,
                    reason = "Error al verificar: ${e.message}"
                )
            }
        }
    }

    /**
     * Realiza una pregunta general a Gemini
     */
    suspend fun askGemini(prompt: String): String {
        return withContext(Dispatchers.IO) {
            try {
                makeApiRequest(prompt)
            } catch (e: Exception) {
                Log.e(TAG, "Error asking Gemini", e)
                ""
            }
        }
    }

    /**
     * Analiza el contexto de una llamada para determinar intención
     */
    suspend fun analyzeCallContext(
        callerName: String?,
        callerPurpose: String?,
        phoneNumber: String
    ): CallAnalysisResult {
        return withContext(Dispatchers.IO) {
            try {
                val prompt = buildCallAnalysisPrompt(callerName, callerPurpose, phoneNumber)
                val response = makeApiRequest(prompt)
                parseCallAnalysisResponse(response)
            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing call context", e)
                CallAnalysisResult(
                    riskLevel = RiskLevel.UNKNOWN,
                    summary = "No se pudo analizar",
                    recommendation = "Usar criterio propio"
                )
            }
        }
    }

    private fun buildSpamCheckPrompt(phoneNumber: String): String {
        return """
            Analiza el siguiente número de teléfono y determina la probabilidad de que sea spam o una llamada no deseada.
            
            Número: $phoneNumber
            
            Considera:
            1. Patrones comunes de números de spam (prefijos conocidos, secuencias)
            2. Si parece un número de centralita o call center
            3. Formatos típicos de números legítimos vs spam en España
            
            Responde EXACTAMENTE en este formato JSON (sin markdown, sin explicaciones adicionales):
            {
                "isSpam": true/false,
                "confidence": 0.0-1.0,
                "reason": "explicación breve"
            }
        """.trimIndent()
    }

    private fun buildCallAnalysisPrompt(
        callerName: String?,
        callerPurpose: String?,
        phoneNumber: String
    ): String {
        return """
            Analiza la siguiente información de una llamada telefónica y evalúa el nivel de riesgo.
            
            Número: $phoneNumber
            Nombre proporcionado: ${callerName ?: "No proporcionado"}
            Motivo de la llamada: ${callerPurpose ?: "No especificado"}
            
            Evalúa:
            1. Si el motivo parece legítimo o sospechoso
            2. Si hay indicios de estafa, venta agresiva o spam
            3. Si la información proporcionada es coherente
            
            Responde EXACTAMENTE en este formato JSON (sin markdown):
            {
                "riskLevel": "LOW/MEDIUM/HIGH/UNKNOWN",
                "summary": "resumen breve de la situación",
                "recommendation": "qué debería hacer el usuario"
            }
        """.trimIndent()
    }

    private fun makeApiRequest(prompt: String): String {
        if (apiKey.isBlank()) {
            throw IllegalStateException("API key no configurada")
        }

        val url = "$BASE_URL/$MODEL:generateContent?key=$apiKey"

        val requestBody = GeminiRequest(
            contents = listOf(
                Content(
                    parts = listOf(Part(text = prompt))
                )
            ),
            generationConfig = GenerationConfig(
                temperature = 0.3,
                maxOutputTokens = 500
            )
        )

        val jsonBody = gson.toJson(requestBody)
        val mediaType = "application/json".toMediaType()
        val body = jsonBody.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            Log.e(TAG, "API error: ${response.code} - $errorBody")
            throw IOException("API error: ${response.code}")
        }

        val responseBody = response.body?.string() ?: throw IOException("Empty response")
        return extractTextFromResponse(responseBody)
    }

    private fun extractTextFromResponse(jsonResponse: String): String {
        return try {
            val response = gson.fromJson(jsonResponse, GeminiResponse::class.java)
            response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing response: $jsonResponse", e)
            ""
        }
    }

    private fun parseSpamCheckResponse(response: String, phoneNumber: String): SpamCheckResult {
        return try {
            // Limpiar respuesta de posibles caracteres markdown
            val cleanJson = response
                .replace("```json", "")
                .replace("```", "")
                .trim()

            val result = gson.fromJson(cleanJson, SpamCheckJsonResult::class.java)
            
            SpamCheckResult(
                phoneNumber = phoneNumber,
                isSpam = result.isSpam,
                confidence = result.confidence.coerceIn(0.0, 1.0),
                reason = result.reason
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing spam check response: $response", e)
            SpamCheckResult(
                phoneNumber = phoneNumber,
                isSpam = false,
                confidence = 0.0,
                reason = "Error al analizar respuesta"
            )
        }
    }

    private fun parseCallAnalysisResponse(response: String): CallAnalysisResult {
        return try {
            val cleanJson = response
                .replace("```json", "")
                .replace("```", "")
                .trim()

            val result = gson.fromJson(cleanJson, CallAnalysisJsonResult::class.java)
            
            CallAnalysisResult(
                riskLevel = RiskLevel.fromString(result.riskLevel),
                summary = result.summary,
                recommendation = result.recommendation
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing call analysis response: $response", e)
            CallAnalysisResult(
                riskLevel = RiskLevel.UNKNOWN,
                summary = "Error al analizar",
                recommendation = "Usar criterio propio"
            )
        }
    }

    // region Data Classes

    data class SpamCheckResult(
        val phoneNumber: String,
        val isSpam: Boolean,
        val confidence: Double,
        val reason: String
    )

    data class CallAnalysisResult(
        val riskLevel: RiskLevel,
        val summary: String,
        val recommendation: String
    )

    /**
     * Resultado de la transcripción de audio
     */
    data class TranscriptionResult(
        val transcription: String,
        val summary: String,
        val isSpam: Boolean,
        val spamConfidence: Double
    )

    enum class RiskLevel {
        LOW, MEDIUM, HIGH, UNKNOWN;

        companion object {
            fun fromString(value: String): RiskLevel {
                return try {
                    valueOf(value.uppercase())
                } catch (e: Exception) {
                    UNKNOWN
                }
            }
        }
    }

    // Request models
    private data class GeminiRequest(
        val contents: List<Content>,
        val generationConfig: GenerationConfig? = null
    )

    private data class Content(
        val parts: List<Part>
    )

    private data class Part(
        val text: String
    )

    private data class GenerationConfig(
        val temperature: Double = 0.3,
        val maxOutputTokens: Int = 500
    )

    // Response models
    private data class GeminiResponse(
        val candidates: List<Candidate>?
    )

    private data class Candidate(
        val content: ContentResponse?
    )

    private data class ContentResponse(
        val parts: List<PartResponse>?
    )

    private data class PartResponse(
        val text: String?
    )

    // JSON parsing models
    private data class SpamCheckJsonResult(
        @SerializedName("isSpam") val isSpam: Boolean,
        @SerializedName("confidence") val confidence: Double,
        @SerializedName("reason") val reason: String
    )

    private data class CallAnalysisJsonResult(
        @SerializedName("riskLevel") val riskLevel: String,
        @SerializedName("summary") val summary: String,
        @SerializedName("recommendation") val recommendation: String
    )

    // endregion
}
