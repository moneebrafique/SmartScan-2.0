package com.smartscan.app.ui

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.smartscan.app.BuildConfig
import com.smartscan.app.data.DocumentRepository
import com.smartscan.app.data.Folder
import com.smartscan.app.data.ScanDocument
import com.smartscan.app.export.ExportFormat
import com.smartscan.app.export.ExportQuality
import com.smartscan.app.export.Exporter
import com.smartscan.app.export.PdfPageSize
import com.smartscan.app.processing.ScanActions
import com.smartscan.app.update.UpdateInfo
import com.smartscan.app.update.Updater
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class SortMode(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    NAME("Name A–Z"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(onOpen: (String) -> Unit, onScan: (folderId: String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val docs by DocumentRepository.documents.collectAsStateWithLifecycle()
    val folders by DocumentRepository.folders.collectAsStateWithLifecycle()

    var currentId by rememberSaveable { mutableStateOf<String?>(null) }
    val current = folders.firstOrNull { it.id == currentId }
    LaunchedEffect(currentId, current) { if (currentId != null && current == null) currentId = null }

    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(SortMode.NEWEST) }
    val selected = remember { mutableStateListOf<String>() }
    val selecting = selected.isNotEmpty()
    LaunchedEffect(docs) {
        val ids = docs.map { it.id }.toSet()
        selected.retainAll { it in ids }
    }

    var busyText by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<ScanDocument?>(null) }
    var deleteTarget by remember { mutableStateOf<ScanDocument?>(null) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var editFolder by remember { mutableStateOf<Folder?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<Folder?>(null) }
    var moveIds by remember { mutableStateOf<List<String>?>(null) }
    var shareIds by remember { mutableStateOf<List<String>?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }

    // Quietly check for a new version when the app opens (at most every 6 hours).
    LaunchedEffect(Unit) {
        if (Updater.shouldAutoCheck(context)) {
            runCatching { Updater.check() }.onSuccess {
                Updater.markChecked(context)
                if (it != null) update = it
            }
        }
    }

    fun checkForUpdates() {
        if (checkingUpdate) return
        checkingUpdate = true
        scope.launch {
            val result = runCatching { Updater.check() }
            checkingUpdate = false
            result.onSuccess {
                Updater.markChecked(context)
                if (it != null) update = it
                else Toast.makeText(context, "You have the latest version (${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Couldn't check for updates: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    BackHandler(enabled = selecting || current != null) {
        if (selecting) selected.clear() else currentId = current?.parentId
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val doc = DocumentRepository.create(currentId)
            uris.forEachIndexed { i, uri ->
                busyText = "Importing ${i + 1} of ${uris.size}…"
                runCatching { ScanActions.addPage(context, doc.id, uri) }
                    .onFailure { Log.w("SmartScan", "Import failed", it) }
            }
            busyText = null
            onOpen(doc.id)
        }
    }

    fun quickSharePdf(doc: ScanDocument) {
        if (doc.pages.isEmpty()) return
        scope.launch {
            busyText = "Preparing PDF…"
            try {
                val result = Exporter.export(context, doc, ExportFormat.PDF, PdfPageSize.A4, ExportQuality.MEDIUM) {}
                Exporter.share(context, result)
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                busyText = null
            }
        }
    }

    val searching = query.isNotBlank()
    val visibleFolders = if (searching) {
        folders.filter { it.name.contains(query.trim(), ignoreCase = true) }.sortedBy { it.name.lowercase() }
    } else {
        folders.filter { it.parentId == currentId }.sortedBy { it.name.lowercase() }
    }
    val visibleDocs = (
        if (searching) docs.filter { it.name.contains(query.trim(), ignoreCase = true) }
        else docs.filter { it.folderId == currentId }
        ).let { list ->
        when (sort) {
            SortMode.NEWEST -> list.sortedByDescending { it.updatedAt }
            SortMode.OLDEST -> list.sortedBy { it.createdAt }
            SortMode.NAME -> list.sortedBy { it.name.lowercase() }
        }
    }
    val docCounts = remember(docs) { docs.groupingBy { it.folderId }.eachCount() }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selected.clear() }) { Icon(Icons.Default.Close, contentDescription = "Cancel") }
                    },
                    actions = {
                        IconButton(onClick = {
                            selected.clear()
                            selected.addAll(visibleDocs.map { it.id })
                        }) { Icon(Icons.Default.SelectAll, contentDescription = "Select all") }
                        IconButton(onClick = { shareIds = selected.toList() }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = { moveIds = selected.toList() }) {
                            Icon(Icons.Default.DriveFileMove, contentDescription = "Move to folder")
                        }
                        IconButton(onClick = { confirmDeleteSelected = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            current?.name ?: "SmartScan",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        if (current != null) {
                            IconButton(onClick = { currentId = current.parentId }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showNewFolder = true }) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder")
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(Icons.Default.SwapVert, contentDescription = "Sort")
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                SortMode.entries.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m.label) },
                                        trailingIcon = { if (sort == m) Icon(Icons.Default.Check, contentDescription = null) },
                                        onClick = { sort = m; sortMenu = false },
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { moreMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (checkingUpdate) "Checking…" else "Check for updates") },
                                    leadingIcon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                                    enabled = !checkingUpdate,
                                    onClick = { moreMenu = false; checkForUpdates() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Version ${BuildConfig.VERSION_NAME}") },
                                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                                    enabled = false,
                                    onClick = {},
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selecting) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SmallFloatingActionButton(onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Icon(Icons.Default.PhotoLibrary, contentDescription = "Import from gallery") }
                    ExtendedFloatingActionButton(
                        onClick = { onScan(currentId) },
                        icon = { Icon(Icons.Default.DocumentScanner, contentDescription = null) },
                        text = { Text("Scan") },
                    )
                }
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 160.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search all documents & folders") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            if (!searching && current != null) {
                item { Breadcrumb(folderPath(folders, current.id)) { currentId = it } }
            }

            if (visibleFolders.isNotEmpty()) {
                item { SectionHeader("Folders") }
                items(visibleFolders.chunked(2), key = { "f_" + it.first().id }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { f ->
                            FolderCard(
                                folder = f,
                                info = buildString {
                                    if (searching) append(folderPathLabel(folders, f.parentId)).append(" • ")
                                    val n = docCounts[f.id] ?: 0
                                    append("$n doc${if (n == 1) "" else "s"}")
                                    val sub = folders.count { it.parentId == f.id }
                                    if (sub > 0) append(" • $sub folder${if (sub == 1) "" else "s"}")
                                },
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    query = ""
                                    selected.clear()
                                    currentId = f.id
                                },
                                onEdit = { editFolder = f },
                                onDelete = { deleteFolderTarget = f },
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            if (visibleDocs.isNotEmpty()) {
                if (visibleFolders.isNotEmpty() || searching) item { SectionHeader("Documents") }
                items(visibleDocs, key = { it.id }) { doc ->
                    DocumentRow(
                        doc = doc,
                        folderLabel = if (searching) folderPathLabel(folders, doc.folderId) else null,
                        selecting = selecting,
                        isSelected = doc.id in selected,
                        onClick = {
                            if (selecting) {
                                if (doc.id in selected) selected.remove(doc.id) else selected.add(doc.id)
                            } else {
                                onOpen(doc.id)
                            }
                        },
                        onLongClick = { if (doc.id !in selected) selected.add(doc.id) },
                        onSharePdf = { quickSharePdf(doc) },
                        onShare = { shareIds = listOf(doc.id) },
                        onMove = { moveIds = listOf(doc.id) },
                        onRename = { renameTarget = doc },
                        onDelete = { deleteTarget = doc },
                    )
                }
            }

            if (visibleFolders.isEmpty() && visibleDocs.isEmpty()) {
                item {
                    EmptyState(
                        when {
                            searching -> "Nothing found for \"$query\""
                            current != null -> "This folder is empty.\nScan or import here, or long-press documents elsewhere and move them in."
                            else -> "No documents yet.\nTap Scan to capture a document, or create folders like Travel, Education and Office to keep things organised."
                        },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ dialogs

    if (showNewFolder) {
        FolderDialog(
            title = if (current == null) "New folder" else "New folder in ${current.name}",
            initialName = "",
            initialColor = folders.size % FolderColors.size,
            suggestions = SUGGESTED_FOLDERS.filter { s ->
                folders.none { it.parentId == currentId && it.name.equals(s, ignoreCase = true) }
            },
            onDismiss = { showNewFolder = false },
        ) { name, color ->
            showNewFolder = false
            val parent = currentId
            scope.launch { DocumentRepository.createFolder(name, parent, color) }
        }
    }

    editFolder?.let { f ->
        FolderDialog(
            title = "Edit folder",
            initialName = f.name,
            initialColor = f.color,
            suggestions = emptyList(),
            onDismiss = { editFolder = null },
        ) { name, color ->
            editFolder = null
            scope.launch { DocumentRepository.updateFolder(f.id) { it.copy(name = name, color = color) } }
        }
    }

    deleteFolderTarget?.let { f ->
        val inside = remember(f.id, docs, folders) {
            val ids = DocumentRepository.descendantFolderIds(f.id)
            docs.count { it.folderId in ids }
        }
        val parentName = folders.firstOrNull { it.id == f.parentId }?.name ?: "Home"
        var alsoDocs by remember(f.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { deleteFolderTarget = null },
            title = { Text("Delete folder \"${f.name}\"?") },
            text = {
                Column {
                    Text(
                        when {
                            inside == 0 -> "This folder has no documents."
                            alsoDocs -> "$inside document(s) inside will be permanently deleted."
                            else -> "$inside document(s) and any sub-folders will be moved to \"$parentName\"."
                        },
                    )
                    if (inside > 0) {
                        Row(
                            Modifier.fillMaxWidth().clickable { alsoDocs = !alsoDocs }.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = alsoDocs, onCheckedChange = { alsoDocs = it })
                            Text("Also delete the documents inside")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteFolderTarget = null
                    val delete = alsoDocs
                    scope.launch { DocumentRepository.deleteFolder(f.id, delete) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteFolderTarget = null }) { Text("Cancel") } },
        )
    }

    moveIds?.let { ids ->
        val single = if (ids.size == 1) docs.firstOrNull { it.id == ids[0] } else null
        MoveToFolderDialog(
            currentFolderId = single?.folderId ?: if (ids.size == 1) null else "__none__",
            count = ids.size,
            onDismiss = { moveIds = null },
        ) { target ->
            moveIds = null
            selected.clear()
            scope.launch {
                DocumentRepository.moveDocuments(ids, target)
                Toast.makeText(
                    context,
                    "Moved ${ids.size} document(s) to ${folderPathLabel(DocumentRepository.folders.value, target)}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    shareIds?.let { ids ->
        val chosen = ids.mapNotNull { id -> docs.firstOrNull { it.id == id } }
        ExportSheet(chosen) {
            shareIds = null
            selected.clear()
        }
    }

    renameTarget?.let { doc ->
        RenameDialog(doc.name, onDismiss = { renameTarget = null }) { name ->
            renameTarget = null
            scope.launch { ScanActions.rename(doc.id, name) }
        }
    }
    deleteTarget?.let { doc ->
        ConfirmDialog(
            title = "Delete document?",
            message = "\"${doc.name}\" and all its pages will be permanently deleted.",
            confirmLabel = "Delete",
            onDismiss = { deleteTarget = null },
        ) {
            deleteTarget = null
            scope.launch { DocumentRepository.delete(doc.id) }
        }
    }
    if (confirmDeleteSelected) {
        ConfirmDialog(
            title = "Delete ${selected.size} document(s)?",
            message = "The selected documents and all their pages will be permanently deleted.",
            confirmLabel = "Delete",
            onDismiss = { confirmDeleteSelected = false },
        ) {
            confirmDeleteSelected = false
            val ids = selected.toList()
            selected.clear()
            scope.launch { ids.forEach { DocumentRepository.delete(it) } }
        }
    }
    update?.let { info -> UpdateDialog(info) { update = null } }
    busyText?.let { BusyDialog(it) }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun Breadcrumb(path: List<Folder>, onNavigate: (String?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onNavigate(null) }) {
            Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Home")
        }
        path.forEachIndexed { i, f ->
            Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
            TextButton(onClick = { onNavigate(f.id) }, enabled = i != path.lastIndex) { Text(f.name) }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.FolderOpen, contentDescription = null,
            modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FolderCard(
    folder: Folder,
    info: String,
    modifier: Modifier,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    OutlinedCard(onClick = onClick, modifier = modifier) {
        Row(
            Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Folder, contentDescription = null,
                tint = folderColor(folder.color), modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    info,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Folder options")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename / color") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menu = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete folder") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentRow(
    doc: ScanDocument,
    folderLabel: String?,
    selecting: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSharePdf: () -> Unit,
    onShare: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val date = remember(doc.updatedAt) {
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(doc.updatedAt))
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isSelected) CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ) else CardDefaults.elevatedCardColors(),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val first = doc.pages.firstOrNull()
            Box(
                Modifier.size(width = 56.dp, height = 72.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (first != null) {
                    AsyncImage(
                        model = DocumentRepository.file(doc.id, first.processedFile),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.Default.Description, contentDescription = null)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(doc.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${doc.pages.size} page${if (doc.pages.size == 1) "" else "s"} • $date",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (folderLabel != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Folder, contentDescription = null,
                            modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            folderLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (selecting) {
                Checkbox(checked = isSelected, onCheckedChange = { onClick() })
            } else {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Share as PDF") },
                            leadingIcon = { Icon(Icons.Default.PictureAsPdf, contentDescription = null) },
                            enabled = doc.pages.isNotEmpty(),
                            onClick = { menu = false; onSharePdf() },
                        )
                        DropdownMenuItem(
                            text = { Text("Share as…") },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                            enabled = doc.pages.isNotEmpty(),
                            onClick = { menu = false; onShare() },
                        )
                        DropdownMenuItem(
                            text = { Text("Move to folder") },
                            leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                            onClick = { menu = false; onMove() },
                        )
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = { menu = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}
