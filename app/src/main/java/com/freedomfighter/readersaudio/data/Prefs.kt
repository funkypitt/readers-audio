package com.freedomfighter.readersaudio.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { DARK, LIGHT, SYSTEM }
enum class FontChoice { SERIF, SANS, MONO }
enum class TextSize { SMALL, MEDIUM, LARGE }
enum class Align { LEFT, CENTER }

data class Settings(
    val theme: ThemeMode = ThemeMode.DARK,
    val font: FontChoice = FontChoice.SANS,
    val textSize: TextSize = TextSize.MEDIUM,
    val align: Align = Align.LEFT,
    val haptics: Boolean = true,
    /** Language spoken, for the transcription: the phone's language by default, "" = detect. */
    val language: String = Prefs.deviceLanguage(),
    /** whisper.cpp model: base, small, medium. */
    val model: String = "normal",
    val speed: Float = 1f,
    /** Whether a transcription also gets the main points written on the phone. */
    val summaryOnPhone: Boolean = false
)

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _settings.value = read() }
    init { sp.registerOnSharedPreferenceChangeListener(listener) }

    private fun read() = Settings(
        theme = enumOr(sp.getString("theme", null), ThemeMode.DARK),
        font = enumOr(sp.getString("font", null), FontChoice.SANS),
        textSize = enumOr(sp.getString("text_size", null), TextSize.MEDIUM),
        align = enumOr(sp.getString("align", null), Align.LEFT),
        haptics = sp.getBoolean("haptics", true),
        language = sp.getString("language", deviceLanguage()) ?: deviceLanguage(),
        model = sp.getString("model", "normal") ?: "normal",
        speed = sp.getFloat("speed", 1f),
        summaryOnPhone = sp.getBoolean("summary_on_phone", false)
    )
    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    fun setTheme(m: ThemeMode) = sp.edit().putString("theme", m.name).apply()
    fun setFont(f: FontChoice) = sp.edit().putString("font", f.name).apply()
    fun setTextSize(t: TextSize) = sp.edit().putString("text_size", t.name).apply()
    fun setHaptics(v: Boolean) = sp.edit().putBoolean("haptics", v).apply()
    fun setLanguage(v: String) = sp.edit().putString("language", v.trim()).apply()
    fun setModel(v: String) = sp.edit().putString("model", v).apply()
    fun setSpeed(v: Float) = sp.edit().putFloat("speed", v).apply()
    fun setSummaryOnPhone(v: Boolean) = sp.edit().putBoolean("summary_on_phone", v).apply()
    fun toggleTheme(systemIsDark: Boolean) {
        val dark = when (_settings.value.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> systemIsDark }
        setTheme(if (dark) ThemeMode.LIGHT else ThemeMode.DARK)
    }

    companion object {
        val SPEEDS = listOf(0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        fun deviceLanguage(): String = java.util.Locale.getDefault().language.takeIf { it.isNotBlank() } ?: "en"
        /** What the language row offers: the phone's language, English, detected. */
        fun languages(): List<String> = listOf(deviceLanguage(), "en", "").distinct()
    }
}
