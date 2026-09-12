package com.freedomfighter.readersaudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.freedomfighter.readersaudio.data.Item
import com.freedomfighter.readersaudio.summary.Summariser
import com.freedomfighter.readersaudio.summary.SummaryModel
import com.freedomfighter.readersaudio.transcribe.Transcriber
import com.freedomfighter.readersaudio.whisper.Models
import com.freedomfighter.readersaudio.whisper.WhisperLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transcribes queued files one after the other, in the foreground (type dataSync) under a
 * wake lock, with a progress notification that can stop it. Everything stays on the phone.
 */
class TranscribeService : Service() {
    private data class Job(val id: String, val language: String, val model: String, val summary: Boolean,
        /** Write the main points of a transcript already made, instead of transcribing again. */
        val pointsOnly: Boolean = false,
        /** Fetch the two gigabytes of the model that writes the points. */
        val fetchModel: Boolean = false)
    private val queue = ConcurrentLinkedQueue<Job>()
    private val cancelled = AtomicBoolean(false)
    private var running = false
    private var lock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val app get() = application as App

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_CANCEL -> { cancelled.set(true); queue.clear(); Live.waiting.clear(); Live.waitingPoints.clear(); WhisperLib.cancel() }
            ACTION_FETCH_MODEL -> if (queue.none { it.fetchModel }) queue.add(Job("", "", Models.DEFAULT, false, fetchModel = true))
            else -> intent?.getStringExtra(EXTRA_ID)?.let { id ->
                if (id != Live.id && queue.none { it.id == id }) {
                    queue.add(Job(id, intent.getStringExtra(EXTRA_LANGUAGE) ?: "", intent.getStringExtra(EXTRA_MODEL) ?: Models.DEFAULT,
                        intent.getBooleanExtra(EXTRA_SUMMARY, false), intent.getBooleanExtra(EXTRA_POINTS_ONLY, false)))
                    Live.waiting.add(id)
                    if (intent.getBooleanExtra(EXTRA_POINTS_ONLY, false)) Live.waitingPoints.add(id)
                }
            }
        }
        if (!running) { running = true; scope.launch { work(); finish() } }
        return START_NOT_STICKY
    }

    private suspend fun work() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReadersAudio:transcribe").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        val ticker = scope.launch { while (isActive) { pushNotification(); delay(1500) } }
        try {
            while (!cancelled.get()) {
                val job = queue.poll() ?: break
                if (job.fetchModel) { fetchModel(); continue }
                val id = job.id
                Live.waiting.remove(id); Live.waitingPoints.remove(id)
                val item = app.library.get(id) ?: continue
                Live.id = id; Live.phase = if (job.pointsOnly) "summary" else "transcribe"; Live.percent = 0
                if (Live.errorId == id) { Live.errorId = ""; Live.error = "" }
                if (job.pointsOnly) { points(item, job.language); continue }
                try {
                    val text = withContext(Dispatchers.Default) {
                        Transcriber.run(this@TranscribeService, item, job.language.ifBlank { null }, job.model,
                            { phase, pct -> Live.phase = phase; Live.percent = pct }, { cancelled.get() })
                    }
                    if (text != null && !cancelled.get()) {
                        // The transcript is saved before anything else is attempted on it: an hour
                        // of work must not hang on a summary that may be interrupted.
                        Live.phase = "save"
                        val uri = withContext(Dispatchers.IO) { Transcriber.save(this@TranscribeService, app.library.get(id) ?: item, text) }
                        app.library.update(id) { it.copy(transcriptUri = uri.toString(), hasPoints = false) }
                        if (job.summary && !cancelled.get()) {
                            val whole = withContext(Dispatchers.Default) { withPoints(text, job.language) }
                            if (whole != text && !cancelled.get()) {
                                Live.phase = "save"
                                withContext(Dispatchers.IO) { Transcriber.save(this@TranscribeService, app.library.get(id) ?: item, whole) }
                                app.library.update(id) { it.copy(hasPoints = true) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!cancelled.get()) { Live.errorId = id; Live.error = (e.message ?: e.javaClass.simpleName).take(120) }
                }
            }
        } finally {
            ticker.cancel()
            Live.id = ""; Live.phase = ""; Live.percent = 0; Live.waiting.clear(); Live.waitingPoints.clear()
            runCatching { if (lock?.isHeld == true) lock?.release() }
        }
    }

    /**
     * The two gigabytes of the model that writes the points, fetched here rather than in a
     * coroutine of the application: such a coroutine dies with the process, and Android ends a
     * backgrounded process long before a download of this size is over — which is why it took
     * the user three attempts, none of them saying a word about the failure. In the service the
     * wake lock and the notification keep the phone on the job, the row shows where it is, and
     * what came down already is picked up again on the next try.
     */
    private suspend fun fetchModel() {
        Live.id = ""; Live.phase = "model"; Live.percent = 0
        app.modelError.value = ""
        try {
            withContext(Dispatchers.IO) {
                SummaryModel.download(this@TranscribeService, { Live.percent = it.coerceIn(0, 100) }, { cancelled.get() })
            }
            if (SummaryModel.isDownloaded(this)) app.prefs.setSummaryOnPhone(true)
        } catch (e: Exception) {
            if (!cancelled.get()) app.modelError.value = (e.message ?: e.javaClass.simpleName).take(120)
        }
    }

    /**
     * The main points of a transcript already saved, put above it in the same file. Asked for on
     * its own, from the player or the file's menu: the points are otherwise only offered before a
     * transcription, and no one wants to transcribe an hour of audio again to get them.
     *
     * Here a failure is said out loud, unlike during a transcription: the points were the whole
     * of the job. An earlier block of points is replaced, not stacked, and nothing is written
     * unless new points came back — a failed attempt leaves the file exactly as it was.
     */
    private suspend fun points(item: Item, language: String) {
        val id = item.id
        try {
            val saved = withContext(Dispatchers.IO) { Transcriber.read(this@TranscribeService, item) }
            if (saved == null) {
                app.library.update(id) { it.copy(transcriptUri = "") }
                Live.errorId = id; Live.error = getString(R.string.transcript_gone)
                return
            }
            val plain = Transcriber.withoutPoints(this, saved)
            val whole = withContext(Dispatchers.Default) { withPoints(plain, language) }
            if (cancelled.get()) return
            if (whole == plain) { Live.errorId = id; Live.error = getString(R.string.summary_failed); return }
            Live.phase = "save"
            withContext(Dispatchers.IO) { Transcriber.save(this@TranscribeService, app.library.get(id) ?: item, whole) }
            app.library.update(id) { it.copy(hasPoints = true) }
        } catch (e: Exception) {
            if (!cancelled.get()) { Live.errorId = id; Live.error = (e.message ?: e.javaClass.simpleName).take(120) }
        }
    }

    /**
     * The transcript with its main points above it, when a small model on the phone can write
     * them. One file holds both, because the transcript is what leaves the app — opened in a
     * reader, shared, filed away — and a summary that lived somewhere else would be lost.
     *
     * Every failure is silent and returns the transcript untouched: the summary is a bonus,
     * never a reason to lose an hour of transcription.
     */
    private fun withPoints(text: String, language: String): String {
        if (!SummaryModel.isDownloaded(this) || !SummaryModel.roomRightNow(this)) return text
        Live.phase = "summary"; Live.percent = 0
        val points = Summariser.summarise(
            model = SummaryModel.file(this),
            transcript = text,
            language = language.ifBlank { app.prefs.settings.value.language },
            onProgress = { Live.percent = it.coerceIn(0, 99) },
            cancelled = { cancelled.get() },
        ) ?: return text
        return getString(R.string.summary_title).uppercase() + "\n\n" + points + "\n\n\n" + text
    }

    private fun finish() {
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { scope.cancel(); runCatching { if (lock?.isHeld == true) lock?.release() }; super.onDestroy() }

    private fun goForeground() = startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun pushNotification() = (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.channel_transcription), NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, TranscribeService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = app.library.get(Live.id)?.title ?: getString(R.string.app_name)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(phaseLabel(this, Live.phase, Live.percent))
            .setSmallIcon(R.drawable.ic_note)
            .setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true).setShowWhen(false)
            .setProgress(100, Live.percent, Live.phase.isEmpty() || Live.phase == "save")
            .addAction(0, getString(R.string.stop), cancel)
            .build()
    }

    /** What the screens and the notification show about the transcription. */
    object Live {
        var id by mutableStateOf("")
        var phase by mutableStateOf("")   // model | transcribe | save
        var percent by mutableIntStateOf(0)
        var error by mutableStateOf("")
        var errorId by mutableStateOf("")
        val waiting = mutableStateListOf<String>()
        /** Of those waiting, the ones that are only to be given their main points. */
        val waitingPoints = mutableStateListOf<String>()
    }

    companion object {
        const val ACTION_CANCEL = "com.freedomfighter.readersaudio.TRANSCRIBE_CANCEL"
        const val ACTION_FETCH_MODEL = "com.freedomfighter.readersaudio.FETCH_SUMMARY_MODEL"
        const val EXTRA_ID = "id"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_MODEL = "model"
        const val EXTRA_SUMMARY = "summary"
        const val EXTRA_POINTS_ONLY = "points_only"
        private const val CHANNEL_ID = "transcription"
        private const val NOTIF_ID = 7

        fun phaseLabel(ctx: Context, phase: String, percent: Int): String = when (phase) {
            "model" -> ctx.getString(R.string.phase_model, percent)
            "transcribe" -> ctx.getString(R.string.phase_transcribe, percent)
            "summary" -> ctx.getString(R.string.phase_summary, percent)
            "save" -> ctx.getString(R.string.phase_save)
            else -> ctx.getString(R.string.phase_waiting)
        }

        fun start(ctx: Context, id: String, language: String, model: String, summary: Boolean) = ContextCompat.startForegroundService(ctx,
            Intent(ctx, TranscribeService::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_LANGUAGE, language).putExtra(EXTRA_MODEL, model)
                .putExtra(EXTRA_SUMMARY, summary))
        /** Fetch the model that writes the points, under the notification and the wake lock. */
        fun fetchModel(ctx: Context) = ContextCompat.startForegroundService(ctx,
            Intent(ctx, TranscribeService::class.java).setAction(ACTION_FETCH_MODEL))

        /** The main points of a transcript already made, without transcribing again. */
        fun points(ctx: Context, id: String, language: String) = ContextCompat.startForegroundService(ctx,
            Intent(ctx, TranscribeService::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_LANGUAGE, language)
                .putExtra(EXTRA_POINTS_ONLY, true))

        fun cancel(ctx: Context) { if (Live.id.isNotBlank() || Live.waiting.isNotEmpty()) ContextCompat.startForegroundService(ctx, Intent(ctx, TranscribeService::class.java).setAction(ACTION_CANCEL)) }
    }
}
