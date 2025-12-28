package com.luisspamdetector.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luisspamdetector.call.CallManager
import com.luisspamdetector.service.LinphoneService
import com.luisspamdetector.ui.theme.LinphoneSpamDetectorTheme
import com.luisspamdetector.util.Logger
import kotlinx.coroutines.delay

/**
 * Actividad para control manual de llamadas con UI completa.
 * Permite descolgar, colgar, grabar, mutear, etc.
 */
class CallControlActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CallControlActivity"
        
        const val EXTRA_PHONE_NUMBER = "phone_number"
        const val EXTRA_DISPLAY_NAME = "display_name"
        const val EXTRA_IS_SPAM = "is_spam"
        const val EXTRA_SPAM_CONFIDENCE = "spam_confidence"
        const val EXTRA_SPAM_REASON = "spam_reason"
        
        // Acciones broadcast (mantenidas por compatibilidad)
        const val ACTION_ANSWER_CALL = "com.luisspamdetector.ACTION_ANSWER_CALL"
        const val ACTION_HANGUP_CALL = "com.luisspamdetector.ACTION_HANGUP_CALL"
        const val ACTION_TOGGLE_RECORDING = "com.luisspamdetector.ACTION_TOGGLE_RECORDING"
        const val ACTION_TOGGLE_MUTE = "com.luisspamdetector.ACTION_TOGGLE_MUTE"
        const val ACTION_TOGGLE_SPEAKER = "com.luisspamdetector.ACTION_TOGGLE_SPEAKER"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupWindow()

        val phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: "Desconocido"
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)
        val isSpam = intent.getBooleanExtra(EXTRA_IS_SPAM, false)
        val spamConfidence = intent.getDoubleExtra(EXTRA_SPAM_CONFIDENCE, 0.0)
        val spamReason = intent.getStringExtra(EXTRA_SPAM_REASON)

        setContent {
            LinphoneSpamDetectorTheme {
                CallControlScreen(
                    phoneNumber = phoneNumber,
                    displayName = displayName,
                    isSpam = isSpam,
                    spamConfidence = spamConfidence,
                    spamReason = spamReason,
                    onAnswer = { answerCall() },
                    onHangup = { 
                        hangupCall()
                        finish()
                    },
                    onToggleRecording = { toggleRecording() },
                    onToggleMute = { toggleMute() },
                    onToggleSpeaker = { toggleSpeaker() }
                )
            }
        }
    }
    
    /**
     * Contesta la llamada usando directamente el servicio
     */
    private fun answerCall() {
        Logger.i(TAG, "Intentando contestar llamada...")
        val service = LinphoneService.instance
        if (service != null) {
            service.answerCurrentCall()
            Logger.i(TAG, "Llamada contestada via servicio")
        } else {
            Logger.e(TAG, "LinphoneService no disponible para contestar")
        }
    }
    
    /**
     * Cuelga la llamada usando directamente el servicio
     */
    private fun hangupCall() {
        Logger.i(TAG, "Intentando colgar llamada...")
        val service = LinphoneService.instance
        if (service != null) {
            service.hangupCurrentCall()
            Logger.i(TAG, "Llamada colgada via servicio")
        } else {
            Logger.e(TAG, "LinphoneService no disponible para colgar")
        }
    }
    
    /**
     * Alterna la grabación
     */
    private fun toggleRecording() {
        Logger.i(TAG, "Toggle grabación...")
        val service = LinphoneService.instance
        if (service != null) {
            service.toggleCurrentCallRecording()
        } else {
            Logger.e(TAG, "LinphoneService no disponible para toggle recording")
        }
    }
    
    /**
     * Alterna el mute del micrófono
     */
    private fun toggleMute() {
        Logger.i(TAG, "Toggle mute...")
        val service = LinphoneService.instance
        if (service != null) {
            service.toggleCurrentCallMute()
        } else {
            Logger.e(TAG, "LinphoneService no disponible para toggle mute")
        }
    }
    
    /**
     * Alterna el altavoz
     */
    private fun toggleSpeaker() {
        Logger.i(TAG, "Toggle speaker...")
        val service = LinphoneService.instance
        if (service != null) {
            service.toggleSpeaker()
        } else {
            Logger.e(TAG, "LinphoneService no disponible para toggle speaker")
        }
    }

    private fun setupWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }
}

@Composable
fun CallControlScreen(
    phoneNumber: String,
    displayName: String?,
    isSpam: Boolean,
    spamConfidence: Double,
    spamReason: String?,
    onAnswer: () -> Unit,
    onHangup: () -> Unit,
    onToggleRecording: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit
) {
    var callState by remember { mutableStateOf(CallManager.CallState.RINGING) }
    var isRecording by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(false) }
    var callDuration by remember { mutableStateOf(0L) }

    // Timer para duración de llamada
    LaunchedEffect(callState) {
        if (callState == CallManager.CallState.ACTIVE) {
            while (true) {
                delay(1000)
                callDuration++
            }
        }
    }

    val backgroundColor = if (isSpam) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF8B0000),
                Color(0xFF4a0000)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF1a237e),
                Color(0xFF000051)
            )
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Header: Estado de spam
                if (isSpam) {
                    SpamWarningBanner(spamConfidence, spamReason)
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Información del llamante
                CallerInfo(
                    phoneNumber = phoneNumber,
                    displayName = displayName,
                    callState = callState,
                    callDuration = callDuration
                )

                Spacer(modifier = Modifier.weight(1f))

                // Indicadores de estado
                StatusIndicators(
                    isRecording = isRecording,
                    isMuted = isMuted,
                    isSpeakerOn = isSpeakerOn
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Controles de llamada
                if (callState == CallManager.CallState.RINGING) {
                    // Botones de contestar/rechazar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Botón rechazar
                        CallActionButton(
                            icon = Icons.Default.CallEnd,
                            contentDescription = "Rechazar",
                            backgroundColor = Color(0xFFD32F2F),
                            onClick = onHangup
                        )

                        // Botón contestar
                        CallActionButton(
                            icon = Icons.Default.Call,
                            contentDescription = "Contestar",
                            backgroundColor = Color(0xFF388E3C),
                            onClick = {
                                callState = CallManager.CallState.ACTIVE
                                onAnswer()
                            }
                        )
                    }
                } else if (callState == CallManager.CallState.ACTIVE) {
                    // Controles durante llamada
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Fila de controles secundarios
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            // Grabar
                            SecondaryCallButton(
                                icon = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                                label = "Grabar",
                                isActive = isRecording,
                                onClick = {
                                    isRecording = !isRecording
                                    onToggleRecording()
                                }
                            )

                            // Mutear
                            SecondaryCallButton(
                                icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                label = if (isMuted) "Activar" else "Mutear",
                                isActive = isMuted,
                                onClick = {
                                    isMuted = !isMuted
                                    onToggleMute()
                                }
                            )

                            // Altavoz
                            SecondaryCallButton(
                                icon = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                                label = "Altavoz",
                                isActive = isSpeakerOn,
                                onClick = {
                                    isSpeakerOn = !isSpeakerOn
                                    onToggleSpeaker()
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        // Botón colgar
                        CallActionButton(
                            icon = Icons.Default.CallEnd,
                            contentDescription = "Colgar",
                            backgroundColor = Color(0xFFD32F2F),
                            onClick = {
                                callState = CallManager.CallState.ENDED
                                onHangup()
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun SpamWarningBanner(confidence: Double, reason: String?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFF6B6B).copy(alpha = 0.9f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Advertencia",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "⚠️ POSIBLE SPAM",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Confianza: ${(confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.9f)
                )
                reason?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 2
                    )
                }
            }
        }
    }
}

@Composable
fun CallerInfo(
    phoneNumber: String,
    displayName: String?,
    callState: CallManager.CallState,
    callDuration: Long
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Nombre o número
        Text(
            text = displayName ?: phoneNumber,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        if (displayName != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = phoneNumber,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Estado de la llamada
        val stateText = when (callState) {
            CallManager.CallState.RINGING -> "Llamada entrante..."
            CallManager.CallState.DIALING -> "Llamando..."
            CallManager.CallState.ACTIVE -> formatDuration(callDuration)
            CallManager.CallState.HOLDING -> "En espera"
            CallManager.CallState.ENDING -> "Finalizando..."
            CallManager.CallState.ENDED -> "Llamada finalizada"
            else -> ""
        }

        Text(
            text = stateText,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}

@Composable
fun StatusIndicators(
    isRecording: Boolean,
    isMuted: Boolean,
    isSpeakerOn: Boolean
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (isRecording) {
            StatusChip(
                icon = Icons.Default.FiberManualRecord,
                label = "Grabando",
                color = Color.Red
            )
        }
        if (isMuted) {
            Spacer(modifier = Modifier.width(8.dp))
            StatusChip(
                icon = Icons.Default.MicOff,
                label = "Muteado",
                color = Color(0xFFFF9800)
            )
        }
        if (isSpeakerOn) {
            Spacer(modifier = Modifier.width(8.dp))
            StatusChip(
                icon = Icons.Default.VolumeUp,
                label = "Altavoz",
                color = Color(0xFF2196F3)
            )
        }
    }
}

@Composable
fun StatusChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color
) {
    Surface(
        color = color.copy(alpha = 0.2f),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = color,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun CallActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    backgroundColor: Color,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        )
    )

    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier
            .size(72.dp)
            .scale(scale),
        containerColor = backgroundColor
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(32.dp)
        )
    }
}

@Composable
fun SecondaryCallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    if (isActive) Color.White.copy(alpha = 0.3f)
                    else Color.White.copy(alpha = 0.1f)
                )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format("%02d:%02d", minutes, secs)
    }
}
