package com.freedomfighter.readersaudio

import android.content.Context
import androidx.annotation.StringRes
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** The whisper model vanished between its download and its opening. */
class ModelMissing : IllegalStateException("model not here")

/** The system would not create the exported .txt under this name. */
class CannotCreate(val name: String) : IllegalStateException("cannot create $name")

/**
 * A failure put into the user's language. What this app and the speech module throw is known and
 * gets a sentence of its own; the speech module's messages are English and are recognised here,
 * on the app side, rather than changed there. Anything else is the system's own text, which cannot
 * be translated: it follows [lead], a phrase that says what failed.
 */
object Errors {
    fun describe(ctx: Context, e: Throwable, @StringRes lead: Int): String {
        val m = e.message.orEmpty()
        return when {
            e is ModelMissing -> ctx.getString(R.string.error_model_missing)
            e is CannotCreate -> ctx.getString(R.string.error_cannot_create, e.name)
            e is UnknownHostException || e is ConnectException || e is SocketTimeoutException -> ctx.getString(R.string.error_no_connection)
            // from readers-speech (share/Download.kt, summary/SummaryModel.kt, translate/TranslateModel.kt, audio/Pcm.kt)
            DOWNLOAD_HTTP.find(m) != null -> ctx.getString(R.string.error_download_http, DOWNLOAD_HTTP.find(m)!!.groupValues[1].toInt())
            m == "download cut short" -> ctx.getString(R.string.error_download_cut)
            NO_SPACE.find(m) != null -> ctx.getString(R.string.error_no_space, NO_SPACE.find(m)!!.groupValues[1].toLong())
            m == "no audio track" -> ctx.getString(R.string.error_no_audio)
            else -> ctx.getString(lead, m.ifBlank { e.javaClass.simpleName })
        }.take(160)
    }

    private val DOWNLOAD_HTTP = Regex("^download: HTTP (\\d+)")
    private val NO_SPACE = Regex("^not enough space: (\\d+) MB needed")
}
