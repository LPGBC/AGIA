# AGIA - Detector de SPAM con IA

Aplicación Android para detección de llamadas SPAM usando Linphone SDK y Gemini AI.

## 🚀 Características

### 🛡️ Detección de SPAM
- Analiza números desconocidos usando Gemini AI
- Ignora automáticamente contactos guardados
- Caché de resultados para evitar llamadas repetidas a la API
- Notificaciones de alerta para posible spam

### 📞 Call Screening (Beta)
- Auto-contesta llamadas de números desconocidos
- Usa TTS para preguntar nombre y motivo
- Usa STT para capturar respuestas
- Procesa con Gemini AI y muestra resumen al usuario
- El usuario decide si acepta o rechaza

### 🔧 Configuración SIP (Nuevo)
- Interfaz integrada para configurar cuenta SIP
- Soporte para llamadas VoIP
- Registro automático al iniciar el servicio

### 📝 Sistema de Logs (Nuevo)
- Logs centralizados con Logger personalizado
- Visor de logs integrado en la app
- Exportar logs para depuración
- Logs automáticos en archivo y logcat

## 📋 Requisitos

- Android Studio Ladybug (2024.2.1) o superior
- JDK 17+
- Android SDK 35 (Android 15)
- API Key de Gemini (Google AI Studio)
- Cuenta SIP (opcional, para VoIP)

## 🔑 Configuración

### 1. Obtener API Key de Gemini
1. Ve a [Google AI Studio](https://makersuite.google.com/app/apikey)
2. Crea una nueva API key
3. Copia la key
4. Pégala en la pestaña "Principal" de la app

### 2. Configurar Cuenta SIP (Opcional)
1. Ve a la pestaña "SIP" en la app
2. Ingresa:
   - Usuario SIP
   - Contraseña
   - Servidor/Dominio (ej: sip.example.com)
3. Presiona "Guardar"

### 3. Permisos
La app requiere:
- ✅ Teléfono (READ_PHONE_STATE, READ_CALL_LOG)
- ✅ Contactos (READ_CONTACTS)
- ✅ Overlay (SYSTEM_ALERT_WINDOW) - para call screening
- ✅ Optimización de batería desactivada - para servicio persistente
- ✅ Almacenamiento - para logs

## 🏗️ Compilar e Instalar

### Desde la terminal:
```bash
# Compilar
gradle assembleDebug

# El APK estará en:
# app/build/outputs/apk/debug/app-debug.apk
```

### Desde Android Studio:
1. Abre el proyecto
2. Build > Build Bundle(s) / APK(s) > Build APK(s)

## 📱 Uso

### Pestaña Principal
1. Configura tu API key de Gemini
2. Concede los permisos necesarios
3. Activa el servicio de protección
4. Configura las opciones de detección:
   - **Detección de SPAM**: Analiza números desconocidos
   - **Call Screening**: Auto-contesta y filtra llamadas

### Pestaña SIP
1. Ingresa las credenciales de tu cuenta SIP
2. Guarda la configuración
3. El servicio se conectará automáticamente cuando esté activo

### Pestaña Logs
1. Visualiza los logs de depuración en tiempo real
2. Comparte logs para reportar problemas
3. Limpia logs antiguos
4. Actualiza para ver logs recientes

## 🐛 Depuración

Si la aplicación se cierra inesperadamente:

1. Ve a la pestaña **Logs**
2. Presiona el botón **Actualizar**
3. Revisa los mensajes de error
4. Comparte los logs usando el botón **Compartir**

Los logs incluyen:
- ❌ Errores y excepciones con stack traces completos
- ⚠️ Advertencias
- ℹ️ Información de estado
- 🐞 Mensajes de depuración

## 🏗️ Estructura del Proyecto

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
│   │   │   ├── PermissionsHelper.kt     # Manejo de permisos
│   │   │   └── Logger.kt                # Sistema de logging (NUEVO)
│   │   ├── MainActivity.kt              # Pantalla principal con tabs
│   │   └── SpamDetectorApp.kt           # Application class
│   ├── res/
│   │   └── ...
│   └── AndroidManifest.xml
├── build.gradle.kts
└── proguard-rules.pro
```

## 📦 Versiones

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
