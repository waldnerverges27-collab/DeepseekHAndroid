package io.deepseek.harness.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.util.TypedValue
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.view.TerminalView
import io.deepseek.harness.android.terminal.ExtraKeySpec
import io.deepseek.harness.android.terminal.ExtraKeysController
import io.deepseek.harness.android.terminal.ModifierKind
import io.deepseek.harness.android.terminal.TerminalSessionRegistry

/**
 * Panel nativo de terminal Linux integrado:
 * - Renderiza sesiones PTY reales (`libtermux.so` + `/dev/ptmx` + `TIOCSWINSZ`) ejecutando
 *   `/bin/bash --login` dentro del mismo contenedor PRoot que el motor `dsh`.
 * - Incluye pestañas multi-sesión, copiar pantalla/selección, pegar portapapeles, zoom de fuente,
 *   botón directo `dsh-doctor` y barra de teclas extra móvil de dos filas (`Ctrl`, `Alt`, `Esc`,
 *   `Tab`, flechas, `Inicio`, `Fin`, `RePág`, `AvPág`, señales y símbolos).
 */
@Composable
fun TerminalPanel(
    fontSizeSp: Int,
    onCreateSession: () -> Unit,
    onFontSizeScale: (Float) -> Float,
    onStepFontSize: (Int) -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val tabs by TerminalSessionRegistry.tabs.collectAsState()
    val activeTabId by TerminalSessionRegistry.activeTabId.collectAsState()
    val screenGen by TerminalSessionRegistry.screenGeneration.collectAsState()

    val extraKeys = TerminalSessionRegistry.extraKeysController
    var terminalViewRef by remember { mutableStateOf<TerminalView?>(null) }

    val activeTab = tabs.firstOrNull { it.id == activeTabId } ?: tabs.firstOrNull()
    val activeSession = activeTab?.session

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
            .imePadding(),
    ) {
        // Barra superior: pestañas de sesión + acciones rápidas (dsh-doctor, copiar, pegar, fuente)
        Surface(
            color = Color(0xFF141821),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEach { tab ->
                        val selected = tab.id == activeTab?.id
                        AssistChip(
                            onClick = { TerminalSessionRegistry.selectTab(tab.id) },
                            label = {
                                Text(
                                    text = tab.label,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                )
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = { TerminalSessionRegistry.closeSession(tab.id) },
                                    modifier = Modifier.size(16.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Cerrar sesión",
                                        modifier = Modifier.size(13.dp),
                                    )
                                }
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (selected) Color(0xFF283566) else Color(0xFF1D2230),
                                labelColor = Color.White,
                            ),
                        )
                    }
                    IconButton(
                        onClick = onCreateSession,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Nueva shell bash",
                            tint = Color(0xFF93C5FD),
                        )
                    }
                }

                // Ejecutar diagnóstico de contenedor compartido
                IconButton(
                    onClick = {
                        activeSession?.write("dsh-doctor\n")
                            ?: onCreateSession()
                    },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.HealthAndSafety,
                        contentDescription = "Verificar entorno compartido (dsh-doctor)",
                        tint = Color(0xFF38BDF8),
                    )
                }

                // Copiar transcript completo al portapapeles (además de la selección táctil nativa)
                IconButton(
                    onClick = {
                        val transcript = activeSession?.emulator?.screen?.transcriptText?.trim()
                        if (!transcript.isNullOrEmpty()) {
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm?.setPrimaryClip(ClipData.newPlainText("Terminal Transcript", transcript))
                            onMessage("Salida de la terminal copiada al portapapeles")
                        }
                    },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copiar salida",
                        tint = Color(0xFFE5E7EB),
                    )
                }

                // Pegar desde el portapapeles
                IconButton(
                    onClick = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        val text = cm?.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                        if (!text.isNullOrEmpty() && activeSession != null) {
                            activeSession.emulator?.paste(text)
                        } else {
                            onMessage("Portapapeles vacío")
                        }
                    },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = "Pegar texto",
                        tint = Color(0xFFE5E7EB),
                    )
                }

                TextButton(
                    onClick = { onStepFontSize(-1) },
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Text("A-", color = Color(0xFF9CA3AF), fontSize = 12.sp)
                }
                TextButton(
                    onClick = { onStepFontSize(1) },
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Text("A+", color = Color(0xFF9CA3AF), fontSize = 12.sp)
                }
            }
        }

        // Área central: TerminalView nativa con PTY
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF0B0E14)),
        ) {
            if (activeSession == null) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "No hay ninguna sesión de terminal abierta.",
                        color = Color(0xFF9CA3AF),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onCreateSession) {
                        Text("Iniciar Zsh (Oh My Zsh) en contenedor PRoot")
                    }
                }
            } else {
                val fontPx = remember(fontSizeSp, context) {
                    TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP,
                        fontSizeSp.toFloat(),
                        context.resources.displayMetrics,
                    ).toInt().coerceAtLeast(16)
                }

                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    factory = { ctx ->
                        val client = TerminalSessionRegistry.getOrCreateClient(ctx, onFontSizeScale)
                        TerminalView(ctx, null).apply {
                            setBackgroundColor(AndroidColor.parseColor("#0B0E14"))
                            isFocusable = true
                            isFocusableInTouchMode = true
                            keepScreenOn = true
                            setTerminalViewClient(client)
                            // IMPORTANTE: setTextSize debe invocarse antes de setTypeface para
                            // inicializar mRenderer (de lo contrario mRenderer es null).
                            setTextSize(fontPx)
                            setTypeface(Typeface.MONOSPACE)
                            setTerminalCursorBlinkerRate(500)
                            client.attachedView = this
                            attachSession(activeSession)
                            terminalViewRef = this
                        }
                    },
                    update = { view ->
                        val client = TerminalSessionRegistry.getOrCreateClient(view.context, onFontSizeScale)
                        client.attachedView = view
                        terminalViewRef = view
                        if (view.textSize != fontPx) {
                            view.setTextSize(fontPx)
                        }
                        if (view.currentSession !== activeSession) {
                            view.attachSession(activeSession)
                        }
                        if (screenGen >= 0L) {
                            view.onScreenUpdated()
                        }
                    },
                )
            }
        }

        // Barra inferior de teclas extra para móvil (2 filas desplazables)
        ExtraKeysBar(
            controller = extraKeys,
            onKeySelected = { keySpec ->
                extraKeys.dispatchExtraKey(
                    spec = keySpec,
                    terminalView = terminalViewRef,
                    session = activeSession,
                )
                terminalViewRef?.requestFocus()
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExtraKeysBar(
    controller: ExtraKeysController,
    onKeySelected: (ExtraKeySpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctrlActive by controller.ctrlActive.collectAsState()
    val ctrlLocked by controller.ctrlLocked.collectAsState()
    val altActive by controller.altActive.collectAsState()
    val altLocked by controller.altLocked.collectAsState()

    Surface(
        color = Color(0xFF141821),
        tonalElevation = 4.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            listOf(controller.primaryRow, controller.secondaryRow).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    row.forEach { keySpec ->
                        val isModifierActive = when (keySpec) {
                            is ExtraKeySpec.ModifierToggle -> when (keySpec.modifier) {
                                ModifierKind.CTRL -> ctrlActive
                                ModifierKind.ALT -> altActive
                                ModifierKind.SHIFT -> false
                            }
                            else -> false
                        }
                        val isModifierLocked = when (keySpec) {
                            is ExtraKeySpec.ModifierToggle -> when (keySpec.modifier) {
                                ModifierKind.CTRL -> ctrlLocked
                                ModifierKind.ALT -> altLocked
                                ModifierKind.SHIFT -> false
                            }
                            else -> false
                        }

                        val bgColor = when {
                            isModifierLocked -> Color(0xFF2563EB)
                            isModifierActive -> Color(0xFF4D6BFE)
                            else -> Color(0xFF222736)
                        }

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .height(34.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(bgColor)
                                .combinedClickable(
                                    onClick = { onKeySelected(keySpec) },
                                    onLongClick = {
                                        if (keySpec is ExtraKeySpec.ModifierToggle) {
                                            controller.onModifierTap(keySpec.modifier, lock = true)
                                        } else {
                                            onKeySelected(keySpec)
                                        }
                                    },
                                )
                                .padding(horizontal = 11.dp),
                        ) {
                            Text(
                                text = if (isModifierLocked) "${keySpec.label}•" else keySpec.label,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
