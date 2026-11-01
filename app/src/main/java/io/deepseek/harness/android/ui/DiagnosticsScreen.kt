package io.deepseek.harness.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.deepseek.harness.android.core.AppLogger
import io.deepseek.harness.android.engine.EngineLifecycleState

/**
 * Panel de estado, ciclo de vida del ForegroundService + Watchdog y consola de logs.
 */
@Composable
fun DiagnosticsScreen(
    engineState: EngineLifecycleState,
    logLines: List<String>,
    prootNoSeccomp: Boolean,
    onToggleProotNoSeccomp: (Boolean) -> Unit,
    onStartOrRetry: () -> Unit,
    onRestartEngine: () -> Unit,
    onStopEngine: () -> Unit,
    onReExtractRootfs: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Motor DeepSeek Harness y Watchdog",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                val summary = when (engineState) {
                    is EngineLifecycleState.Running ->
                        "ACTIVO en 127.0.0.1:${engineState.port} (${engineState.latencyMs} ms) • Reinicios watchdog: ${engineState.restartCount}"
                    is EngineLifecycleState.StartingEngine ->
                        "INICIANDO en puerto ${engineState.port} (intento ${engineState.attempt})…"
                    is EngineLifecycleState.ExtractingRootfs ->
                        "EXTRAYENDO rootfs (${(engineState.progress.fraction * 100).toInt()}%)…"
                    is EngineLifecycleState.Stopped ->
                        "DETENIDO manualmente"
                    is EngineLifecycleState.Error ->
                        "ERROR: ${engineState.title}"
                    else ->
                        "PREPARANDO entorno…"
                }
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                )

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = {
                            if (engineState is EngineLifecycleState.Running) {
                                onRestartEngine()
                            } else {
                                onStartOrRetry()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = if (engineState is EngineLifecycleState.Running) Icons.Default.Refresh else Icons.Default.PlayArrow,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (engineState is EngineLifecycleState.Running) "Reiniciar" else "Iniciar")
                    }
                    OutlinedButton(
                        onClick = onStopEngine,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Detener")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Compatibilidad PROOT_NO_SECCOMP",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Evita conflictos BPF seccomp en kernels Android OEM",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = prootNoSeccomp,
                        onCheckedChange = onToggleProotNoSeccomp,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = onRequestBatteryExemption,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.BatteryChargingFull, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Sin restricción batería", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onReExtractRootfs,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Reinstalar rootfs", fontSize = 12.sp)
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Registro en vivo (${logLines.size} líneas)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    val full = AppLogger.exportAllText(context)
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("DeepSeek Harness Logs", full))
                    onMessage("Logs completos copiados al portapapeles")
                },
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Copiar")
            }
            Spacer(modifier = Modifier.width(6.dp))
            OutlinedButton(onClick = { AppLogger.clear() }) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null)
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0B0E14))
                .padding(10.dp),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(logLines.asReversed()) { line ->
                    val lineColor = when {
                        line.contains("[ERROR]") -> Color(0xFFF87171)
                        line.contains("[WARN]") -> Color(0xFFFBBF24)
                        else -> Color(0xFFD1D5DB)
                    }
                    Text(
                        text = line,
                        color = lineColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}
