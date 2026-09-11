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
    private data class Job(val id: String, val language: String, val model: String)
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
            ACTION_CANCEL -> { cancelled.set(true); queue.clear(); Live.waiting.clear(); WhisperLib.cancel() }
            else -> intent?.getStringExtra(EXTRA_ID)?.let { id ->
                if (id != Live.id && queue.none { it.id == id }) {
                    queue.add(Job(id, intent.getStringExtra(EXTRA_LANGUAGE) ?: "", intent.getStringExtra(EXTRA_MODEL) ?: Models.DEFAULT))
                    Live.waiting.add(id)
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
                val id = job.id
                Live.waiting.remove(id)
                val item = app.library.get(id) ?: continue
                Live.id = id; Live.phase = "transcribe"; Live.percent = 0
                if (Live.errorId == id) { Live.errorId = ""; Live.error = "" }
                try {
                    val text = withContext(Dispatchers.Default) {
                        Transcriber.run(this@TranscribeService, item, job.language.ifBlank { null }, job.model,
                            { phase, pct -> Live.phase = phase; Live.percent = pct }, { cancelled.get() })
                    }
                    if (text != null && !cancelled.get()) {
                        Live.phase = "save"
                        val uri = withContext(Dispatchers.IO) { Transcriber.save(this@TranscribeService, app.library.get(id) ?: item, text) }
                        app.library.update(id) { it.copy(transcriptUri = uri.toString()) }
                    }
                } catch (e: Exception) {
                    if (!cancelled.get()) { Live.errorId = id; Live.error = (e.message ?: e.javaClass.simpleName).take(120) }
                }
            }
        } finally {
            ticker.cancel()
            Live.id = ""; Live.phase = ""; Live.percent = 0; Live.waiting.clear()
            runCatching { if (lock?.isHeld == true) lock?.release() }
        }
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
    }

    companion object {
        const val ACTION_CANCEL = "com.freedomfighter.readersaudio.TRANSCRIBE_CANCEL"
        const val EXTRA_ID = "id"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_MODEL = "model"
        private const val CHANNEL_ID = "transcription"
        private const val NOTIF_ID = 7

        fun phaseLabel(ctx: Context, phase: String, percent: Int): String = when (phase) {
            "model" -> ctx.getString(R.string.phase_model, percent)
            "transcribe" -> ctx.getString(R.string.phase_transcribe, percent)
            "save" -> ctx.getString(R.string.phase_save)
            else -> ctx.getString(R.string.phase_waiting)
        }

        fun start(ctx: Context, id: String, language: String, model: String) = ContextCompat.startForegroundService(ctx,
            Intent(ctx, TranscribeService::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_LANGUAGE, language).putExtra(EXTRA_MODEL, model))
        fun cancel(ctx: Context) { if (Live.id.isNotBlank() || Live.waiting.isNotEmpty()) ContextCompat.startForegroundService(ctx, Intent(ctx, TranscribeService::class.java).setAction(ACTION_CANCEL)) }
    }
}
