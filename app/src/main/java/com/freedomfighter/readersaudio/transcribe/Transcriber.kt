package com.freedomfighter.readersaudio.transcribe

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.freedomfighter.readersaudio.R
import com.freedomfighter.readersaudio.audio.Decode16k
import com.freedomfighter.readersaudio.data.Item
import com.freedomfighter.readersaudio.whisper.Models
import com.freedomfighter.readersaudio.whisper.Paragraphs
import com.freedomfighter.readersaudio.whisper.Prompts
import com.freedomfighter.readersaudio.whisper.Segment
import com.freedomfighter.readersaudio.whisper.WhisperSession

/** Whisper on the phone, over a whole file in five-minute pieces, into a plain .txt. */
object Transcriber {
    private const val CHUNK_SECONDS = 300
    const val FOLDER = "Transcriptions"

    /** The transcript text, or null when cancelled. [onProgress] gets a phase (model, transcribe) and 0–100. */
    fun run(ctx: Context, item: Item, language: String?, modelKey: String, onProgress: (String, Int) -> Unit, cancelled: () -> Boolean): String? {
        val model = Models.byKey(modelKey)
        if (!Models.isDownloaded(ctx, model)) {
            onProgress("model", 0)
            Models.download(ctx, model) { onProgress("model", it) }
        }
        if (cancelled()) return null
        val uri = Uri.parse(item.uri)
        val totalMs = durationMs(ctx, uri).takeIf { it > 0 } ?: item.durationMs.coerceAtLeast(1)
        val segments = ArrayList<Segment>()
        var aborted = false
        onProgress("transcribe", 0)
        WhisperSession(Models.file(ctx, model)).use { session ->
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
        }
        if (aborted || cancelled()) return null
        return Paragraphs.build(segments, ctx.getString(R.string.no_speech))
    }

    fun durationMs(ctx: Context, uri: Uri): Long = runCatching {
        MediaMetadataRetriever().use { it.setDataSource(ctx, uri); it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() }
    }.getOrDefault(0L)

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
