package io.deepseek.harness.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.deepseek.harness.android.engine.EngineLifecycleState
import java.util.Locale

/**
 * Pantalla del asistente de primer arranque y arranque en frío del motor `dsh`.
 * Muestra el progreso exacto de extracción de `rootfs.tar.xz` y el estado de cada fase.
 */
@Composable
fun BootSetupScreen(
    state: EngineLifecycleState,
    onRetryBoot: () -> Unit,
    onStartStoppedEngine: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "DeepSeek Harness",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Entorno autónomo Linux PRoot + Agente dsh (Offline-Ready)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            ),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                val activeStep = when (state) {
                    is EngineLifecycleState.Idle,
                    is EngineLifecycleState.CheckingEnvironment,
                    is EngineLifecycleState.ExtractingRootfs -> 1
                    is EngineLifecycleState.VerifyingContainer -> 2
                    is EngineLifecycleState.StartingEngine -> 3
                    is EngineLifecycleState.Running -> 4
                    is EngineLifecycleState.Stopped -> 0
                    is EngineLifecycleState.Error -> -1
                }

                BootPhaseRow(
                    stepNumber = 1,
                    title = "Extraer y verificar rootfs Linux (Debian + Node.js + dsh)",
                    activeStep = activeStep,
                )
                Spacer(modifier = Modifier.height(10.dp))
                BootPhaseRow(
                    stepNumber = 2,
                    title = "Configurar contenedor PRoot compartido y montajes SAF",
                    activeStep = activeStep,
                )
                Spacer(modifier = Modifier.height(10.dp))
                BootPhaseRow(
                    stepNumber = 3,
                    title = "Iniciar ForegroundService y servidor web en 127.0.0.1:3080",
                    activeStep = activeStep,
                )

                when (state) {
                    is EngineLifecycleState.ExtractingRootfs -> {
                        val p = state.progress
                        Spacer(modifier = Modifier.height(18.dp))
                        if (p.totalCompressedBytes > 0L) {
                            LinearProgressIndicator(
                                progress = { p.fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            val doneMb = p.bytesCompressedRead / (1024.0 * 1024.0)
                            val totalMb = p.totalCompressedBytes / (1024.0 * 1024.0)
                            val pct = (p.fraction * 100f).toInt()
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%d%% • %.1f / %.1f MB (%d archivos)",
                                    pct,
                                    doneMb,
                                    totalMb,
                                    p.entriesExtracted,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Archivos extraídos: ${p.entriesExtracted}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = p.currentEntry,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is EngineLifecycleState.StartingEngine -> {
                        Spacer(modifier = Modifier.height(18.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Esperando respuesta HTTP en http://127.0.0.1:${state.port} (intento ${state.attempt})…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is EngineLifecycleState.Stopped -> {
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = "El motor dsh fue detenido manualmente. Tus archivos y sesiones siguen intactos.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onStartStoppedEngine,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Iniciar motor dsh")
                        }
                    }

                    is EngineLifecycleState.Error -> {
                        Spacer(modifier = Modifier.height(18.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = state.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = state.detail,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = onRetryBoot,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Reintentar")
                            }
                            OutlinedButton(
                                onClick = onOpenDiagnostics,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Ver Logs")
                            }
                        }
                    }

                    else -> {
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(onClick = onOpenTerminal) {
            Icon(Icons.Default.Terminal, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Abrir Terminal Linux (bash)")
        }
    }
}

@Composable
private fun BootPhaseRow(
    stepNumber: Int,
    title: String,
    activeStep: Int,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        when {
            activeStep > stepNumber -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }

            activeStep == stepNumber -> {
                CircularProgressIndicator(
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(20.dp),
                )
            }

            else -> {
                Text(
                    text = "$stepNumber.",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(20.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (activeStep == stepNumber) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
