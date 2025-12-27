package com.luisspamdetector

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luisspamdetector.data.ScreeningHistoryEntity
import com.luisspamdetector.data.ScreeningHistoryRepository
import com.luisspamdetector.data.SpamDatabase
import com.luisspamdetector.service.LinphoneService
import com.luisspamdetector.ui.theme.LinphoneSpamDetectorTheme
import com.luisspamdetector.util.Logger
import com.luisspamdetector.util.PermissionsHelper
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            // Permisos concedidos - forzar recomposición
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            LinphoneSpamDetectorTheme {
                MainScreen(
                    onRequestPermissions = { requestPermissions() },
                    onRequestOverlayPermission = { 
                        PermissionsHelper.requestOverlayPermission(this)
                    },
                    onRequestBatteryOptimization = { 
                        PermissionsHelper.requestBatteryOptimizationExemption(this) 
                    }
                )
            }
        }
    }

    private fun requestPermissions() {
        val missing = PermissionsHelper.getMissingPermissions(this)
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // onResume ya no es necesario con DisposableEffect y recreate()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onRequestPermissions: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestBatteryOptimization: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    var apiKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "") ?: "") }
    var showApiKey by remember { mutableStateOf(false) }
    var serviceEnabled by remember { mutableStateOf(prefs.getBoolean("service_enabled", false)) }
    var spamDetectionEnabled by remember { mutableStateOf(prefs.getBoolean("spam_detection_enabled", true)) }
    var callScreeningEnabled by remember { mutableStateOf(prefs.getBoolean("call_screening_enabled", false)) }
    var testModeEnabled by remember { mutableStateOf(prefs.getBoolean("test_mode_enabled", false)) }
    
    // Variables SIP
    var sipUsername by remember { mutableStateOf(prefs.getString("sip_username", "") ?: "") }
    var sipPassword by remember { mutableStateOf(prefs.getString("sip_password", "") ?: "") }
    var sipDomain by remember { mutableStateOf(prefs.getString("sip_domain", "") ?: "") }
    var sipProtocol by remember { mutableStateOf(prefs.getString("sip_protocol", "UDP") ?: "UDP") }
    var showSipPassword by remember { mutableStateOf(false) }
    var sipConfigured by remember { mutableStateOf(prefs.getBoolean("sip_configured", false)) }
    var sipRegistered by remember { mutableStateOf(false) }
    
    // Tab seleccionada
    var selectedTab by remember { mutableStateOf(0) }
    
    // Estado de permisos
    var hasAllPermissions by remember { mutableStateOf(PermissionsHelper.hasAllPermissions(context)) }
    var hasOverlayPermission by remember { mutableStateOf(PermissionsHelper.hasOverlayPermission(context)) }
    var hasBatteryExemption by remember { mutableStateOf(PermissionsHelper.isIgnoringBatteryOptimizations(context)) }

    // BroadcastReceiver para estado de registro SIP
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == LinphoneService.ACTION_REGISTRATION_STATE_CHANGED) {
                    sipRegistered = intent.getBooleanExtra(LinphoneService.EXTRA_IS_REGISTERED, false)
                }
            }
        }
        
        val filter = android.content.IntentFilter(LinphoneService.ACTION_REGISTRATION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    // Actualizar estados de permisos cuando la app regresa (onResume)
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasAllPermissions = PermissionsHelper.hasAllPermissions(context)
                hasOverlayPermission = PermissionsHelper.hasOverlayPermission(context)
                hasBatteryExemption = PermissionsHelper.isIgnoringBatteryOptimizations(context)
            }
        }
        
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Actualizar estados iniciales
    LaunchedEffect(Unit) {
        hasAllPermissions = PermissionsHelper.hasAllPermissions(context)
        hasOverlayPermission = PermissionsHelper.hasOverlayPermission(context)
        hasBatteryExemption = PermissionsHelper.isIgnoringBatteryOptimizations(context)
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            "Detector de SPAM",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
                
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Principal") },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Historial") },
                        icon = { Icon(Icons.Default.History, contentDescription = null) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("SIP") },
                        icon = { Icon(Icons.Default.Phone, contentDescription = null) }
                    )
                    Tab(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        text = { Text("Logs") },
                        icon = { Icon(Icons.Default.Description, contentDescription = null) }
                    )
                }
            }
        }
    ) { paddingValues ->
        when (selectedTab) {
            0 -> MainTab(
                paddingValues = paddingValues,
                serviceEnabled = serviceEnabled,
                apiKey = apiKey,
                showApiKey = showApiKey,
                spamDetectionEnabled = spamDetectionEnabled,
                callScreeningEnabled = callScreeningEnabled,
                testModeEnabled = testModeEnabled,
                hasAllPermissions = hasAllPermissions,
                hasOverlayPermission = hasOverlayPermission,
                hasBatteryExemption = hasBatteryExemption,
                onServiceToggle = onServiceToggle@{ enabled ->
                    if (enabled && apiKey.isBlank()) {
                        Toast.makeText(context, "Configura la API key primero", Toast.LENGTH_SHORT).show()
                        return@onServiceToggle
                    }
                    
                    if (enabled && !hasAllPermissions) {
                        Toast.makeText(context, "Concede los permisos primero", Toast.LENGTH_SHORT).show()
                        return@onServiceToggle
                    }

                    serviceEnabled = enabled
                    prefs.edit().putBoolean("service_enabled", enabled).apply()

                    val intent = Intent(context, LinphoneService::class.java)
                    if (enabled) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                    } else {
                        context.stopService(intent)
                    }
                },
                onApiKeyChange = { newKey ->
                    apiKey = newKey
                    prefs.edit().putString("gemini_api_key", newKey).apply()
                },
                onToggleApiKeyVisibility = { showApiKey = !showApiKey },
                onSpamDetectionChange = { enabled ->
                    spamDetectionEnabled = enabled
                    prefs.edit().putBoolean("spam_detection_enabled", enabled).apply()
                },
                onCallScreeningChange = { enabled ->
                    if (enabled && !hasOverlayPermission) {
                        Toast.makeText(context, "Concede permiso de overlay primero", Toast.LENGTH_SHORT).show()
                        return@MainTab
                    }
                    callScreeningEnabled = enabled
                    prefs.edit().putBoolean("call_screening_enabled", enabled).apply()
                },
                onTestModeChange = { enabled ->
                    testModeEnabled = enabled
                    prefs.edit().putBoolean("test_mode_enabled", enabled).apply()
                },
                onRequestPermissions = {
                    onRequestPermissions()
                    hasAllPermissions = PermissionsHelper.hasAllPermissions(context)
                },
                onRequestOverlay = onRequestOverlayPermission,
                onRequestBattery = onRequestBatteryOptimization
            )
            1 -> HistoryTab(paddingValues = paddingValues)
            2 -> SipConfigTab(
                paddingValues = paddingValues,
                sipUsername = sipUsername,
                sipPassword = sipPassword,
                sipDomain = sipDomain,
                sipProtocol = sipProtocol,
                showSipPassword = showSipPassword,
                sipConfigured = sipConfigured,
                sipRegistered = sipRegistered,
                onUsernameChange = { sipUsername = it },
                onPasswordChange = { sipPassword = it },
                onDomainChange = { sipDomain = it },
                onProtocolChange = { sipProtocol = it },
                onTogglePasswordVisibility = { showSipPassword = !showSipPassword },
                onSave = {
                    if (sipUsername.isBlank() || sipPassword.isBlank() || sipDomain.isBlank()) {
                        Toast.makeText(context, "Completa todos los campos", Toast.LENGTH_SHORT).show()
                        return@SipConfigTab
                    }
                    
                    prefs.edit().apply {
                        putString("sip_username", sipUsername)
                        putString("sip_password", sipPassword)
                        putString("sip_domain", sipDomain)
                        putString("sip_protocol", sipProtocol)
                        putBoolean("sip_configured", true)
                        apply()
                    }
                    sipConfigured = true
                    
                    // Si el servicio está corriendo, reiniciarlo con la nueva config
                    if (LinphoneService.isRunning) {
                        val intent = Intent(context, LinphoneService::class.java)
                        context.stopService(intent)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                    }
                    
                    Toast.makeText(context, "Configuración SIP guardada", Toast.LENGTH_SHORT).show()
                },
                onClear = {
                    prefs.edit().apply {
                        remove("sip_username")
                        remove("sip_password")
                        remove("sip_domain")
                        remove("sip_protocol")
                        putBoolean("sip_configured", false)
                        apply()
                    }
                    sipUsername = ""
                    sipPassword = ""
                    sipDomain = ""
                    sipProtocol = "UDP"
                    sipConfigured = false
                    sipRegistered = false
                    Toast.makeText(context, "Configuración SIP eliminada", Toast.LENGTH_SHORT).show()
                }
            )
            3 -> LogsTab(paddingValues = paddingValues)
        }
    }
}

@Composable
fun MainTab(
    paddingValues: PaddingValues,
    serviceEnabled: Boolean,
    apiKey: String,
    showApiKey: Boolean,
    spamDetectionEnabled: Boolean,
    callScreeningEnabled: Boolean,
    testModeEnabled: Boolean,
    hasAllPermissions: Boolean,
    hasOverlayPermission: Boolean,
    hasBatteryExemption: Boolean,
    onServiceToggle: (Boolean) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onToggleApiKeyVisibility: () -> Unit,
    onSpamDetectionChange: (Boolean) -> Unit,
    onCallScreeningChange: (Boolean) -> Unit,
    onTestModeChange: (Boolean) -> Unit,
    onRequestPermissions: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestBattery: () -> Unit
) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Estado del servicio
            ServiceStatusCard(
                isRunning = LinphoneService.isRunning,
                isEnabled = serviceEnabled,
                apiKeyConfigured = apiKey.isNotBlank(),
                onToggle = onServiceToggle
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Permisos
            PermissionsCard(
                hasAllPermissions = hasAllPermissions,
                hasOverlayPermission = hasOverlayPermission,
                hasBatteryExemption = hasBatteryExemption,
                onRequestPermissions = onRequestPermissions,
                onRequestOverlay = onRequestOverlay,
                onRequestBattery = onRequestBattery
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Configuración de API
            ApiConfigCard(
                apiKey = apiKey,
                showApiKey = showApiKey,
                onApiKeyChange = onApiKeyChange,
                onToggleVisibility = onToggleApiKeyVisibility
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Opciones de detección
            DetectionOptionsCard(
                spamDetectionEnabled = spamDetectionEnabled,
                callScreeningEnabled = callScreeningEnabled,
                testModeEnabled = testModeEnabled,
                onSpamDetectionChange = onSpamDetectionChange,
                onCallScreeningChange = onCallScreeningChange,
                onTestModeChange = onTestModeChange
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Información
            InfoCard()
        }
}

@Composable
fun ServiceStatusCard(
    isRunning: Boolean,
    isEnabled: Boolean,
    apiKeyConfigured: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) 
                Color(0xFF1a472a).copy(alpha = 0.3f) 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (isRunning) Color(0xFF4ecca3) else Color.Gray
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Shield else Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = if (isRunning) "Protección Activa" else "Protección Inactiva",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        text = if (!apiKeyConfigured) "Configura API key" 
                               else if (isRunning) "Monitoreando llamadas" 
                               else "Toca para activar",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF4ecca3)
                )
            )
        }
    }
}

@Composable
fun PermissionsCard(
    hasAllPermissions: Boolean,
    hasOverlayPermission: Boolean,
    hasBatteryExemption: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestBattery: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "Permisos",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            PermissionItem(
                icon = Icons.Outlined.Phone,
                title = "Permisos básicos",
                subtitle = "Teléfono, contactos, micrófono",
                isGranted = hasAllPermissions,
                onClick = onRequestPermissions
            )

            Spacer(modifier = Modifier.height(12.dp))

            PermissionItem(
                icon = Icons.Outlined.Layers,
                title = "Mostrar sobre otras apps",
                subtitle = "Para alertas de spam",
                isGranted = hasOverlayPermission,
                onClick = onRequestOverlay
            )

            Spacer(modifier = Modifier.height(12.dp))

            PermissionItem(
                icon = Icons.Outlined.BatteryChargingFull,
                title = "Optimización de batería",
                subtitle = "Mantener servicio activo",
                isGranted = hasBatteryExemption,
                onClick = onRequestBattery
            )
        }
    }
}

@Composable
fun PermissionItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !isGranted, onClick = onClick)
            .background(
                if (isGranted) Color(0xFF4ecca3).copy(alpha = 0.1f)
                else MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isGranted) Color(0xFF4ecca3) else MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Icon(
            imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null,
            tint = if (isGranted) Color(0xFF4ecca3) else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
fun ApiConfigCard(
    apiKey: String,
    showApiKey: Boolean,
    onApiKeyChange: (String) -> Unit,
    onToggleVisibility: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "API Key de Gemini",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Obtén tu API key en Google AI Studio",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key") },
                placeholder = { Text("AIza...") },
                singleLine = true,
                visualTransformation = if (showApiKey) 
                    VisualTransformation.None 
                else 
                    PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = onToggleVisibility) {
                        Icon(
                            imageVector = if (showApiKey) 
                                Icons.Default.VisibilityOff 
                            else 
                                Icons.Default.Visibility,
                            contentDescription = if (showApiKey) "Ocultar" else "Mostrar"
                        )
                    }
                },
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}

@Composable
fun DetectionOptionsCard(
    spamDetectionEnabled: Boolean,
    callScreeningEnabled: Boolean,
    testModeEnabled: Boolean,
    onSpamDetectionChange: (Boolean) -> Unit,
    onCallScreeningChange: (Boolean) -> Unit,
    onTestModeChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "Opciones de Detección",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Detección de spam
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Detección de SPAM",
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Analiza números desconocidos con IA",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = spamDetectionEnabled,
                    onCheckedChange = onSpamDetectionChange
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Divider()
            Spacer(modifier = Modifier.height(16.dp))

            // Call screening
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Call Screening",
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "BETA",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        text = "Auto-contesta y pregunta quién llama",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = callScreeningEnabled,
                    onCheckedChange = onCallScreeningChange
                )
            }

            // Modo de prueba (solo visible si call screening está activado)
            if (callScreeningEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Science,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Modo Prueba",
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp
                            )
                        }
                        Text(
                            text = "Screening de TODAS las llamadas (incluye contactos)",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Checkbox(
                        checked = testModeEnabled,
                        onCheckedChange = onTestModeChange
                    )
                }
            }
        }
    }
}

@Composable
fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Cómo funciona",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Cuando recibes una llamada de un número desconocido, " +
                           "la app usa Gemini AI para analizar si es probable que sea spam. " +
                           "Los contactos guardados nunca se analizan.",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun SipConfigTab(
    paddingValues: PaddingValues,
    sipUsername: String,
    sipPassword: String,
    sipDomain: String,
    sipProtocol: String,
    showSipPassword: Boolean,
    sipConfigured: Boolean,
    sipRegistered: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDomainChange: (String) -> Unit,
    onProtocolChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Configuración de Cuenta SIP",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    
                    // Indicador de estado de registro
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (sipRegistered) Color(0xFF4ecca3) 
                                    else if (sipConfigured) Color(0xFFFF9800)
                                    else MaterialTheme.colorScheme.error
                                )
                        )
                        Text(
                            text = if (sipRegistered) "Registrado" 
                                   else if (sipConfigured) "Configurado"
                                   else "Sin configurar",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Configura tu cuenta SIP para recibir llamadas VoIP",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = sipUsername,
                    onValueChange = onUsernameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Usuario SIP") },
                    placeholder = { Text("ej: 1234") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.Person, contentDescription = null)
                    },
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = sipPassword,
                    onValueChange = onPasswordChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Contraseña") },
                    singleLine = true,
                    visualTransformation = if (showSipPassword)
                        VisualTransformation.None
                    else
                        PasswordVisualTransformation(),
                    leadingIcon = {
                        Icon(Icons.Default.Lock, contentDescription = null)
                    },
                    trailingIcon = {
                        IconButton(onClick = onTogglePasswordVisibility) {
                            Icon(
                                imageVector = if (showSipPassword)
                                    Icons.Default.VisibilityOff
                                else
                                    Icons.Default.Visibility,
                                contentDescription = if (showSipPassword) "Ocultar" else "Mostrar"
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = sipDomain,
                    onValueChange = onDomainChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Servidor/Dominio") },
                    placeholder = { Text("ej: sip.example.com") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.Cloud, contentDescription = null)
                    },
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Selector de protocolo
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Protocolo de transporte",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("UDP", "TCP", "TLS").forEach { protocol ->
                            FilterChip(
                                selected = sipProtocol == protocol,
                                onClick = { onProtocolChange(protocol) },
                                label = { Text(protocol) },
                                leadingIcon = if (sipProtocol == protocol) {
                                    {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                } else null
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (sipConfigured) {
                        OutlinedButton(
                            onClick = onClear,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Limpiar")
                        }
                    }
                    
                    Button(
                        onClick = onSave,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4ecca3)
                        )
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Guardar", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Información sobre SIP",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Configura aquí tu cuenta SIP para recibir llamadas VoIP. " +
                               "Necesitas obtener estas credenciales de tu proveedor SIP. " +
                               "El servicio se conectará automáticamente cuando esté habilitado.",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
fun LogsTab(paddingValues: PaddingValues) {
    val context = LocalContext.current
    var logContent by remember { mutableStateOf("Cargando logs...") }
    var logFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedLogFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val files = Logger.getAllLogFiles(context)
            withContext(Dispatchers.Main) {
                logFiles = files
                if (files.isNotEmpty()) {
                    selectedLogFile = files.first()
                    logContent = files.first().readText()
                } else {
                    logContent = "No hay archivos de log disponibles"
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Logs de Depuración",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = {
                                selectedLogFile?.let { file ->
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "AGIA Log - ${file.name}")
                                        putExtra(Intent.EXTRA_TEXT, logContent)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Compartir log"))
                                }
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Compartir")
                        }

                        IconButton(
                            onClick = {
                                Logger.clearLogs(context)
                                logContent = "Logs limpiados"
                                logFiles = emptyList()
                                selectedLogFile = null
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Limpiar")
                        }

                        IconButton(
                            onClick = {
                                GlobalScope.launch(Dispatchers.IO) {
                                    val files = Logger.getAllLogFiles(context)
                                    withContext(Dispatchers.Main) {
                                        logFiles = files
                                        if (files.isNotEmpty()) {
                                            selectedLogFile = files.first()
                                            logContent = files.first().readText()
                                        }
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Actualizar")
                        }
                    }
                }

                if (logFiles.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = "Archivo: ${selectedLogFile?.name ?: "Ninguno"}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(
                    text = logContent,
                    fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun HistoryTab(paddingValues: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { SpamDatabase.getDatabase(context) }
    val historyRepository = remember { ScreeningHistoryRepository(db.screeningHistoryDao()) }
    
    var historyItems by remember { mutableStateOf<List<ScreeningHistoryEntity>>(emptyList()) }
    var expandedItemId by remember { mutableStateOf<Long?>(null) }
    var playingItemId by remember { mutableStateOf<Long?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    
    // Load history on first composition
    LaunchedEffect(Unit) {
        historyItems = historyRepository.getRecentScreenings(100)
    }
    
    // Cleanup media player
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
        }
    }
    
    fun playRecording(item: ScreeningHistoryEntity) {
        if (item.recordingPath.isNullOrEmpty()) return
        
        val file = File(item.recordingPath)
        if (!file.exists()) {
            Toast.makeText(context, "Grabación no encontrada", Toast.LENGTH_SHORT).show()
            return
        }
        
        scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    mediaPlayer?.release()
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(item.recordingPath)
                        prepare()
                        start()
                        setOnCompletionListener {
                            playingItemId = null
                        }
                    }
                    playingItemId = item.id
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error reproduciendo: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    fun stopPlaying() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        playingItemId = null
    }
    
    fun deleteItem(item: ScreeningHistoryEntity) {
        scope.launch(Dispatchers.IO) {
            // Delete recording file if exists
            item.recordingPath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
            
            historyRepository.deleteScreening(item.id)
            historyItems = historyRepository.getRecentScreenings(100)
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp)
    ) {
        Text(
            text = "Historial de Screening",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "${historyItems.size} llamadas analizadas",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (historyItems.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No hay historial de llamadas",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(historyItems) { item ->
                    ScreeningHistoryItem(
                        item = item,
                        isExpanded = expandedItemId == item.id,
                        isPlaying = playingItemId == item.id,
                        dateFormat = dateFormat,
                        onExpandToggle = {
                            expandedItemId = if (expandedItemId == item.id) null else item.id
                        },
                        onPlay = { playRecording(item) },
                        onStop = { stopPlaying() },
                        onDelete = { deleteItem(item) }
                    )
                }
            }
        }
    }
}

@Composable
fun ScreeningHistoryItem(
    item: ScreeningHistoryEntity,
    isExpanded: Boolean,
    isPlaying: Boolean,
    dateFormat: SimpleDateFormat,
    onExpandToggle: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onExpandToggle() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isSpam) 
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.callerName ?: item.phoneNumber,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (item.callerName != null) {
                        Text(
                            text = item.phoneNumber,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Spam indicator
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                if (item.isSpam) Color.Red else Color.Green,
                                shape = CircleShape
                            )
                    )
                    
                    // Accepted/Rejected icon
                    Icon(
                        imageVector = if (item.wasAccepted) Icons.Default.Call else Icons.Default.Close,
                        contentDescription = if (item.wasAccepted) "Aceptada" else "Rechazada",
                        modifier = Modifier.size(20.dp),
                        tint = if (item.wasAccepted) Color.Green else Color.Red
                    )
                    
                    // Expand/collapse icon
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Colapsar" else "Expandir",
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            
            // Date and duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = dateFormat.format(Date(item.timestamp)),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                
                if (item.duration != null && item.duration > 0) {
                    Text(
                        text = "${item.duration}s",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            
            // Caller purpose
            if (!item.callerPurpose.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Propósito: ${item.callerPurpose}",
                    fontSize = 13.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
            
            // Expanded content
            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))
                
                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Spam analysis
                if (item.isSpam) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Spam",
                            tint = Color.Red,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Spam detectado (${(item.spamConfidence * 100).toInt()}% confianza)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.Red
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                // Summary (resumen breve)
                if (!item.summary.isNullOrBlank()) {
                    Text(
                        text = "Resumen:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.summary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                // Transcription
                Text(
                    text = "Transcripción:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.transcription?.ifEmpty { "Sin transcripción disponible" } ?: "Sin transcripción disponible",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Play/Stop button
                    if (!item.recordingPath.isNullOrEmpty()) {
                        OutlinedButton(
                            onClick = { if (isPlaying) onStop() else onPlay() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Close else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Detener" else "Reproducir",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isPlaying) "Detener" else "Reproducir")
                        }
                    }
                    
                    // Delete button
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.Red
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Eliminar",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Eliminar")
                    }
                }
            }
        }
    }
}

