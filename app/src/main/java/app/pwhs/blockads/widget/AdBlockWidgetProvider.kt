package app.pwhs.blockads.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import app.pwhs.blockads.MainActivity
import app.pwhs.blockads.R
import app.pwhs.blockads.data.DnsLogDao
import app.pwhs.blockads.service.AdBlockVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.getKoin

class AdBlockWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "AdBlockWidget"
        const val ACTION_TOGGLE_VPN = "app.pwhs.blockads.widget.TOGGLE_VPN"
        const val ACTION_WIDGET_UPDATE = "app.pwhs.blockads.widget.UPDATE"

        private const val EXPANDED_MIN_WIDTH_DP = 200
        private const val EXPANDED_MIN_HEIGHT_DP = 120

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

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_TOGGLE_VPN -> {
                toggleVpn(context)
                // Update widgets after a short delay to reflect new state
                val widgetManager = AppWidgetManager.getInstance(context)
                val ids = widgetManager.getAppWidgetIds(
                    ComponentName(context, AdBlockWidgetProvider::class.java)
                )
                for (id in ids) {
                    updateWidget(context, widgetManager, id)
                }
            }
            ACTION_WIDGET_UPDATE -> {
                val widgetManager = AppWidgetManager.getInstance(context)
                val ids = widgetManager.getAppWidgetIds(
                    ComponentName(context, AdBlockWidgetProvider::class.java)
                )
                for (id in ids) {
                    updateWidget(context, widgetManager, id)
                }
            }
        }
    }

    private fun toggleVpn(context: Context) {
        if (AdBlockVpnService.isRunning) {
            val stopIntent = Intent(context, AdBlockVpnService::class.java).apply {
                action = AdBlockVpnService.ACTION_STOP
            }
            context.startService(stopIntent)
        } else {
            val startIntent = Intent(context, AdBlockVpnService::class.java).apply {
                action = AdBlockVpnService.ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(startIntent)
                } else {
                    context.startService(startIntent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start VPN from widget, opening app", e)
                val appIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(appIntent)
            }
        }
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)

        val isExpanded = minWidth >= EXPANDED_MIN_WIDTH_DP && minHeight >= EXPANDED_MIN_HEIGHT_DP

        if (isExpanded) {
            updateExpandedWidget(context, appWidgetManager, appWidgetId)
        } else {
            updateCollapsedWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun updateCollapsedWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_collapsed)
        val isRunning = AdBlockVpnService.isRunning

        // Set toggle button appearance
        views.setImageViewResource(
            R.id.widget_toggle_btn,
            R.drawable.ic_power
        )
        views.setInt(
            R.id.widget_toggle_btn,
            "setBackgroundResource",
            if (isRunning) R.drawable.widget_toggle_on else R.drawable.widget_toggle_off
        )

        // Set shield icon
        views.setImageViewResource(
            R.id.widget_shield_icon,
            if (isRunning) R.drawable.ic_shield_on else R.drawable.ic_shield_off
        )

        // Set status text
        views.setTextViewText(
            R.id.widget_status_text,
            context.getString(
                if (isRunning) R.string.status_protected else R.string.status_unprotected
            )
        )
        views.setTextColor(
            R.id.widget_status_text,
            if (isRunning) 0xFF00E676.toInt() else 0xFF757575.toInt()
        )

        // Set toggle click action
        val toggleIntent = Intent(context, AdBlockWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE_VPN
        }
        val togglePendingIntent = PendingIntent.getBroadcast(
            context, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_toggle_btn, togglePendingIntent)

        // Set app open click on the rest of the widget
        val appIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val appPendingIntent = PendingIntent.getActivity(
            context, 1, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_shield_icon, appPendingIntent)
        views.setOnClickPendingIntent(R.id.widget_app_name, appPendingIntent)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun updateExpandedWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_expanded)
        val isRunning = AdBlockVpnService.isRunning

        // Set toggle button appearance
        views.setImageViewResource(
            R.id.widget_toggle_btn,
            R.drawable.ic_power
        )
        views.setInt(
            R.id.widget_toggle_btn,
            "setBackgroundResource",
            if (isRunning) R.drawable.widget_toggle_on else R.drawable.widget_toggle_off
        )

        // Set shield icon
        views.setImageViewResource(
            R.id.widget_shield_icon,
            if (isRunning) R.drawable.ic_shield_on else R.drawable.ic_shield_off
        )

        // Set status text
        views.setTextViewText(
            R.id.widget_status_text,
            context.getString(
                if (isRunning) R.string.status_protected else R.string.status_unprotected
            )
        )
        views.setTextColor(
            R.id.widget_status_text,
            if (isRunning) 0xFF00E676.toInt() else 0xFF757575.toInt()
        )

        // Set toggle click action
        val toggleIntent = Intent(context, AdBlockWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE_VPN
        }
        val togglePendingIntent = PendingIntent.getBroadcast(
            context, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_toggle_btn, togglePendingIntent)

        // Set app open click
        val appIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val appPendingIntent = PendingIntent.getActivity(
            context, 1, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_shield_icon, appPendingIntent)
        views.setOnClickPendingIntent(R.id.widget_app_name, appPendingIntent)

        // Load stats asynchronously
        val result = goAsync()
        coroutineScope.launch {
            try {
                val dnsLogDao: DnsLogDao = getKoin().get()
                val blocked = dnsLogDao.getBlockedCountOnce()
                val total = dnsLogDao.getTotalCountOnce()
                val blockRate = if (total > 0) {
                    String.format("%.1f%%", blocked.toFloat() / total * 100)
                } else {
                    "0%"
                }

                views.setTextViewText(R.id.widget_blocked_count, formatCount(blocked))
                views.setTextViewText(R.id.widget_total_count, formatCount(total))
                views.setTextViewText(R.id.widget_block_rate, blockRate)

                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading widget stats", e)
                // Still update the widget with default stats
                appWidgetManager.updateAppWidget(appWidgetId, views)
            } finally {
                result.finish()
            }
        }
    }

    private fun formatCount(count: Int): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000f)
            count >= 1_000 -> String.format("%.1fK", count / 1_000f)
            else -> count.toString()
        }
    }
}
