package com.freedomfighter.readersaudio.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.RemoteViews
import com.freedomfighter.readersaudio.App
import com.freedomfighter.readersaudio.MainActivity
import com.freedomfighter.readersaudio.NowPlaying
import com.freedomfighter.readersaudio.R
import com.freedomfighter.readersaudio.data.clock

/**
 * The last file played, for any launcher: its title and where it was left; ▶ resumes it,
 * ❚❚ pauses. The button is a standard media-button press on Media3's receiver, which starts
 * the player if needed and resumes the last file (playback resumption); the title opens the player.
 */
object LastWidgets {
    fun render(ctx: Context, mgr: AppWidgetManager, widgetId: Int) {
        val v = RemoteViews(ctx.packageName, R.layout.widget_last)
        val (bg, fg, dim) = WidgetUi.colors(ctx)
        v.setInt(R.id.widget_root, "setBackgroundColor", bg)
        listOf(R.id.widget_title, R.id.widget_play, R.id.widget_timer).forEach { v.setTextColor(it, fg) }
        v.setTextColor(R.id.widget_sub, dim)
        val open = Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER)
        v.setOnClickPendingIntent(R.id.widget_body, WidgetUi.activity(ctx, open, 1))
        val item = (ctx.applicationContext as App).library.last()
        if (item == null) {
            v.setTextViewText(R.id.widget_title, ctx.getString(R.string.widget_empty))
            v.setTextViewText(R.id.widget_sub, ctx.getString(R.string.app_name))
            v.setViewVisibility(R.id.widget_play, View.GONE)
            v.setViewVisibility(R.id.widget_timer, View.GONE)
            mgr.updateAppWidget(widgetId, v)
            return
        }
        val isThis = NowPlaying.id == item.id
        val playing = isThis && NowPlaying.playing
        val pos = if (isThis) NowPlaying.positionMs + (if (playing) System.currentTimeMillis() - NowPlaying.at else 0L) else item.positionMs
        val dur = if (isThis && NowPlaying.durationMs > 0) NowPlaying.durationMs else item.durationMs
        v.setTextViewText(R.id.widget_title, item.title)
        v.setViewVisibility(R.id.widget_play, View.VISIBLE)
        v.setTextViewText(R.id.widget_play, if (playing) "❚❚" else "▶")
        if (playing) {
            // A running clock needs no refresh: the length under the title, the position counting on the right.
            v.setViewVisibility(R.id.widget_timer, View.VISIBLE)
            v.setChronometer(R.id.widget_timer, SystemClock.elapsedRealtime() - pos, null, true)
            v.setTextViewText(R.id.widget_sub, if (dur > 0) clock(dur) else "")
        } else {
            v.setViewVisibility(R.id.widget_timer, View.GONE)
            v.setTextViewText(R.id.widget_sub, when { dur > 0 && pos > 0 -> clock(pos) + " / " + clock(dur); dur > 0 -> clock(dur); else -> "" })
        }
        val press = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setComponent(ComponentName(ctx, "androidx.media3.session.MediaButtonReceiver"))
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        v.setOnClickPendingIntent(R.id.widget_play, PendingIntent.getBroadcast(ctx, 2, press, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        mgr.updateAppWidget(widgetId, v)
    }

    fun refresh(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        mgr.getAppWidgetIds(ComponentName(ctx, LastWidget::class.java)).forEach { render(ctx, mgr, it) }
    }
}

class LastWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { LastWidgets.render(context, mgr, it) } }
}
