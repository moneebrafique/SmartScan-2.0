package com.smartscan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartscan.app.data.DocumentRepository
import com.smartscan.app.data.Folder
import kotlinx.coroutines.launch

val FolderColors = listOf(
    Color(0xFF1E88E5), // blue
    Color(0xFF43A047), // green
    Color(0xFFF4511E), // orange
    Color(0xFF8E24AA), // purple
    Color(0xFFE53935), // red
    Color(0xFF00897B), // teal
    Color(0xFF6D4C41), // brown
    Color(0xFF546E7A), // grey
)

fun folderColor(index: Int): Color = FolderColors[((index % FolderColors.size) + FolderColors.size) % FolderColors.size]

val SUGGESTED_FOLDERS = listOf(
    "Travel", "Education", "Office", "Personal", "ID Cards",
    "Receipts & Bills", "Medical", "Property", "Bank", "Certificates",
)

/** Folders as a tree, in display order, with their nesting depth. */
fun flattenFolders(all: List<Folder>, parent: String? = null, depth: Int = 0): List<Pair<Folder, Int>> =
    all.filter { it.parentId == parent }
        .sortedBy { it.name.lowercase() }
        .flatMap { listOf(it to depth) + flattenFolders(all, it.id, depth + 1) }

/** Path from the top level down to the given folder. */
fun folderPath(all: List<Folder>, id: String?): List<Folder> {
    val byId = all.associateBy { it.id }
    val path = mutableListOf<Folder>()
    val seen = mutableSetOf<String>()
    var curId = id
    while (curId != null) {
        val f = byId[curId] ?: break
        if (!seen.add(f.id)) break
        path.add(0, f)
        curId = f.parentId
    }
    return path
}

fun folderPathLabel(all: List<Folder>, id: String?): String =
    folderPath(all, id).joinToString(" › ") { it.name }.ifEmpty { "Home" }

/** Create / edit folder: name, quick suggestions, colour. */
@Composable
fun FolderDialog(
    title: String,
    initialName: String,
    initialColor: Int,
    suggestions: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, color: Int) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableIntStateOf(initialColor) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        suggestions.forEach { s ->
                            SuggestionChip(onClick = { name = s }, label = { Text(s) })
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Color", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FolderColors.forEachIndexed { i, c ->
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(c)
                                .then(
                                    if (i == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    else Modifier,
                                )
                                .clickable { color = i },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (i == color) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim(), color) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick a destination folder (or Home); can also create a new folder on the spot. */
@Composable
fun MoveToFolderDialog(
    currentFolderId: String?,
    count: Int,
    onDismiss: () -> Unit,
    onMove: (folderId: String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val folders by DocumentRepository.folders.collectAsState()
    var creating by remember { mutableStateOf(false) }
    val tree = remember(folders) { flattenFolders(folders) }

    if (creating) {
        FolderDialog(
            title = "New folder",
            initialName = "",
            initialColor = folders.size % FolderColors.size,
            suggestions = SUGGESTED_FOLDERS.filter { s -> folders.none { it.parentId == null && it.name.equals(s, true) } },
            onDismiss = { creating = false },
        ) { name, color ->
            creating = false
            scope.launch {
                val f = DocumentRepository.createFolder(name, null, color)
                onMove(f.id)
            }
        }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Move to folder" else "Move $count documents") },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                item {
                    FolderPickRow(
                        icon = { Icon(Icons.Default.Home, null) },
                        name = "Home (no folder)",
                        depth = 0,
                        isCurrent = currentFolderId == null,
                        onClick = { onMove(null) },
                    )
                }
                items(tree, key = { it.first.id }) { (folder, depth) ->
                    FolderPickRow(
                        icon = { Icon(Icons.Default.Folder, null, tint = folderColor(folder.color)) },
                        name = folder.name,
                        depth = depth,
                        isCurrent = currentFolderId == folder.id,
                        onClick = { onMove(folder.id) },
                    )
                }
                item {
                    TextButton(onClick = { creating = true }) {
                        Icon(Icons.Default.CreateNewFolder, null)
                        Spacer(Modifier.width(8.dp))
                        Text("New folder")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FolderPickRow(
    icon: @Composable () -> Unit,
    name: String,
    depth: Int,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !isCurrent, onClick = onClick)
            .padding(start = (8 + depth * 20).dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Text(
            name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (isCurrent) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        if (isCurrent) Text("Current", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}
