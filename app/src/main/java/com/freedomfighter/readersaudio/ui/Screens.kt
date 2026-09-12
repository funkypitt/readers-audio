package com.freedomfighter.readersaudio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.freedomfighter.readersaudio.App
import com.freedomfighter.readersaudio.MainActivity
import com.freedomfighter.readersaudio.R
import com.freedomfighter.readersaudio.TranscribeService
import com.freedomfighter.readersaudio.data.FontChoice
import com.freedomfighter.readersaudio.data.Item
import com.freedomfighter.readersaudio.data.Prefs
import com.freedomfighter.readersaudio.data.TextSize
import com.freedomfighter.readersaudio.data.clock
import com.freedomfighter.readersaudio.summary.SummaryModel
import com.freedomfighter.readersaudio.whisper.Models
import com.freedomfighter.readersaudio.whisper.Prompts
import java.util.Locale

sealed class Screen {
    data object List : Screen()
    data object Player : Screen()
    data object Settings : Screen()
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.List)
    val current: Screen get() = stack.last()
    fun push(s: Screen) { if (stack.last() != s) stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.size - 1) }
    fun home() { while (stack.size > 1) stack.removeAt(stack.size - 1) }
}

fun speedLabel(f: Float): String = (if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()) + "×"

@Composable
fun languageLabel(code: String): String =
    if (code.isBlank()) stringResource(R.string.language_auto) else Locale(code).getDisplayLanguage(Locale.getDefault())

/** A hairline, [fraction] of it in the foreground colour. */
@Composable
fun Progress(fraction: Float, modifier: Modifier = Modifier) {
    val colors = LocalColors.current
    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        drawRect(colors.rule, topLeft = Offset(0f, size.height / 3), size = Size(size.width, size.height / 3))
        drawRect(colors.fg, size = Size(size.width * fraction, size.height))
    }
}

/** The line under a title: where the file was left, and the transcript. */
@Composable
fun itemStatus(item: Item, activity: MainActivity): String {
    val ctx = LocalContext.current
    val ui = activity.ui
    val t = TranscribeService.Live
    val current = ui.mediaId == item.id
    val pos = if (current) ui.positionMs else item.positionMs
    val dur = if (current && ui.durationMs > 0) ui.durationMs else item.durationMs
    val time = when { dur > 0 && pos > 0 -> clock(pos) + " / " + clock(dur); dur > 0 -> clock(dur); else -> "" }
    val extra = when {
        t.id == item.id -> TranscribeService.phaseLabel(ctx, t.phase, t.percent)
        item.id in t.waiting -> stringResource(R.string.phase_waiting)
        t.errorId == item.id && t.error.isNotBlank() -> t.error
        item.transcriptUri.isNotBlank() -> stringResource(R.string.transcript_word)
        else -> ""
    }
    return listOf(time, extra).filter { it.isNotBlank() }.joinToString(" · ")
}

/** Everything one can do with a file: from a long press in the list, or ⋯ in the player. */
@Composable
fun ItemMenu(item: Item, activity: MainActivity, nav: Nav, onDismiss: () -> Unit, onTranscribe: (Item) -> Unit, inPlayer: Boolean = false) {
    val t = TranscribeService.Live
    val busy = t.id == item.id || item.id in t.waiting
    val hasTranscript = item.transcriptUri.isNotBlank()
    TextMenu(item.title, buildList {
        if (!inPlayer) add(MenuItem(stringResource(R.string.play)) { activity.play(item); nav.push(Screen.Player) })
        if (hasTranscript) add(MenuItem(stringResource(R.string.open_transcript), secondary = stringResource(R.string.transcript_saved)) { activity.openTranscript(item) })
        if (busy) add(MenuItem(stringResource(R.string.stop_transcription)) { activity.cancelTranscription() })
        else add(MenuItem(stringResource(if (hasTranscript) R.string.transcribe_again else R.string.transcribe)) { onTranscribe(item) })
        add(MenuItem(stringResource(R.string.share_audio)) { activity.shareAudio(item) })
        if (hasTranscript) add(MenuItem(stringResource(R.string.share_transcript)) { activity.shareTranscript(item) })
        add(MenuItem(stringResource(R.string.save_copy)) { activity.saveCopy(item) })
        if (inPlayer) add(MenuItem(stringResource(R.string.stop)) { activity.stopPlayback(); nav.pop() })
        else add(MenuItem(stringResource(R.string.remove)) { activity.remove(item) })
    }, onDismiss = onDismiss)
}

// ---------------------------------------------------------------------------------------------
// The list: last played first; the file playing sits inverted at the bottom, above "+ open"
// ---------------------------------------------------------------------------------------------

@Composable
fun ListScreen(nav: Nav, app: App, activity: MainActivity) {
    val colors = LocalColors.current
    val all by app.library.items.collectAsState()
    val ui = activity.ui
    var menu by remember { mutableStateOf(false) }
    var rowMenu by remember { mutableStateOf<String?>(null) }
    var sheetFor by remember { mutableStateOf<String?>(null) }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.app_title), onBack = null, trailing = "⋯", onTrailing = { menu = true })
            LazyColumn(Modifier.weight(1f)) {
                if (all.isEmpty()) item { Small(stringResource(R.string.empty), Modifier.padding(horizontal = rowPadH, vertical = 16.dp), maxLines = 5) }
                items(all, key = { it.id }) { item ->
                    val status = itemStatus(item, activity)
                    Box(Modifier.fillMaxWidth().pressable(
                        onClick = { if (ui.mediaId != item.id) activity.play(item); nav.push(Screen.Player) },
                        onLongPress = { rowMenu = item.id }
                    )) {
                        TextRow(item.title, secondary = status.ifBlank { null })
                    }
                }
            }
            Rule()
            val current = all.firstOrNull { it.id == ui.mediaId }
            if (current != null) {
                TextRow((if (ui.playing) "❚❚  " else "▶  ") + current.title, inverted = true, secondary = itemStatus(current, activity).ifBlank { null }) { nav.push(Screen.Player) }
            }
            TextRow(stringResource(R.string.open_files)) { activity.openFiles() }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (menu) TextMenu(null, listOf(
            MenuItem(stringResource(R.string.open_files)) { activity.openFiles() }
        ), onDismiss = { menu = false }, footer = listOf(
            MenuItem(if (colors.isDark) stringResource(R.string.theme_light) else stringResource(R.string.theme_dark)) { app.prefs.toggleTheme(colors.isDark) },
            MenuItem(stringResource(R.string.settings)) { nav.push(Screen.Settings) }
        ))
        rowMenu?.let { id ->
            val item = all.firstOrNull { it.id == id }
            if (item == null) rowMenu = null else ItemMenu(item, activity, nav, onDismiss = { rowMenu = null }, onTranscribe = { sheetFor = it.id })
        }
        sheetFor?.let { id ->
            val item = all.firstOrNull { it.id == id }
            if (item == null) sheetFor = null else TranscribeSheet(item, activity, onDismiss = { sheetFor = null })
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The player: the time, large; a rule to tap; −5 · play · +10; speed; the transcript
// ---------------------------------------------------------------------------------------------

@Composable
fun PlayerScreen(nav: Nav, app: App, activity: MainActivity) {
    val typo = LocalTypo.current
    val tick = rememberTick()
    val all by app.library.items.collectAsState()
    val settings by app.prefs.settings.collectAsState()
    val ui = activity.ui
    val item = all.firstOrNull { it.id == ui.mediaId } ?: all.filter { it.lastPlayed > 0 }.maxByOrNull { it.lastPlayed }
    var menu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    BackHandler { nav.pop() }
    if (item == null) { LaunchedEffect(Unit) { nav.pop() }; return }
    val current = ui.mediaId == item.id
    val playing = current && ui.playing
    val pos = if (current) ui.positionMs else item.positionMs
    val dur = if (current && ui.durationMs > 0) ui.durationMs else item.durationMs
    val t = TranscribeService.Live
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(item.title, onBack = { nav.pop() }, trailing = "⋯", onTrailing = { menu = true })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                VSpace(28.dp)
                T(clock(pos), Modifier.padding(horizontal = rowPadH), size = typo.big, align = TextAlign.Start, maxLines = 1)
                Small(if (dur > 0) clock(dur) else "", Modifier.padding(horizontal = rowPadH), maxLines = 1)
                var width by remember { mutableIntStateOf(1) }
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = rowPadH).height(44.dp)
                        .onSizeChanged { width = it.width }
                        .pointerInput(dur, current, item.id) {
                            detectTapGestures { o: Offset ->
                                if (dur > 0) {
                                    val p = (o.x / width * dur).toLong().coerceIn(0, dur)
                                    if (current) activity.seekTo(p) else activity.play(item.copy(positionMs = p))
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) { Progress(if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f) }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Control(stringResource(R.string.back5), Modifier.weight(1f)) { tick(); if (current) activity.seekBy(-5_000) }
                    Control(if (playing) "❚❚" else "▶", Modifier.weight(1f), inverted = playing) { tick(); if (current) activity.toggle() else activity.play(item) }
                    Control(stringResource(R.string.fwd10), Modifier.weight(1f)) { tick(); if (current) activity.seekBy(10_000) }
                }
                TextRow(speedLabel(settings.speed), secondary = stringResource(R.string.speed), size = typo.title) {
                    val i = Prefs.SPEEDS.indexOf(settings.speed).let { if (it < 0) 1 else it }
                    activity.setSpeed(Prefs.SPEEDS[(i + 1) % Prefs.SPEEDS.size])
                }
                Rule(Modifier.padding(vertical = 8.dp))
                when {
                    t.id == item.id -> TextRow(TranscribeService.phaseLabel(LocalContext.current, t.phase, t.percent), secondary = stringResource(R.string.stop_transcription), size = typo.title) { activity.cancelTranscription() }
                    item.id in t.waiting -> TextRow(stringResource(R.string.phase_waiting), secondary = stringResource(R.string.stop_transcription), size = typo.title) { activity.cancelTranscription() }
                    item.transcriptUri.isNotBlank() -> {
                        TextRow(stringResource(R.string.open_transcript), secondary = stringResource(R.string.transcript_saved), size = typo.title) { activity.openTranscript(item) }
                        TextRow(stringResource(R.string.share_transcript), size = typo.title) { activity.shareTranscript(item) }
                    }
                    else -> TextRow(stringResource(R.string.transcribe), secondary = stringResource(R.string.transcribe_hint), size = typo.title) { sheet = true }
                }
                if (t.errorId == item.id && t.error.isNotBlank() && t.id != item.id) {
                    Small(t.error, Modifier.padding(horizontal = rowPadH, vertical = 6.dp), maxLines = 3)
                }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        if (menu) ItemMenu(item, activity, nav, onDismiss = { menu = false }, onTranscribe = { sheet = true }, inPlayer = true)
        if (sheet) TranscribeSheet(item, activity, onDismiss = { sheet = false })
    }
}

@Composable
private fun Control(label: String, modifier: Modifier, inverted: Boolean = false, onClick: () -> Unit) {
    val colors = LocalColors.current
    val typo = LocalTypo.current
    Box(
        modifier.height(76.dp).background(if (inverted) colors.fg else Color.Transparent).noRippleClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        T(label, size = typo.title, color = if (inverted) colors.bg else colors.fg, align = TextAlign.Center, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// Settings: the transcription, the look
// ---------------------------------------------------------------------------------------------

@Composable
fun SettingsScreen(nav: Nav, app: App) {
    val context = LocalContext.current
    val s by app.prefs.settings.collectAsState()
    val colors = LocalColors.current
    val downloading by Models.downloading.collectAsState()
    BackHandler { nav.pop() }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.settings), onBack = { nav.pop() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Small(stringResource(R.string.transcripts_hint), Modifier.padding(horizontal = rowPadH).padding(top = 16.dp, bottom = 4.dp), maxLines = 5)
                Rule(Modifier.padding(vertical = 8.dp))
                TextRow(if (colors.isDark) stringResource(R.string.theme_dark) else stringResource(R.string.theme_light), secondary = stringResource(R.string.colours)) { app.prefs.toggleTheme(colors.isDark) }
                TextRow(when (s.textSize) { TextSize.SMALL -> "S"; TextSize.MEDIUM -> "M"; TextSize.LARGE -> "L" }, secondary = stringResource(R.string.text_size)) {
                    app.prefs.setTextSize(when (s.textSize) { TextSize.SMALL -> TextSize.MEDIUM; TextSize.MEDIUM -> TextSize.LARGE; TextSize.LARGE -> TextSize.SMALL })
                }
                TextRow(when (s.font) { FontChoice.SANS -> "sans-serif"; FontChoice.SERIF -> "serif"; FontChoice.MONO -> "mono" }, secondary = stringResource(R.string.font)) {
                    app.prefs.setFont(when (s.font) { FontChoice.SANS -> FontChoice.SERIF; FontChoice.SERIF -> FontChoice.MONO; FontChoice.MONO -> FontChoice.SANS })
                }
                TextRow(if (s.haptics) stringResource(R.string.on) else stringResource(R.string.off), secondary = stringResource(R.string.haptics)) { app.prefs.setHaptics(!s.haptics) }
                Rule(Modifier.padding(vertical = 8.dp))
                TextRow(stringResource(R.string.app_name), secondary = stringResource(R.string.about)) { }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}


/**
 * Asked before every transcription: the language spoken, the phone's by default, and the
 * quality, normal by default. High quality is Whisper large-v3-turbo: better punctuation and
 * accuracy, much slower. A bottom sheet in the Reader's style.
 */
@Composable
fun TranscribeSheet(item: Item, activity: MainActivity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalColors.current
    val typo = LocalTypo.current
    var language by remember { mutableStateOf(Prefs.deviceLanguage()) }
    var quality by remember { mutableStateOf(Models.DEFAULT) }
    // The main points: remembered from last time, and only offered to a phone that can hold the
    // two gigabytes the model needs. The first tap fetches it; the choice applies to this file.
    val roomy = remember { SummaryModel.phoneCanHoldIt(context) }
    val modelDownloading by SummaryModel.downloading.collectAsState()
    val summaryHere = remember(modelDownloading) { SummaryModel.isDownloaded(context) }
    var points by remember { mutableStateOf(activity.app.prefs.settings.value.summaryOnPhone && summaryHere) }
    var picking by remember { mutableStateOf(false) }
    val downloading by Models.downloading.collectAsState()
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().background(colors.bg.copy(alpha = 0.6f)).noRippleClickable(onClick = onDismiss)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(colors.bg).noRippleClickable { }
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Rule(color = colors.fg)
            Small(item.title, Modifier.padding(horizontal = rowPadH).padding(top = 14.dp, bottom = 2.dp), maxLines = 1)
            TextRow(languageLabel(language), secondary = stringResource(R.string.language), size = typo.title) { picking = true }
            Rule(Modifier.padding(vertical = 4.dp))
            Models.ALL.forEach { m ->
                val state = when {
                    Models.isDownloaded(context, m) -> ""
                    downloading >= 0 -> " · $downloading%"
                    else -> " · " + stringResource(R.string.model_not_yet)
                }
                TextRow(
                    stringResource(if (m == Models.HIGH) R.string.quality_high else R.string.quality_normal),
                    inverted = quality == m.key, secondary = "${m.mb} MB$state", size = typo.title
                ) { quality = m.key }
            }
            if (roomy) {
                Rule(Modifier.padding(vertical = 4.dp))
                val pointsState = when {
                    modelDownloading >= 0 -> " · $modelDownloading%"
                    !summaryHere -> " · " + stringResource(R.string.model_not_yet)
                    else -> ""
                }
                TextRow(
                    stringResource(R.string.summary_on_phone), inverted = points && summaryHere,
                    secondary = "${SummaryModel.MB} MB$pointsState", size = typo.title,
                ) {
                    if (summaryHere) { points = !points; activity.app.prefs.setSummaryOnPhone(points) }
                    else activity.app.fetchSummaryModel()
                }
            }
            Rule(color = colors.fg)
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) { TextRow(stringResource(R.string.action_cancel), onClick = onDismiss) }
                Box(Modifier.weight(1f)) { TextRow(stringResource(R.string.transcribe), inverted = true) { activity.transcribe(item, language, quality, points && summaryHere); onDismiss() } }
            }
        }
    }
    if (picking) TextMenu(stringResource(R.string.language), Prompts.choices(Prefs.deviceLanguage()).map { code ->
        MenuItem(languageLabel(code), secondary = if (code == language) "✓" else null) { language = code }
    }, onDismiss = { picking = false })
}
