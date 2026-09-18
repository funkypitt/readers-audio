package com.freedomfighter.readersaudio.transcribe

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.freedomfighter.readersaudio.R
import com.freedomfighter.readers.speech.audio.Decode16k
import com.freedomfighter.readersaudio.data.Item
import com.freedomfighter.readers.speech.whisper.Models
import com.freedomfighter.readers.speech.whisper.Paragraphs
import com.freedomfighter.readers.speech.whisper.Prompts
import com.freedomfighter.readers.speech.whisper.Segment
import com.freedomfighter.readers.speech.whisper.Vad
import com.freedomfighter.readers.speech.whisper.WhisperSession
import java.io.File

/** Whisper on the phone, over a whole file in five-minute pieces, into a plain .txt. */
object Transcriber {
    private const val CHUNK_SECONDS = 300
    const val FOLDER = "Transcriptions"

    /** The transcript text, or null when cancelled. [onProgress] gets a phase (model, transcribe) and 0–100. */
    fun run(ctx: Context, item: Item, language: String?, modelKey: String, onProgress: (String, Int) -> Unit, cancelled: () -> Boolean): String? {
        val model = Models.byKey(modelKey)
        if (!Models.isDownloaded(ctx, model)) {
            onProgress("model", 0)
            Models.download(ctx, model, onProgress = { onProgress("model", it) }, cancelled = cancelled)
        }
        if (cancelled()) return null
        // Ours, or the sibling app's copy by file descriptor: either way a path whisper reads.
        val handle = Models.open(ctx, model) ?: error("model not here")
        val uri = Uri.parse(item.uri)
        val totalMs = durationMs(ctx, uri).takeIf { it > 0 } ?: item.durationMs.coerceAtLeast(1)
        val segments = ArrayList<Segment>()
        var aborted = false
        onProgress("transcribe", 0)
        handle.use { WhisperSession(handle.path, Vad.modelPath(ctx)).use { session ->
            Decode16k.chunks(ctx, uri, CHUNK_SECONDS) { pcm, startMs ->
                if (cancelled()) { aborted = true; return@chunks false }
                val chunkMs = pcm.size / 16L
                val prompt = if (segments.isEmpty()) Prompts.style(language) else Prompts.forPiece(language, segments.takeLast(12).joinToString(" ") { it.text })
                val segs = session.run(pcm, language, prompt) { p ->
                    onProgress("transcribe", (((startMs + chunkMs * p / 100.0) / totalMs) * 100).toInt().coerceIn(0, 99))
                }
                if (segs == null) { aborted = true; return@chunks false }
                segs.forEach { segments += it.copy(startMs = it.startMs + startMs, endMs = it.endMs + startMs) }
                true
            }
        } }
        if (aborted || cancelled()) return null
        return Paragraphs.build(segments, ctx.getString(R.string.no_speech))
    }

    fun durationMs(ctx: Context, uri: Uri): Long = runCatching {
        MediaMetadataRetriever().use { it.setDataSource(ctx, uri); it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() }
    }.getOrDefault(0L)

    /** The exported .txt as it is on disk, or null when the file is gone. */
    fun read(ctx: Context, item: Item): String? = runCatching {
        ctx.contentResolver.openInputStream(Uri.parse(item.transcriptUri))!!.use { String(it.readBytes(), Charsets.UTF_8) }
    }.getOrNull()

    // ---- What the app keeps for itself: the transcript and its points, each in a file of its own.
    // The exported .txt in Documents is a rendering of the two, never read back as a source of
    // truth: it used to be, through a localised heading, and a phone whose language changed
    // could no longer find its own points and stacked a second block on the first.

    private fun keep(ctx: Context) = File(ctx.filesDir, "transcripts").apply { mkdirs() }
    fun textFile(ctx: Context, id: String) = File(keep(ctx), "$id.txt")
    fun pointsFile(ctx: Context, id: String) = File(keep(ctx), "$id.points.txt")

    /** The transcript alone. An item from before the app kept it is recovered from the exported file, once. */
    fun text(ctx: Context, item: Item): String? {
        textFile(ctx, item.id).takeIf { it.exists() }?.let { return it.readText() }
        val exported = read(ctx, item) ?: return null
        val plain = withoutPoints(ctx, exported)
        textFile(ctx, item.id).writeText(plain)
        pointsIn(ctx, exported)?.let { pointsFile(ctx, item.id).writeText(it) }
        return plain
    }

    /** The main points, or null when none were written. An older item's are recovered from the exported file, once. */
    fun points(ctx: Context, item: Item): String? {
        pointsFile(ctx, item.id).takeIf { it.exists() }?.let { return it.readText() }
        if (item.transcriptUri.isBlank()) return null
        val p = read(ctx, item)?.let { pointsIn(ctx, it) } ?: return null
        pointsFile(ctx, item.id).writeText(p)
        return p
    }

    /** What leaves the app: the points under their heading when there are some, then the transcript. */
    fun render(ctx: Context, points: String?, text: String): String =
        if (points.isNullOrBlank()) text else head(ctx) + "\n\n" + points.trim() + "\n\n\n" + text

    fun forget(ctx: Context, id: String) { textFile(ctx, id).delete(); pointsFile(ctx, id).delete() }

    private fun head(ctx: Context) = ctx.getString(R.string.summary_title).uppercase()

    /** The main points at the head of an exported file written by an earlier version, or null. */
    private fun pointsIn(ctx: Context, text: String): String? {
        val h = HEADS.firstOrNull { text.startsWith(it) } ?: return null
        val cut = text.indexOf("\n\n\n")
        return (if (cut < 0) text else text.substring(0, cut)).removePrefix(h).trim().ifBlank { null }
    }

    /** An exported file written by an earlier version, without its block of points. */
    private fun withoutPoints(ctx: Context, text: String): String {
        if (HEADS.none { text.startsWith(it) }) return text
        val cut = text.indexOf("\n\n\n")
        return if (cut < 0) text else text.substring(cut + 3)
    }

    /** The heading in every language the app has spoken, so a phone that changed language still finds its old points. */
    private val HEADS = listOf("MAIN POINTS", "POINTS PRINCIPAUX", "WICHTIGSTE PUNKTE", "PUNTOS PRINCIPALES", "PONTOS PRINCIPAIS", "ОСНОВНЫЕ МЫСЛИ")

    /**
     * Save as Documents/Transcriptions/<title>.txt through MediaStore, so any reader opens it;
     * a second transcription of the same file overwrites the first.
     */
    fun save(ctx: Context, item: Item, text: String): Uri {
        val cr = ctx.contentResolver
        val bytes = text.toByteArray(Charsets.UTF_8)
        item.transcriptUri.takeIf { it.isNotBlank() }?.let { existing ->
            val u = Uri.parse(existing)
            if (runCatching { cr.openOutputStream(u, "wt")!!.use { it.write(bytes) } }.isSuccess) return u
        }
        val name = item.title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").trim().ifBlank { "transcript" }.take(100) + ".txt"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER)
        }
        val uri = cr.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("cannot create $name")
        cr.openOutputStream(uri)!!.use { it.write(bytes) }
        return uri
    }
}
