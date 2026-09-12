package com.freedomfighter.readersaudio

import android.app.Application
import com.freedomfighter.readersaudio.data.Library
import com.freedomfighter.readersaudio.data.Prefs
import com.freedomfighter.readers.speech.summary.SummaryModel
import com.freedomfighter.readersaudio.widget.LastWidgets
import kotlinx.coroutines.flow.MutableStateFlow

class App : Application() {
    // lazy: the widget and the services can run before Application.onCreate is done
    val prefs: Prefs by lazy { Prefs(this) }
    val library: Library by lazy { Library(this).also { it.onChange = { LastWidgets.refresh(this) } } }
    /** Empty, or why the model that writes the main points could not be fetched. */
    val modelError = MutableStateFlow("")

    override fun onCreate() {
        super.onCreate(); prefs; library
        Thread { runCatching { com.freedomfighter.readers.speech.whisper.Models.cleanup(this) } }.start()
    }

    /**
     * Fetch the model that writes the main points. In the transcription service, not here: it
     * takes minutes, and a coroutine of the application does not survive the user leaving the
     * page — see TranscribeService.fetchModel.
     */
    fun fetchSummaryModel() {
        if (SummaryModel.downloading.value >= 0) return
        TranscribeService.fetchModel(this)
    }
}
