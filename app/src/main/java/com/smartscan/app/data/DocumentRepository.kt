package com.smartscan.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Documents: docs/<id>/doc.json + page images.
 * Folders: folders.json (a flat list with parent links, so folders can be nested).
 */
object DocumentRepository {
    private lateinit var root: File
    private lateinit var foldersFile: File
    private val mutex = Mutex()

    private val _documents = MutableStateFlow<List<ScanDocument>>(emptyList())
    val documents: StateFlow<List<ScanDocument>> = _documents.asStateFlow()

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    fun init(context: Context) {
        root = File(context.filesDir, "docs").apply { mkdirs() }
        foldersFile = File(context.filesDir, "folders.json")
        _documents.value = root.listFiles()
            ?.mapNotNull { dir ->
                runCatching { fromJson(JSONObject(File(dir, "doc.json").readText())) }.getOrNull()
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
        _folders.value = runCatching {
            val arr = JSONArray(foldersFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Folder(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    parentId = if (o.isNull("parentId")) null else o.getString("parentId"),
                    color = o.optInt("color", 0),
                    createdAt = o.optLong("createdAt", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun get(id: String): ScanDocument? = _documents.value.firstOrNull { it.id == id }

    fun docDir(id: String): File = File(root, id)

    fun file(docId: String, name: String): File = File(docDir(docId), name)

    // ------------------------------------------------------------------ documents

    suspend fun create(folderId: String? = null): ScanDocument = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val name = "Scan " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date(now))
            val doc = ScanDocument(UUID.randomUUID().toString(), name, now, now, emptyList(), folderId)
            write(doc)
            doc
        }
    }

    /** Atomic read-modify-write of a document. [touch] updates the "modified" time. */
    suspend fun update(
        docId: String,
        touch: Boolean = true,
        transform: (ScanDocument) -> ScanDocument,
    ): ScanDocument? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = get(docId) ?: return@withLock null
            var updated = transform(current)
            if (touch) updated = updated.copy(updatedAt = System.currentTimeMillis())
            write(updated)
            updated
        }
    }

    suspend fun delete(docId: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                docDir(docId).deleteRecursively()
                _documents.value = _documents.value.filterNot { it.id == docId }
            }
        }
    }

    suspend fun moveDocuments(docIds: Collection<String>, folderId: String?) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                for (id in docIds) {
                    val doc = get(id) ?: continue
                    if (doc.folderId != folderId) write(doc.copy(folderId = folderId))
                }
            }
        }
    }

    // ------------------------------------------------------------------ folders

    suspend fun createFolder(name: String, parentId: String?, color: Int): Folder =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val folder = Folder(UUID.randomUUID().toString(), name, parentId, color, System.currentTimeMillis())
                writeFolders(_folders.value + folder)
                folder
            }
        }

    suspend fun updateFolder(id: String, transform: (Folder) -> Folder) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                writeFolders(_folders.value.map { if (it.id == id) transform(it) else it })
            }
        }
    }

    /** The folder itself plus all folders nested inside it. */
    fun descendantFolderIds(id: String): Set<String> {
        val all = _folders.value
        val result = mutableSetOf(id)
        var frontier = setOf(id)
        while (frontier.isNotEmpty()) {
            frontier = all.filter { it.parentId in frontier && it.id !in result }.map { it.id }.toSet()
            result += frontier
        }
        return result
    }

    /**
     * Deletes a folder. If [deleteContents] is false, its documents and sub-folders move up
     * to the parent folder; otherwise everything inside is deleted too.
     */
    suspend fun deleteFolder(id: String, deleteContents: Boolean) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val folder = _folders.value.firstOrNull { it.id == id } ?: return@withLock
                if (deleteContents) {
                    val ids = descendantFolderIds(id)
                    val doomed = _documents.value.filter { it.folderId in ids }
                    doomed.forEach { docDir(it.id).deleteRecursively() }
                    val doomedIds = doomed.map { it.id }.toSet()
                    _documents.value = _documents.value.filterNot { it.id in doomedIds }
                    writeFolders(_folders.value.filterNot { it.id in ids })
                } else {
                    _documents.value.filter { it.folderId == id }
                        .forEach { write(it.copy(folderId = folder.parentId)) }
                    writeFolders(
                        _folders.value
                            .filterNot { it.id == id }
                            .map { if (it.parentId == id) it.copy(parentId = folder.parentId) else it },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ persistence

    private fun writeFolders(list: List<Folder>) {
        val arr = JSONArray()
        list.forEach { f ->
            arr.put(JSONObject().apply {
                put("id", f.id)
                put("name", f.name)
                put("parentId", f.parentId ?: JSONObject.NULL)
                put("color", f.color)
                put("createdAt", f.createdAt)
            })
        }
        val tmp = File(foldersFile.parentFile, "folders.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(foldersFile)
        _folders.value = list
    }

    private fun write(doc: ScanDocument) {
        val dir = docDir(doc.id).apply { mkdirs() }
        val tmp = File(dir, "doc.json.tmp")
        tmp.writeText(toJson(doc).toString())
        tmp.renameTo(File(dir, "doc.json"))
        _documents.value = (_documents.value.filterNot { it.id == doc.id } + doc)
            .sortedByDescending { it.updatedAt }
    }

    private fun toJson(d: ScanDocument): JSONObject = JSONObject().apply {
        put("id", d.id)
        put("name", d.name)
        put("createdAt", d.createdAt)
        put("updatedAt", d.updatedAt)
        put("folderId", d.folderId ?: JSONObject.NULL)
        put("pages", JSONArray().apply {
            d.pages.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id)
                    put("original", p.originalFile)
                    put("processed", p.processedFile)
                    put("filter", p.filter.name)
                    put("rotation", p.rotation)
                    put("adj", JSONObject().apply {
                        put("brightness", p.adjust.brightness)
                        put("contrast", p.adjust.contrast)
                        put("sharpness", p.adjust.sharpness)
                        put("shadows", p.adjust.shadows)
                        put("color", p.adjust.color)
                        put("textWeight", p.adjust.textWeight)
                    })
                    put("corners", JSONArray().apply {
                        p.corners.forEach { c -> put(c.x.toDouble()); put(c.y.toDouble()) }
                    })
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): ScanDocument {
        val arr = o.getJSONArray("pages")
        val pages = (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            val c = p.getJSONArray("corners")
            val adj = p.optJSONObject("adj")
            ScanPage(
                id = p.getString("id"),
                originalFile = p.getString("original"),
                processedFile = p.getString("processed"),
                corners = (0 until 4).map { k ->
                    Pt(c.getDouble(k * 2).toFloat(), c.getDouble(k * 2 + 1).toFloat())
                },
                filter = runCatching { FilterType.valueOf(p.getString("filter")) }
                    .getOrDefault(FilterType.MAGIC),
                rotation = p.optInt("rotation", 0),
                adjust = if (adj == null) Adjust() else Adjust(
                    brightness = adj.optInt("brightness", 0),
                    contrast = adj.optInt("contrast", 0),
                    sharpness = adj.optInt("sharpness", 0),
                    shadows = adj.optInt("shadows", 0),
                    color = adj.optInt("color", 0),
                    textWeight = adj.optInt("textWeight", 0),
                ),
            )
        }
        return ScanDocument(
            id = o.getString("id"),
            name = o.getString("name"),
            createdAt = o.getLong("createdAt"),
            updatedAt = o.getLong("updatedAt"),
            pages = pages,
            folderId = if (o.isNull("folderId")) null else o.getString("folderId"),
        )
    }
}
