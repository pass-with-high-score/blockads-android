package app.pwhs.blockads.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import app.pwhs.blockads.MainActivity
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.DnsLogDao
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.RootProxyService
import app.pwhs.blockads.utils.startOfDayMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.getKoin

class AdBlockWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val EXPANDED_MIN_WIDTH_DP = 160
        private const val EXPANDED_MIN_HEIGHT_DP = 90

        fun sendUpdateBroadcast(context: Context) {
            val intent = Intent(context, AdBlockWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            val widgetManager = AppWidgetManager.getInstance(context)
            val ids = widgetManager.getAppWidgetIds(
                ComponentName(context, AdBlockWidgetProvider::class.java)
            )
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            val options = appWidgetManager.getAppWidgetOptions(id)
            updateWidgetInternal(context, appWidgetManager, id, options)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        updateWidgetInternal(context, appWidgetManager, appWidgetId, newOptions)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
    }

    private fun updateWidgetInternal(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        options: Bundle
    ) {
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)

        val isExpanded =
            minWidth >= EXPANDED_MIN_WIDTH_DP &&
                    minHeight >= EXPANDED_MIN_HEIGHT_DP

        val isRunning = AdBlockVpnService.isRunning || RootProxyService.isRunning

        if (isExpanded) {
            updateExpanded(context, appWidgetManager, appWidgetId, isRunning)
        } else {
            updateCollapsed(context, appWidgetManager, appWidgetId, isRunning)
        }
    }

    private fun updateCollapsed(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        isRunning: Boolean
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_collapsed)

        views.setTextViewText(
            R.id.widget_status,
            context.getString(
                if (isRunning) R.string.widget_protected
                else R.string.widget_unprotected
            )
        )

        views.setInt(
            R.id.widget_toggle_btn,
            "setBackgroundResource",
            if (isRunning) R.drawable.widget_toggle_on
            else R.drawable.widget_toggle_off
        )

        views.setTextColor(
            R.id.widget_status,
            if (isRunning) 0xFF4CAF50.toInt()
            else 0xFF757575.toInt()
        )

        bindClicks(context, views)
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun updateExpanded(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        isRunning: Boolean
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_expanded)

        views.setTextViewText(
            R.id.widget_status,
            context.getString(
                if (isRunning) R.string.widget_protected
                else R.string.widget_unprotected
            )
        )

        views.setInt(
            R.id.widget_toggle_btn,
            "setBackgroundResource",
            if (isRunning) R.drawable.widget_toggle_on
            else R.drawable.widget_toggle_off
        )

        views.setInt(
            R.id.widget_status,
            "setBackgroundResource",
            if (isRunning) R.drawable.widget_toggle_on
            else R.drawable.widget_toggle_off
        )

        bindClicks(context, views)
        appWidgetManager.updateAppWidget(appWidgetId, views)

        // Load stats async
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao: DnsLogDao = getKoin().get()
                val blockedToday =
                    dao.getBlockedCountSinceSync(startOfDayMillis())

                views.setTextViewText(
                    R.id.widget_blocked_count,
                    blockedToday.toString()
                )

                appWidgetManager.updateAppWidget(appWidgetId, views)
            } finally {
                result.finish()
            }
        }
    }

    private fun bindClicks(context: Context, views: RemoteViews) {
        val toggleIntent = Intent(context, WidgetToggleReceiver::class.java).apply {
            action = WidgetToggleReceiver.ACTION_TOGGLE_VPN
        }
        val togglePending = PendingIntent.getBroadcast(
            context, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_toggle_btn, togglePending)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val openAppPending = PendingIntent.getActivity(
            context, 1, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openAppPending)
    }
}