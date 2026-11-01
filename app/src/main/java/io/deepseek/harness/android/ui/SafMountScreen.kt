package io.deepseek.harness.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.deepseek.harness.android.saf.SafAccessMode
import io.deepseek.harness.android.saf.SafMountEntry

/**
 * Pantalla de gestión del Puente SAF (Storage Access Framework):
 * permite seleccionar carpetas del almacenamiento externo de Android y acceder a ellas
 * tanto desde la terminal integrada (`bash`) como desde el agente `deepseek-harness` (`dsh`)
 * en `/mnt/saf/<alias>` (y `/root/saf/<alias>`).
 */
@Composable
fun SafMountScreen(
    mounts: List<SafMountEntry>,
    hasAllFilesAccess: Boolean,
    onPickSafDirectory: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onOpenInTerminal: (String) -> Unit,
    onSyncMount: (String) -> Unit,
    onUnmount: (String) -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Puente de Almacenamiento Externo (SAF)",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Selecciona cualquier carpeta de tu dispositivo para montarla en /mnt/saf/<nombre> (y /root/saf/<nombre>) dentro del contenedor PRoot compartido por dsh y bash.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onPickSafDirectory,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Seleccionar carpeta externa (SAF)")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess) {
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    ),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Modo Espejo SAF activo (sin permiso global de archivos)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Las carpetas seleccionadas funcionan ya mismo mediante espejo DocumentFile. Si deseas acceso POSIX directo en tiempo real sin copias para repositorios grandes, concede opcionalmente «Acceso a todos los archivos».",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = onRequestAllFilesAccess) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Conceder acceso directo (opcional)")
                        }
                    }
                }
            }
        }

        if (mounts.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "Aún no hay carpetas externas montadas",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Pulsa «Seleccionar carpeta externa (SAF)» arriba. Una vez elegida, podrás hacer `cd /mnt/saf/<alias>` desde la terminal o indicarle esa ruta directamente al agente dsh.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(mounts, key = { it.alias }) { entry ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = entry.guestPath,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(
                                onClick = {
                                    val cm = context.getSystemService(ClipboardManager::class.java)
                                    cm?.setPrimaryClip(ClipData.newPlainText("Ruta PRoot SAF", entry.guestPath))
                                    onMessage("Ruta copiada: ${entry.guestPath}")
                                },
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copiar ruta")
                            }
                            IconButton(onClick = { onUnmount(entry.alias) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Desmontar")
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        val modeDesc = when (entry.mode) {
                            SafAccessMode.DIRECT_SYMLINK ->
                                "Modo: Enlace simbólico POSIX directo (${entry.resolvedHostPath})"
                            SafAccessMode.DOCUMENT_MIRROR ->
                                "Modo: Espejo SAF bidireccional (pulsa Sincronizar tras editar fuera/dentro)"
                        }
                        Text(
                            text = modeDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Button(
                                onClick = { onOpenInTerminal(entry.guestPath) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Terminal, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Abrir en Terminal")
                            }
                            OutlinedButton(
                                onClick = { onSyncMount(entry.alias) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Sincronizar")
                            }
                        }
                    }
                }
            }
        }
    }
}
