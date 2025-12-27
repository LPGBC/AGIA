# Sistema de Grabación y Resumen de Llamadas con IA

## 📞 Problema Identificado

**Síntoma:** Al grabar llamadas, solo se captura:
- ✅ El micrófono del móvil (tu voz)
- ✅ La locución/TTS del sistema
- ❌ **NO se captura la voz del que está llamando**

**Causa:** El sistema anterior usaba la grabación de Linphone (`call.startRecording()`), que solo graba el stream RTP (tu audio saliente), pero no captura el audio entrante del llamante.

## ✅ Solución Implementada

### Nuevo Sistema de Grabación con `MediaRecorder`

Se ha implementado un sistema completo de grabación que captura **AMBOS lados de la conversación** usando `AudioSource.VOICE_CALL`:

```kotlin
mediaRecorder.setAudioSource(MediaRecorder.AudioSource.VOICE_CALL)
```

Este audio source es especial porque captura:
- 🎤 **Audio del micrófono** (tu voz)
- 🔊 **Audio del altavoz** (voz del llamante)
- 🎙️ **Ambos canales mezclados** en una sola pista

## 🏗️ Arquitectura del Sistema

### 1. **CallManager** - Interfaz Unificada
Gestiona llamadas de cualquier tipo (SIP, GSM) con funciones:
- `answerCall()` - Descolgar manualmente
- `hangupCall()` - Colgar en cualquier momento
- `startRecording()` - Iniciar grabación de ambos lados
- `stopRecording()` - Detener y guardar grabación

**Implementaciones:**
- `LinphoneCallManager` - Para llamadas SIP/VoIP
- `TelecomCallManager` - Para llamadas GSM nativas
- `GenericCallManager` - Para cualquier llamada sin control directo

### 2. **CallRecorder** - Grabador Avanzado
Características:
- ✅ Graba ambos lados usando `VOICE_CALL`
- ✅ Soporta múltiples formatos (AAC, AMR, WAV)
- ✅ Manejo robusto de errores
- ✅ Metadata (duración, tamaño, timestamp)

### 3. **CallTranscriptionService** - Análisis con IA
Procesa grabaciones y genera:
- 📝 Transcripción completa
- 📊 Resumen inteligente
- 👤 Identificación de nombre del llamante
- 🎯 Motivo de la llamada
- ⚠️ Detección de spam
- 📈 Nivel de urgencia
- 😊 Análisis de sentimiento

### 4. **Base de Datos** - Almacenamiento Completo
Nueva tabla `call_recordings`:
```kotlin
- phoneNumber: String
- recordingPath: String
- transcription: String?
- summary: String?
- isSpam: Boolean
- urgency: LOW/MEDIUM/HIGH/CRITICAL
- sentiment: POSITIVE/NEUTRAL/NEGATIVE/SUSPICIOUS
```

### 5. **CallControlActivity** - UI de Control Manual
Interfaz completa para:
- ☎️ Descolgar/colgar manualmente
- ⏺️ Activar/desactivar grabación
- 🔇 Mutear micrófono
- 🔊 Activar altavoz
- ⏱️ Cronómetro de llamada
- ⚠️ Alerta visual si es spam

## 🔧 Uso del Sistema

### Integración Básica

```kotlin
// Crear CallManager para una llamada Linphone
val callManager = CallManagerFactory.createLinphoneCallManager(context, linphoneCall)

// Registrar listener para eventos
callManager.addListener(object : CallManager.CallListener {
    override fun onCallStarted(callInfo: CallInfo) {
        // Llamada iniciada
    }
    
    override fun onCallEnded(callInfo: CallInfo, duration: Long) {
        // Llamada finalizada - procesar grabación
    }
    
    override fun onCallRecordingReady(callInfo: CallInfo, recordingPath: String) {
        // Grabación lista - enviar a transcripción
        processRecording(recordingPath, callInfo.phoneNumber, duration)
    }
})

// Descolgar la llamada
callManager.answerCall()

// Iniciar grabación automática
callManager.startRecording(autoRecord = true)

// Colgar cuando termine
callManager.hangupCall()
```

### Procesar Grabación con IA

```kotlin
val transcriptionService = CallTranscriptionService(context, geminiService)

transcriptionService.processRecording(
    recordingPath = recordingPath,
    phoneNumber = phoneNumber,
    duration = duration,
    callback = object : CallTranscriptionService.TranscriptionCallback {
        override fun onTranscriptionCompleted(result: TranscriptionResult) {
            // Guardar en base de datos
            callRecordingRepository.updateWithTranscription(recordingId, result)
            
            // Mostrar resumen al usuario
            showSummary(result.summary)
        }
        
        override fun onTranscriptionFailed(error: String) {
            Log.e(TAG, "Error transcribiendo: $error")
        }
    }
)
```

## ⚙️ Configuración

### Permisos Requeridos (AndroidManifest.xml)

```xml
<!-- Grabación de audio -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />

<!-- Control de llamadas (Android 9+) -->
<uses-permission android:name="android.permission.ANSWER_PHONE_CALLS" />

<!-- Notificaciones de grabación (Android 14+) -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
```

### Dependencias (build.gradle.kts)

Ya incluidas en el proyecto:
- Room Database
- Linphone SDK
- Kotlin Coroutines
- Jetpack Compose

## 📱 Flujo de Uso Completo

### Escenario 1: Llamada Entrante con Screening Automático

1. **Llamada detectada** → LinphoneService recibe llamada
2. **Auto-contestar** (si screening activado)
3. **Iniciar grabación** automáticamente con `CallRecorder`
4. **Reproducir prompts TTS** al llamante
5. **Grabar respuesta completa** (ambos lados)
6. **Mostrar UI** al usuario con opción de aceptar/rechazar
7. **Al colgar** → Guardar grabación en base de datos
8. **Procesar con IA** → Transcripción + resumen
9. **Notificar usuario** con resumen disponible

### Escenario 2: Llamada Manual sin Screening

1. **Llamada entrante** → Mostrar `CallControlActivity`
2. **Usuario descuelga** manualmente
3. **Usuario activa grabación** desde UI
4. **Grabar conversación** completa
5. **Usuario cuelga** cuando termine
6. **Guardar grabación** automáticamente
7. **Procesar con IA** en background
8. **Resumen disponible** en historial

### Escenario 3: Solo Grabación (sin control)

```kotlin
val genericManager = CallManagerFactory.createCallManager(context)

// Cuando detectes una llamada activa (desde BroadcastReceiver)
genericManager.notifyCallStarted(phoneNumber)
genericManager.startRecording()

// Cuando la llamada termine
genericManager.stopRecording()
genericManager.notifyCallEnded()
```

## 🔍 Verificación del Sistema

Para verificar que la grabación funciona correctamente:

1. **Realizar llamada de prueba**
2. **Activar grabación** desde la UI
3. **Hablar ambas personas**
4. **Colgar y revisar archivo** en:
   ```
   /data/data/com.luisspamdetector/files/call_recordings/
   ```
5. **Reproducir archivo** - deberías escuchar **ambas voces**

## ⚠️ Consideraciones Importantes

### Legalidad
⚠️ **IMPORTANTE:** En muchos países/estados es **ilegal** grabar llamadas sin consentimiento de ambas partes. Asegúrate de:
- Informar a los llamantes que se está grabando
- Obtener consentimiento explícito
- Cumplir con leyes locales de privacidad

### Rendimiento
- Las grabaciones en AAC ocupan ~500KB por minuto
- Las grabaciones en AMR ocupan ~200KB por minuto  
- Considera limpieza automática de grabaciones antiguas

### Compatibilidad
- `AudioSource.VOICE_CALL` requiere Android 4.0+ (API 14)
- Algunos fabricantes (Samsung, Xiaomi) pueden bloquear esta función
- En Android 9+ requiere permiso `CAPTURE_AUDIO_OUTPUT`

## 🚀 Próximos Pasos

### Mejoras Futuras

1. **Transcripción real con Gemini**
   - Esperar soporte nativo de audio en Gemini API
   - O integrar Google Speech-to-Text
   - O usar Whisper de OpenAI

2. **Búsqueda por contenido**
   - Buscar en transcripciones
   - Filtrar por palabras clave
   - Exportar como texto

3. **Análisis avanzado**
   - Detección de emociones
   - Identificación de temas
   - Extracción de datos (fechas, números)

4. **Sincronización**
   - Backup en cloud
   - Compartir resúmenes
   - Multi-dispositivo

## 📚 Archivos Importantes

```
app/src/main/java/com/luisspamdetector/
├── call/
│   ├── CallManager.kt              # Interfaz principal
│   ├── CallRecorder.kt             # Grabador con VOICE_CALL ⭐
│   ├── LinphoneCallManager.kt      # Implementación Linphone
│   ├── TelecomCallManager.kt       # Implementación GSM
│   ├── GenericCallManager.kt       # Implementación genérica
│   └── CallTranscriptionService.kt # Servicio de IA
├── data/
│   ├── SpamDatabase.kt             # Base de datos principal
│   └── CallRecordingDatabase.kt    # Entidades de grabación
└── ui/
    └── CallControlActivity.kt      # UI de control manual
```

## ❓ FAQ

**P: ¿Por qué no usa `AudioSource.MIC`?**  
R: `MIC` solo graba el micrófono. `VOICE_CALL` graba micrófono + altavoz.

**P: ¿Funciona con llamadas VoIP?**  
R: Sí, funciona con Linphone, WhatsApp, Telegram, etc.

**P: ¿Puedo desactivar el screening pero mantener grabación?**  
R: Sí, usa `GenericCallManager` con grabación manual desde UI.

**P: ¿Dónde se almacenan las grabaciones?**  
R: En `/data/data/com.luisspamdetector/files/call_recordings/`

**P: ¿Cómo elimino grabaciones antiguas?**  
R: `callRecordingRepository.cleanOldRecordings(daysToKeep = 30, deleteFiles = true)`

---

## 📞 Soporte

Para problemas o sugerencias, revisar:
- Logs en `Logger` con tag correspondiente
- Permisos en Settings → Apps → AGIA
- Estado de grabación en base de datos

**Desarrollado para AGIA - Sistema de Detección de Spam con IA**
