package com.luisspamdetector.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luisspamdetector.ui.theme.LinphoneSpamDetectorTheme

/**
 * Actividad overlay que muestra el proceso de screening y permite
 * al usuario aceptar o rechazar la llamada una vez identificada.
 */
class ScreeningOverlayActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PHONE_NUMBER = "phone_number"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CALLER_PURPOSE = "caller_purpose"
        const val EXTRA_SCREENING_STATUS = "screening_status"

        const val ACTION_ACCEPT_CALL = "com.luisspamdetector.ACTION_ACCEPT_CALL"
        const val ACTION_REJECT_CALL = "com.luisspamdetector.ACTION_REJECT_CALL"
        const val ACTION_UPDATE_SCREENING = "com.luisspamdetector.ACTION_UPDATE_SCREENING"
    }

    private var phoneNumber = ""
    private var callerName by mutableStateOf<String?>(null)
    private var callerPurpose by mutableStateOf<String?>(null)
    private var isScreeningComplete by mutableStateOf(false)

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_SCREENING) {
                callerName = intent.getStringExtra(EXTRA_CALLER_NAME)
                callerPurpose = intent.getStringExtra(EXTRA_CALLER_PURPOSE)
                isScreeningComplete = intent.getBooleanExtra(EXTRA_SCREENING_STATUS, false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configurar ventana para mostrar sobre pantalla de bloqueo
        setupWindow()

        // Obtener datos del intent
        phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: "Desconocido"

        // Registrar receiver para actualizaciones
        val filter = IntentFilter(ACTION_UPDATE_SCREENING)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }

        setContent {
            LinphoneSpamDetectorTheme {
                ScreeningOverlayScreen(
                    phoneNumber = phoneNumber,
                    callerName = callerName,
                    callerPurpose = callerPurpose,
                    isScreeningComplete = isScreeningComplete,
                    onAccept = { acceptCall() },
                    onReject = { rejectCall() }
                )
            }
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

    private fun acceptCall() {
        sendBroadcast(Intent(ACTION_ACCEPT_CALL))
        finish()
    }

    private fun rejectCall() {
        sendBroadcast(Intent(ACTION_REJECT_CALL))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(updateReceiver)
        } catch (e: Exception) {
            // Ignorar si ya estaba desregistrado
        }
    }
}

@Composable
fun ScreeningOverlayScreen(
    phoneNumber: String,
    callerName: String?,
    callerPurpose: String?,
    isScreeningComplete: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1a1a2e),
                        Color(0xFF16213e)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Icono de teléfono
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0f3460)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Phone,
                    contentDescription = null,
                    modifier = Modifier.size(50.dp),
                    tint = Color.White
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Número de teléfono
            Text(
                text = phoneNumber,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Card con información del screening
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF0f3460).copy(alpha = 0.7f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AnimatedVisibility(
                        visible = !isScreeningComplete,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = Color(0xFF4ecca3),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Identificando llamada...",
                                fontSize = 18.sp,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "El asistente está preguntando quién llama",
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = isScreeningComplete,
                        enter = fadeIn() + slideInVertically(),
                        exit = fadeOut()
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // Nombre del llamante
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = Color(0xFF4ecca3),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Nombre",
                                        fontSize = 12.sp,
                                        color = Color.White.copy(alpha = 0.6f)
                                    )
                                    Text(
                                        text = callerName ?: "No identificado",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            Divider(color = Color.White.copy(alpha = 0.2f))
                            Spacer(modifier = Modifier.height(16.dp))

                            // Motivo de la llamada
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Motivo de la llamada",
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.6f)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = callerPurpose ?: "No especificado",
                                    fontSize = 16.sp,
                                    color = Color.White,
                                    lineHeight = 22.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Botones de acción
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Botón Rechazar
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingActionButton(
                        onClick = onReject,
                        containerColor = Color(0xFFe74c3c),
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = "Rechazar",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Rechazar",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp
                    )
                }

                // Botón Aceptar
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FloatingActionButton(
                        onClick = onAccept,
                        containerColor = Color(0xFF2ecc71),
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Aceptar",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Aceptar",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
