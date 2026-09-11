package com.freedomfighter.readersaudio

import android.app.Application
import com.freedomfighter.readersaudio.data.Library
import com.freedomfighter.readersaudio.data.Prefs
import com.freedomfighter.readersaudio.widget.LastWidgets

class App : Application() {
    // lazy: the widget and the services can run before Application.onCreate is done
    val prefs: Prefs by lazy { Prefs(this) }
    val library: Library by lazy { Library(this).also { it.onChange = { LastWidgets.refresh(this) } } }
    override fun onCreate() {
        super.onCreate(); prefs; library
        Thread { runCatching { com.freedomfighter.readersaudio.whisper.Models.cleanup(this) } }.start()
    }
}
