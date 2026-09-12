package com.freedomfighter.readersaudio

import android.Manifest
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.freedomfighter.readersaudio.data.Item
import com.freedomfighter.readersaudio.data.Prefs
import com.freedomfighter.readersaudio.ui.ListScreen
import com.freedomfighter.readersaudio.ui.LocalColors
import com.freedomfighter.readersaudio.ui.Nav
import com.freedomfighter.readersaudio.ui.PlayerScreen
import com.freedomfighter.readersaudio.ui.ReaderTheme
import com.freedomfighter.readersaudio.ui.Screen
import com.freedomfighter.readersaudio.ui.SettingsScreen
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the media controller reports, polled for the screens. */
class PlayerUi {
    var mediaId by mutableStateOf("")
    var playing by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
}

class MainActivity : ComponentActivity() {
    val nav = Nav()
    val ui = PlayerUi()
    val app get() = application as App
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingPlay: Item? = null
    private var pendingCopy: Item? = null

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { uris.mapNotNull { app.library.import(it) } }
            if (items.size == 1) { play(items[0]); nav.push(Screen.Player) }
        }
    }

    /** "Save a copy to a folder": the system's own folder picker, then a plain stream copy. */
    private val createCopy = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val item = pendingCopy; pendingCopy = null
        val target = r.data?.data
        if (r.resultCode != RESULT_OK || item == null || target == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(Uri.parse(item.uri))!!.use { i -> contentResolver.openOutputStream(target)!!.use { o -> i.copyTo(o, 256 * 1024) } }
                }.isSuccess
            }
            Toast.makeText(this@MainActivity, if (ok) R.string.copied else R.string.copy_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handle(intent)
        val activity = this
        setContent {
            val settings by app.prefs.settings.collectAsState()
            ReaderTheme(settings) {
                Bars()
                LaunchedEffect(Unit) {
                    while (true) {
                        controller?.let { c ->
                            ui.mediaId = c.currentMediaItem?.mediaId ?: ""
                            ui.playing = c.isPlaying
                            ui.positionMs = c.currentPosition
                            ui.durationMs = c.duration.takeIf { it > 0 } ?: 0L
                        }
                        delay(250)
                    }
                }
                when (nav.current) {
                    Screen.List -> ListScreen(nav, app, activity)
                    Screen.Player -> PlayerScreen(nav, app, activity)
                    Screen.Settings -> SettingsScreen(nav, app)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }

    override fun onStart() {
        super.onStart()
        val f = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        controllerFuture = f
        f.addListener({
            controller = runCatching { f.get() }.getOrNull()
            pendingPlay?.let { pendingPlay = null; play(it) }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        super.onStop()
    }

    /** "Open with" and "share to" from other apps; the widget's title opens the player. */
    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { open(it) }
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
                uri?.let { open(it) }
            }
            ACTION_OPEN_PLAYER -> { nav.home(); if (app.library.last() != null) nav.push(Screen.Player) }
            else -> return
        }
        intent.action = null
    }

    private fun open(uri: Uri) = lifecycleScope.launch {
        val item = withContext(Dispatchers.IO) { app.library.import(uri) }
        if (item == null) { Toast.makeText(this@MainActivity, R.string.cant_open, Toast.LENGTH_LONG).show(); return@launch }
        play(item)
        nav.home(); nav.push(Screen.Player)
    }

    // ---- playback, through the session ----

    fun play(item: Item) {
        val c = controller ?: run { pendingPlay = item; return }
        if (c.currentMediaItem?.mediaId == item.id && c.playbackState != Player.STATE_IDLE) {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
            c.play(); return
        }
        val start = if (item.durationMs > 0 && item.positionMs > item.durationMs - 3_000) 0L else item.positionMs
        c.setMediaItem(PlaybackService.mediaItem(item), start)
        c.setPlaybackSpeed(app.prefs.settings.value.speed)
        c.prepare()
        c.play()
    }

    fun toggle() {
        val c = controller ?: return
        when {
            c.isPlaying -> c.pause()
            c.mediaItemCount == 0 -> app.library.last()?.let { play(it) }
            c.playbackState == Player.STATE_ENDED -> { c.seekTo(0); c.play() }
            c.playbackState == Player.STATE_IDLE -> { c.prepare(); c.play() }
            else -> c.play()
        }
    }

    fun seekBy(ms: Long) {
        val c = controller ?: return
        val max = c.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        c.seekTo((c.currentPosition + ms).coerceIn(0, max))
    }

    fun seekTo(ms: Long) { controller?.seekTo(ms) }
    fun setSpeed(f: Float) { app.prefs.setSpeed(f); controller?.setPlaybackSpeed(f) }
    fun stopPlayback() { controller?.sendCustomCommand(SessionCommand(PlaybackService.ACTION_STOP, Bundle.EMPTY), Bundle.EMPTY) }
    fun openFiles() = pickFiles.launch(arrayOf("audio/*"))
    fun transcribe(item: Item, language: String, model: String, summary: Boolean) = TranscribeService.start(this, item.id, language, model, summary)
    /** The main points of a transcript already saved: no audio is read again. */
    fun summarise(item: Item) = TranscribeService.points(this, item.id, Prefs.deviceLanguage())
    fun cancelTranscription() = TranscribeService.cancel(this)
    fun remove(item: Item) { if (ui.mediaId == item.id) stopPlayback(); app.library.remove(item.id) }

    // ---- sharing and copies ----

    fun shareAudio(item: Item) {
        val uri = app.library.shareUri(item)
        val send = Intent(Intent.ACTION_SEND).setType(item.mime.takeIf { '/' in it } ?: "audio/*")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, item.title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(item.name, uri)
        runCatching { startActivity(Intent.createChooser(send, item.title)) }
    }

    /** The .txt as a file, and as plain text too when it is short, so notes and messaging apps take it. */
    fun shareTranscript(item: Item) {
        val uri = Uri.parse(item.transcriptUri)
        val bytes = runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull()
        if (bytes == null) { transcriptGone(item); return }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, item.title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (bytes.size < 100_000) send.putExtra(Intent.EXTRA_TEXT, String(bytes, Charsets.UTF_8))
        send.clipData = ClipData.newRawUri(item.title, uri)
        runCatching { startActivity(Intent.createChooser(send, item.title)) }
    }

    /** Any reader: Reader's Books, or whatever opens text. */
    fun openTranscript(item: Item) {
        val uri = Uri.parse(item.transcriptUri)
        if (runCatching { contentResolver.openInputStream(uri)!!.close() }.isFailure) { transcriptGone(item); return }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/plain").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(view, item.title)) }
    }

    private fun transcriptGone(item: Item) {
        app.library.update(item.id) { it.copy(transcriptUri = "") }
        Toast.makeText(this, R.string.transcript_gone, Toast.LENGTH_LONG).show()
    }

    fun saveCopy(item: Item) {
        pendingCopy = item
        val mime = item.mime.takeIf { '/' in it && !it.endsWith("*") } ?: "application/octet-stream"
        runCatching {
            createCopy.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, item.name))
        }
    }

    @Composable
    private fun Bars() {
        val view = LocalView.current
        val colors = LocalColors.current
        LaunchedEffect(colors.isDark) {
            val c = WindowInsetsControllerCompat(window, view)
            c.isAppearanceLightStatusBars = !colors.isDark
            c.isAppearanceLightNavigationBars = !colors.isDark
        }
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "com.freedomfighter.readersaudio.OPEN_PLAYER"
    }
}
