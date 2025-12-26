package com.luisspamdetector.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
        private const val MODEL = "gemini-1.5-flash"
        private const val TIMEOUT_SECONDS = 30L
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
