package app.pwhs.blockads.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class AdBlockGlanceWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = AdBlockGlanceWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdateWorker.enqueue(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetUpdateWorker.cancel(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        // Ensure periodic updates are scheduled when widgets exist
        val widgetManager = AppWidgetManager.getInstance(context)
        val ids = widgetManager.getAppWidgetIds(
            ComponentName(context, AdBlockGlanceWidgetReceiver::class.java)
        )
        if (ids.isNotEmpty()) {
            WidgetUpdateWorker.enqueue(context)
        }
    }
}
