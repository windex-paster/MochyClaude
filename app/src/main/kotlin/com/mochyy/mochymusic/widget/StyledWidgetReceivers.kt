// StyledWidgetReceivers.kt: the controls pill and the player card, each as a glass and a default widget

package com.mochyy.mochymusic.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.mochyy.mochymusic.playback.MusicService

/** What the new widgets share: they are drawn by [MochyWidgetManager] and ask the player for a redraw when placed or resized. */
abstract class StyledWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        askForRedraw(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        askForRedraw(context)
    }

    private fun askForRedraw(context: Context) {
        if (!MusicService.isRunning) return
        val intent = Intent(context, MusicService::class.java).apply { action = MusicWidgetReceiver.ACTION_UPDATE_WIDGET }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            // the system may refuse a service start from the background; the widget keeps what it showed
        }
    }
}

class PillGlassWidgetReceiver : StyledWidgetReceiver()
class PillDefaultWidgetReceiver : StyledWidgetReceiver()
class CardGlassWidgetReceiver : StyledWidgetReceiver()
class CardDefaultWidgetReceiver : StyledWidgetReceiver()
