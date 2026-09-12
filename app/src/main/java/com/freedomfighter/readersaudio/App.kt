package com.freedomfighter.readersaudio

import android.app.Application
import com.freedomfighter.readersaudio.data.Library
import com.freedomfighter.readersaudio.data.Prefs
import com.freedomfighter.readersaudio.summary.SummaryModel
import com.freedomfighter.readersaudio.widget.LastWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class App : Application() {
    // lazy: the widget and the services can run before Application.onCreate is done
    val prefs: Prefs by lazy { Prefs(this) }
    val library: Library by lazy { Library(this).also { it.onChange = { LastWidgets.refresh(this) } } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Empty, or why the model that writes the main points could not be fetched. */
    val modelError = MutableStateFlow("")

    override fun onCreate() {
        super.onCreate(); prefs; library
        Thread { runCatching { com.freedomfighter.readersaudio.whisper.Models.cleanup(this) } }.start()
    }

    /**
     * Fetch the model that writes the main points, then remember that it is wanted. Here rather
     * than in the sheet: two gigabytes take minutes, and the sheet is closed long before.
     */
    fun fetchSummaryModel() {
        if (SummaryModel.downloading.value >= 0) return
        scope.launch {
            modelError.value = try {
                SummaryModel.download(this@App); prefs.setSummaryOnPhone(true); ""
            } catch (e: Exception) { e.message ?: "model download failed" }
        }
    }
}
