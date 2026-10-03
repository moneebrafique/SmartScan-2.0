package com.smartscan.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartscan.app.BuildConfig
import com.smartscan.app.update.UpdateInfo
import com.smartscan.app.update.Updater
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@Composable
fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun installNow(file: File) {
        if (Updater.canInstall(context)) {
            Updater.install(context, file)
        } else {
            Toast.makeText(
                context,
                "Allow SmartScan to install updates, then come back and tap Install",
                Toast.LENGTH_LONG,
            ).show()
            Updater.openInstallPermissionSettings(context)
        }
    }

    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
        title = { Text("Update available") },
        text = {
            Column {
                Text("Version ${info.versionName}", fontWeight = FontWeight.SemiBold)
                Text(
                    "You have ${BuildConfig.VERSION_NAME}" +
                        if (info.sizeBytes > 0) String.format(Locale.US, " · %.1f MB download", info.sizeBytes / 1_048_576.0) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (info.notes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text("What's new", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(
                        info.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                    )
                }
                progress?.let { p ->
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text("Downloading… ${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = progress == null,
                onClick = {
                    val existing = apk
                    if (existing != null && existing.exists()) {
                        installNow(existing)
                    } else {
                        error = null
                        progress = 0f
                        scope.launch {
                            try {
                                val file = Updater.download(context, info) { p -> progress = p }
                                apk = file
                                progress = null
                                installNow(file)
                            } catch (e: Exception) {
                                progress = null
                                error = "Download failed. Check your internet connection and try again."
                            }
                        }
                    }
                },
            ) { Text(if (apk != null) "Install" else "Update now") }
        },
        dismissButton = {
            TextButton(enabled = progress == null, onClick = onDismiss) { Text("Later") }
        },
    )
}
