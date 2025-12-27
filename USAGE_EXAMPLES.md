# Ejemplos de Uso del Sistema de Grabación

## 📱 Ejemplo 1: Grabación Automática de Todas las Llamadas

```kotlin
class MainActivity : ComponentActivity() {
    
    private lateinit var callHandler: EnhancedCallHandler
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Inicializar handler
        callHandler = EnhancedCallHandler(
            context = this,
            scope = lifecycleScope
        )
        
        // Configurar grabación automática
        getSharedPreferences("settings", MODE_PRIVATE)
            .edit()
            .putBoolean("auto_record_calls", true)
            .putBoolean("show_call_control_ui", true)
            .apply()
    }
}
```

## 📞 Ejemplo 2: Control Manual de Llamada

```kotlin
// En tu servicio o actividad
fun handleManualCall(linphoneCall: Call) {
    val callManager = CallManagerFactory.createLinphoneCallManager(this, linphoneCall)
    
    // Mostrar UI de control
    val intent = Intent(this, CallControlActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
        putExtra(CallControlActivity.EXTRA_PHONE_NUMBER, phoneNumber)
    }
    startActivity(intent)
    
    // Escuchar acciones del usuario desde la UI
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                CallControlActivity.ACTION_ANSWER_CALL -> {
                    callManager.answerCall()
                }
                
                CallControlActivity.ACTION_TOGGLE_RECORDING -> {
                    if (callManager.isRecording()) {
                        val recordingPath = callManager.stopRecording()
                        Toast.makeText(context, "Grabación guardada", Toast.LENGTH_SHORT).show()
                    } else {
                        callManager.startRecording()
                        Toast.makeText(context, "Grabando...", Toast.LENGTH_SHORT).show()
                    }
                }
                
                CallControlActivity.ACTION_HANGUP_CALL -> {
                    callManager.hangupCall()
                }
            }
        }
    }
    
    registerReceiver(receiver, IntentFilter().apply {
        addAction(CallControlActivity.ACTION_ANSWER_CALL)
        addAction(CallControlActivity.ACTION_TOGGLE_RECORDING)
        addAction(CallControlActivity.ACTION_HANGUP_CALL)
    })
}
```

## 🤖 Ejemplo 3: Procesar Grabación con IA

```kotlin
lifecycleScope.launch {
    val database = SpamDatabase.getDatabase(applicationContext)
    val repository = CallRecordingRepository(database.callRecordingDao())
    
    // Obtener grabaciones pendientes de procesar
    val pendingRecordings = repository.getPendingTranscriptions()
    
    for (recording in pendingRecordings) {
        transcriptionService.processRecording(
            recordingPath = recording.recordingPath,
            phoneNumber = recording.phoneNumber,
            duration = recording.duration,
            callback = object : CallTranscriptionService.TranscriptionCallback {
                override fun onTranscriptionStarted(recordingPath: String) {
                    Log.d(TAG, "Procesando: $recordingPath")
                }
                
                override fun onTranscriptionProgress(progress: Int) {
                    // Actualizar UI de progreso
                    updateProgress(progress)
                }
                
                override fun onTranscriptionCompleted(result: TranscriptionResult) {
                    // Guardar resultado
                    lifecycleScope.launch {
                        repository.updateWithTranscription(recording.id, result)
                        
                        // Mostrar resumen al usuario
                        showDialog {
                            title("Resumen de Llamada")
                            message("""
                                Número: ${recording.phoneNumber}
                                Resumen: ${result.summary}
                                Spam: ${if (result.isSpam) "SÍ" else "NO"}
                                Urgencia: ${result.urgency}
                            """.trimIndent())
                        }
                    }
                }
                
                override fun onTranscriptionFailed(error: String) {
                    Log.e(TAG, "Error: $error")
                    lifecycleScope.launch {
                        repository.markTranscriptionFailed(recording.id, error)
                    }
                }
            }
        )
    }
}
```

## 📊 Ejemplo 4: Mostrar Historial de Grabaciones

```kotlin
@Composable
fun RecordingsHistoryScreen() {
    val repository = remember { 
        val db = SpamDatabase.getDatabase(LocalContext.current)
        CallRecordingRepository(db.callRecordingDao())
    }
    
    var recordings by remember { mutableStateOf<List<CallRecordingEntity>>(emptyList()) }
    
    LaunchedEffect(Unit) {
        recordings = repository.getRecentRecordings(limit = 50)
    }
    
    LazyColumn {
        items(recordings) { recording ->
            RecordingCard(
                recording = recording,
                onPlay = { playRecording(recording.recordingPath) },
                onDelete = { 
                    lifecycleScope.launch {
                        repository.deleteRecording(recording.id, deleteFile = true)
                        recordings = repository.getRecentRecordings(limit = 50)
                    }
                }
            )
        }
    }
}

@Composable
fun RecordingCard(
    recording: CallRecordingEntity,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = recording.displayName ?: recording.phoneNumber,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = formatTimestamp(recording.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                
                if (recording.isSpam) {
                    Badge(containerColor = Color.Red) {
                        Text("SPAM", color = Color.White)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Resumen
            if (recording.summary != null) {
                Text(
                    text = recording.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Info adicional
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Duración: ${formatDuration(recording.duration)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Urgencia: ${recording.urgency}",
                    style = MaterialTheme.typography.bodySmall,
                    color = when (recording.urgency) {
                        "CRITICAL" -> Color.Red
                        "HIGH" -> Color(0xFFFF9800)
                        "MEDIUM" -> Color(0xFF2196F3)
                        else -> Color.Gray
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Botones
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onPlay) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Reproducir")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reproducir")
                }
                
                TextButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Eliminar")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Eliminar")
                }
            }
        }
    }
}
```

## 🗂️ Ejemplo 5: Gestión de Almacenamiento

```kotlin
class StorageManager(private val repository: CallRecordingRepository) {
    
    suspend fun getStorageInfo(): StorageInfo {
        val stats = repository.getStorageStats()
        
        return StorageInfo(
            totalRecordings = stats.totalRecordings,
            spamRecordings = stats.spamRecordings,
            storageUsedMB = stats.totalStorageMB,
            oldestRecordingDays = calculateOldestRecordingAge()
        )
    }
    
    suspend fun cleanupOldRecordings(daysToKeep: Int = 30) {
        repository.cleanOldRecordings(
            daysToKeep = daysToKeep,
            deleteFiles = true
        )
        
        Log.i(TAG, "✅ Limpieza completada - manteniendo últimos $daysToKeep días")
    }
    
    suspend fun cleanupSpamRecordings() {
        val spamRecordings = repository.getSpamRecordings()
        
        for (recording in spamRecordings) {
            // Mantener solo 7 días de grabaciones de spam
            val age = System.currentTimeMillis() - recording.timestamp
            val days = age / (24 * 60 * 60 * 1000)
            
            if (days > 7) {
                repository.deleteRecording(recording.id, deleteFile = true)
            }
        }
        
        Log.i(TAG, "✅ Limpieza de spam completada")
    }
    
    suspend fun exportRecording(recordingId: Long, destinationPath: String): Boolean {
        val recording = repository.getRecordingById(recordingId) ?: return false
        
        return try {
            val sourceFile = File(recording.recordingPath)
            val destFile = File(destinationPath)
            
            sourceFile.copyTo(destFile, overwrite = true)
            
            // También exportar metadata como JSON
            val metadataFile = File(destinationPath.replace(".m4a", ".json"))
            metadataFile.writeText(recording.toJson())
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error exportando", e)
            false
        }
    }
}

data class StorageInfo(
    val totalRecordings: Int,
    val spamRecordings: Int,
    val storageUsedMB: Double,
    val oldestRecordingDays: Int
)
```

## 🔍 Ejemplo 6: Búsqueda y Filtrado

```kotlin
@Composable
fun SearchRecordingsScreen() {
    var searchQuery by remember { mutableStateOf("") }
    var filterType by remember { mutableStateOf(FilterType.ALL) }
    var recordings by remember { mutableStateOf<List<CallRecordingEntity>>(emptyList()) }
    
    val repository = remember { 
        CallRecordingRepository(SpamDatabase.getDatabase(LocalContext.current).callRecordingDao())
    }
    
    LaunchedEffect(searchQuery, filterType) {
        recordings = when (filterType) {
            FilterType.ALL -> repository.getRecentRecordings()
            FilterType.SPAM -> repository.getSpamRecordings()
            FilterType.PENDING -> repository.getPendingTranscriptions()
        }.filter { recording ->
            if (searchQuery.isEmpty()) true
            else {
                recording.phoneNumber.contains(searchQuery, ignoreCase = true) ||
                recording.displayName?.contains(searchQuery, ignoreCase = true) == true ||
                recording.summary?.contains(searchQuery, ignoreCase = true) == true ||
                recording.transcription?.contains(searchQuery, ignoreCase = true) == true
            }
        }
    }
    
    Column {
        // Barra de búsqueda
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            placeholder = { Text("Buscar por número, nombre o contenido...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
        )
        
        // Filtros
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filterType == FilterType.ALL,
                onClick = { filterType = FilterType.ALL },
                label = { Text("Todas") }
            )
            FilterChip(
                selected = filterType == FilterType.SPAM,
                onClick = { filterType = FilterType.SPAM },
                label = { Text("Spam") }
            )
            FilterChip(
                selected = filterType == FilterType.PENDING,
                onClick = { filterType = FilterType.PENDING },
                label = { Text("Pendientes") }
            )
        }
        
        // Lista de resultados
        LazyColumn {
            items(recordings) { recording ->
                RecordingCard(recording = recording)
            }
        }
    }
}

enum class FilterType {
    ALL, SPAM, PENDING
}
```

## ⚙️ Ejemplo 7: Configuración de Grabación

```kotlin
@Composable
fun RecordingSettingsScreen() {
    val prefs = LocalContext.current.getSharedPreferences("settings", Context.MODE_PRIVATE)
    
    var autoRecord by remember { 
        mutableStateOf(prefs.getBoolean("auto_record_calls", false)) 
    }
    var recordFormat by remember { 
        mutableStateOf(prefs.getString("record_format", "aac") ?: "aac") 
    }
    var autoTranscribe by remember { 
        mutableStateOf(prefs.getBoolean("auto_transcribe", true)) 
    }
    var retentionDays by remember { 
        mutableStateOf(prefs.getInt("retention_days", 30)) 
    }
    
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Configuración de Grabación", style = MaterialTheme.typography.headlineMedium)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Auto-grabar
        SwitchPreference(
            title = "Grabar automáticamente",
            subtitle = "Graba todas las llamadas entrantes",
            checked = autoRecord,
            onCheckedChange = { 
                autoRecord = it
                prefs.edit().putBoolean("auto_record_calls", it).apply()
            }
        )
        
        // Formato
        ListPreference(
            title = "Formato de audio",
            subtitle = recordFormat.uppercase(),
            options = listOf("aac", "amr", "wav"),
            selected = recordFormat,
            onSelected = {
                recordFormat = it
                prefs.edit().putString("record_format", it).apply()
            }
        )
        
        // Auto-transcribir
        SwitchPreference(
            title = "Transcribir automáticamente",
            subtitle = "Procesa grabaciones con IA",
            checked = autoTranscribe,
            onCheckedChange = { 
                autoTranscribe = it
                prefs.edit().putBoolean("auto_transcribe", it).apply()
            }
        )
        
        // Retención
        SliderPreference(
            title = "Retención de grabaciones",
            subtitle = "$retentionDays días",
            value = retentionDays.toFloat(),
            valueRange = 7f..90f,
            onValueChange = {
                retentionDays = it.toInt()
                prefs.edit().putInt("retention_days", it.toInt()).apply()
            }
        )
    }
}
```

---

Estos ejemplos muestran cómo usar el sistema completo de grabación y análisis con IA. Puedes combinarlos según tus necesidades específicas.
