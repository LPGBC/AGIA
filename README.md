# Linphone Spam Detector

Aplicación Android para detección de llamadas SPAM usando Linphone SDK y Gemini AI.

## Requisitos

- Android Studio Ladybug (2024.2.1) o superior
- JDK 17+
- Android SDK 35 (Android 15)
- API Key de Gemini (Google AI Studio)

## Características

### Detección de SPAM
- Analiza números desconocidos usando Gemini AI
- Ignora automáticamente contactos guardados
- Caché de resultados para evitar llamadas repetidas a la API
- Notificaciones de alerta para posible spam

### Call Screening (Beta)
- Auto-contesta llamadas de números desconocidos
- Usa TTS para preguntar nombre y motivo
- Usa STT para capturar respuestas
- Procesa con Gemini AI y muestra resumen al usuario
- El usuario decide si acepta o rechaza

## Configuración

### 1. Clonar/Descargar el proyecto

### 2. Obtener API Key de Gemini
1. Ve a [Google AI Studio](https://makersuite.google.com/app/apikey)
2. Crea una nueva API key
3. Copia la key

### 3. Compilar
```bash
./gradlew assembleDebug
```

### 4. Instalar
```bash
./gradlew installDebug
```

## Estructura del Proyecto

```
app/
├── src/main/
│   ├── java/com/luisspamdetector/
│   │   ├── api/
│   │   │   └── GeminiApiService.kt      # Cliente API de Gemini
│   │   ├── data/
│   │   │   └── SpamDatabase.kt          # Room DB para caché
│   │   ├── service/
│   │   │   ├── LinphoneService.kt       # Servicio principal Linphone
│   │   │   ├── CallScreeningService.kt  # Servicio de screening
│   │   │   ├── CallReceiver.kt          # Receptor de llamadas
│   │   │   └── BootReceiver.kt          # Receptor de boot
│   │   ├── ui/
│   │   │   ├── IncomingCallActivity.kt      # Alerta de spam
│   │   │   ├── ScreeningOverlayActivity.kt  # UI de screening
│   │   │   └── theme/                       # Tema Material 3
│   │   ├── util/
│   │   │   ├── ContactsHelper.kt        # Utilidades de contactos
│   │   │   └── PermissionsHelper.kt     # Manejo de permisos
│   │   ├── MainActivity.kt              # Pantalla principal
│   │   └── SpamDetectorApp.kt           # Application class
│   ├── res/
│   │   └── ...
│   └── AndroidManifest.xml
├── build.gradle.kts
└── proguard-rules.pro
```

## Versiones

| Componente | Versión |
|------------|---------|
| Linphone SDK | 5.4.72 |
| compileSdk | 35 (Android 15) |
| targetSdk | 35 |
| minSdk | 26 (Android 8.0) |
| Kotlin | 2.0.21 |
| Compose BOM | 2024.12.01 |
| Gradle | 8.10.2 |
| AGP | 8.7.3 |

## Permisos Requeridos

- `READ_PHONE_STATE` - Detectar llamadas entrantes
- `READ_CALL_LOG` - Acceder al registro de llamadas
- `READ_CONTACTS` - Verificar si el número está en contactos
- `RECORD_AUDIO` - Para call screening (TTS/STT)
- `POST_NOTIFICATIONS` - Mostrar notificaciones
- `FOREGROUND_SERVICE_PHONE_CALL` - Servicio en primer plano
- `SYSTEM_ALERT_WINDOW` - Overlay para alertas

## Notas para Android 15

- Los servicios foreground requieren tipos específicos declarados
- El permiso `FOREGROUND_SERVICE_PHONE_CALL` es obligatorio
- La app usa `enableOnBackInvokedCallback` para gesture navigation

## Licencia

Este proyecto está bajo la licencia GPL-3.0 por usar Linphone SDK.

## Créditos

- [Linphone](https://www.linphone.org/) - SDK VoIP
- [Google Gemini](https://ai.google.dev/) - AI para análisis de spam
