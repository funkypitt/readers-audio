package com.freedomfighter.readersaudio.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** One audio file the player knows: where it is, where it was left, and its transcript if any. */
data class Item(
    val id: String,
    val uri: String,
    val title: String,
    val name: String,
    val mime: String,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val lastPlayed: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
    /** content:// URI of the .txt in Documents/Transcriptions, "" when none. */
    val transcriptUri: String = "",
    /** Whether that .txt carries its main points at the head. */
    val hasPoints: Boolean = false
) {
    val isCopy: Boolean get() = uri.startsWith("file:")
}

fun clock(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

/** The list of files, last played first, kept as one JSON file. */
class Library(private val context: Context) {
    private val index = File(context.filesDir, "library.json")
    private val _items = MutableStateFlow(sorted(load()))
    val items: StateFlow<List<Item>> = _items
    var onChange: (() -> Unit)? = null

    private fun sorted(list: List<Item>) = list.sortedWith(compareByDescending<Item> { it.lastPlayed }.thenByDescending { it.addedAt })

    private fun load(): List<Item> = runCatching {
        val arr = JSONArray(index.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Item(o.getString("id"), o.getString("uri"), o.optString("title"), o.optString("name"), o.optString("mime", "audio/*"),
                o.optLong("durationMs"), o.optLong("positionMs"), o.optLong("lastPlayed"), o.optLong("addedAt"), o.optString("transcriptUri"),
                o.optBoolean("hasPoints"))
        }
    }.getOrDefault(emptyList())

    @Synchronized private fun save(list: List<Item>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().put("id", r.id).put("uri", r.uri).put("title", r.title).put("name", r.name).put("mime", r.mime)
                .put("durationMs", r.durationMs).put("positionMs", r.positionMs).put("lastPlayed", r.lastPlayed).put("addedAt", r.addedAt)
                .put("transcriptUri", r.transcriptUri).put("hasPoints", r.hasPoints))
        }
        val tmp = File(index.parentFile, "library.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(index)) { index.writeText(arr.toString()); tmp.delete() }
        _items.value = sorted(list)
        onChange?.invoke()
    }

    fun get(id: String): Item? = _items.value.firstOrNull { it.id == id }
    fun last(): Item? = _items.value.filter { it.lastPlayed > 0 }.maxByOrNull { it.lastPlayed }

    @Synchronized fun update(item: Item) = save(_items.value.map { if (it.id == item.id) item else it })
    @Synchronized fun update(id: String, f: (Item) -> Item) { get(id)?.let { update(f(it)) } }

    @Synchronized fun remove(id: String) {
        get(id)?.let { if (it.isCopy) runCatching { File(Uri.parse(it.uri).path!!).delete() } }
        save(_items.value.filterNot { it.id == id })
    }

    /**
     * Add a file picked or handed over by another app. A picked document keeps a persistable
     * read grant; a one-off grant ("open with" from a file manager) is copied into the app, so
     * the file still plays tomorrow. Blocking: call off the main thread.
     */
    fun import(uri: Uri): Item? {
        val id = idFor(uri.toString())
        get(id)?.let { return it }
        val cr = context.contentResolver
        val name = runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
        val mime = cr.getType(uri) ?: "audio/*"
        var stored = uri.toString()
        val persisted = runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
        if (!persisted) {
            val ext = name.substringAfterLast('.', "").ifBlank { "audio" }
            val f = File(context.filesDir, "audio/$id.$ext").apply { parentFile?.mkdirs() }
            val ok = runCatching { cr.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o, 256 * 1024) } } }.isSuccess
            if (!ok) { f.delete(); return null }
            stored = Uri.fromFile(f).toString()
        }
        val item = Item(id, stored, name.substringBeforeLast('.').ifBlank { name }, name, mime)
        synchronized(this) { save(_items.value.filterNot { it.id == id } + item) }
        return item
    }

    /** A URI other apps can read: the document itself, or the copy through our FileProvider. */
    fun shareUri(item: Item): Uri =
        if (item.isCopy) FileProvider.getUriForFile(context, context.packageName + ".files", File(Uri.parse(item.uri).path!!)) else Uri.parse(item.uri)

    companion object {
        fun idFor(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }
}
