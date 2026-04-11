package app.pwhs.blockads.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.data.repository.CustomFilterManager
import app.pwhs.blockads.data.repository.FilterListRepository
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

class FilterUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val filterListRepository: FilterListRepository by inject()
    private val appPreferences: AppPreferences by inject()
    private val filterListDao: FilterListDao by inject()
    private val customFilterManager: CustomFilterManager by inject()

    companion object {
        const val WORK_NAME = "filter_update_work"
        private const val NOTIFICATION_CHANNEL_ID = "filter_update_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override suspend fun doWork(): Result {
        return try {
            val wifiOnly = appPreferences.autoUpdateWifiOnly.first()
            if (wifiOnly) {
                val cm =
                    applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val network = cm.activeNetwork
                val capabilities = cm.getNetworkCapabilities(network)
                val isUnmeteredOrWifi = capabilities != null && (
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) ||
                                capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                        )

                if (!isUnmeteredOrWifi) {
                    Timber.d("Filter update skipped: not on unmetered/Wi-Fi (VPN metered constraint workaround). Retrying later.")
                    return Result.retry()
                }
            }

            // Check notification preference
            val notificationType = appPreferences.autoUpdateNotification.first()

            var totalCount = 0
            var isAnySuccess = false
            var failureMessage: String? = null

            // 1. Update remote built-in filters
            val result = filterListRepository.forceUpdateAllEnabledFilters()
            if (result.isSuccess) {
                totalCount += result.getOrDefault(0)
                isAnySuccess = true
            } else {
                failureMessage = result.exceptionOrNull()?.message
            }

            // 2. Update all enabled custom filters
            val customFilters = filterListDao.getAllNonBuiltIn()
            for (filter in customFilters) {
                if (!filter.isEnabled) continue

                val isLocal = filter.trieUrl.startsWith("local://") &&
                        filter.bloomUrl.startsWith("local://")

                if (isLocal) {
                    // Enqueue separate worker for local filters
                    customFilterManager.enqueueRecompileLocally(filter)
                    isAnySuccess = true
                } else {
                    val customResult = customFilterManager.updateCustomFilter(filter)
                    if (customResult.isSuccess) {
                        totalCount += customResult.getOrNull()?.ruleCount ?: 0
                        isAnySuccess = true
                    } else {
                        if (failureMessage == null) {
                            failureMessage = customResult.exceptionOrNull()?.message
                        }
                    }
                }
            }

            // Always reload engine in case any filters updated their binaries
            filterListRepository.loadAllEnabledFilters()

            if (isAnySuccess) {
                // Show notification based on preference
                when (notificationType) {
                    AppPreferences.NOTIFICATION_NORMAL -> {
                        showUpdateNotification(
                            title = applicationContext.getString(R.string.filter_update_success),
                            message = applicationContext.getString(
                                R.string.filter_update_success_message,
                                totalCount
                            ),
                            isSuccess = true
                        )
                    }

                    AppPreferences.NOTIFICATION_SILENT -> {
                        showUpdateNotification(
                            title = applicationContext.getString(R.string.filter_update_success),
                            message = applicationContext.getString(
                                R.string.filter_update_success_message,
                                totalCount
                            ),
                            isSuccess = true,
                            silent = true
                        )
                    }

                    AppPreferences.NOTIFICATION_NONE -> {
                        // No notification
                    }
                }
                Result.success()
            } else {
                // Only show error notifications if not set to NONE
                if (notificationType != AppPreferences.NOTIFICATION_NONE) {
                    showUpdateNotification(
                        title = applicationContext.getString(R.string.filter_update_failed),
                        message = failureMessage
                            ?: applicationContext.getString(R.string.filter_update_failed_message),
                        isSuccess = false
                    )
                }
                Result.retry()
            }
        } catch (e: Exception) {
            Timber.e(e, "Filter update failed")
            Result.failure()
        }
    }

    private fun showUpdateNotification(
        title: String,
        message: String,
        isSuccess: Boolean,
        silent: Boolean = false
    ) {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel for Android O and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                applicationContext.getString(R.string.filter_update_notification_channel),
                if (silent) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description =
                    applicationContext.getString(R.string.filter_update_notification_channel_desc)
                if (silent) {
                    setSound(null, null)
                    enableVibration(false)
                }
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(if (isSuccess) R.drawable.ic_check else R.drawable.ic_error)
            .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply {
                if (silent) {
                    setSilent(true)
                }
            }
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
