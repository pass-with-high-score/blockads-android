package app.pwhs.blockads.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.pwhs.blockads.R
import app.pwhs.blockads.data.dao.FilterListDao
import app.pwhs.blockads.data.entities.FilterList
import app.pwhs.blockads.data.repository.FilterListRepository
import app.pwhs.blockads.service.ServiceController
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.File

/**
 * WorkManager worker for local .trie/.bloom compilation.
 * Runs in the background so large filter lists don't block the UI or get killed.
 *
 * Input: KEY_FILTER_URL, KEY_FILTER_NAME, KEY_FILTER_ID (optional, for recompile)
 * Output: KEY_RESULT_RULE_COUNT, KEY_RESULT_FILTER_ID
 */
class FilterCompileWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val filterListDao: FilterListDao by inject()
    private val filterRepo: FilterListRepository by inject()
    private val client: HttpClient by inject()

    companion object {
        const val WORK_NAME_PREFIX = "filter_compile_"
        const val KEY_FILTER_URL = "filter_url"
        const val KEY_FILTER_NAME = "filter_name"
        const val KEY_FILTER_ID = "filter_id"

        const val KEY_RESULT_RULE_COUNT = "result_rule_count"
        const val KEY_RESULT_FILTER_ID = "result_filter_id"

        private const val CHANNEL_ID = "filter_compile_channel"
        private const val NOTIFICATION_ID = 1002
        private const val REMOTE_FILTERS_DIR = "remote_filters"

        fun buildInputData(url: String, name: String, existingFilterId: Long = -1L): Data {
            return workDataOf(
                KEY_FILTER_URL to url,
                KEY_FILTER_NAME to name,
                KEY_FILTER_ID to existingFilterId
            )
        }
    }

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_FILTER_URL) ?: return Result.failure()
        val name = inputData.getString(KEY_FILTER_NAME) ?: "Custom Filter"
        val existingId = inputData.getLong(KEY_FILTER_ID, -1L)

        val remoteFilterDir = File(applicationContext.filesDir, REMOTE_FILTERS_DIR).apply { mkdirs() }
        val tempFile = File(applicationContext.cacheDir, "worker_compile_${System.currentTimeMillis()}.txt")
        val tempTrie = File(applicationContext.cacheDir, "worker_compile_${System.currentTimeMillis()}.trie")
        val tempBloom = File(applicationContext.cacheDir, "worker_compile_${System.currentTimeMillis()}.bloom")

        try {
            showProgressNotification(name)

            // Download
            Timber.d("FilterCompileWorker: downloading $url")
            val response = client.get(url)
            val channel = response.bodyAsChannel()
            tempFile.outputStream().use { output ->
                val buffer = ByteArray(8 * 1024)
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer)
                    if (read > 0) output.write(buffer, 0, read)
                }
            }

            // Compile
            Timber.d("FilterCompileWorker: compiling...")
            val ruleCount = tunnel.Tunnel.compileFilterList(
                tempFile.absolutePath, tempTrie.absolutePath, tempBloom.absolutePath
            ).toInt()

            if (ruleCount == 0) {
                showResultNotification(name, false, "No valid domains found")
                return Result.failure()
            }

            // DB insert or update
            val filterId = if (existingId > 0) {
                // Recompile existing filter
                val existing = filterListDao.getById(existingId)
                if (existing != null) {
                    val updated = existing.copy(
                        ruleCount = ruleCount,
                        domainCount = ruleCount,
                        bloomUrl = "local://$existingId.bloom",
                        trieUrl = "local://$existingId.trie",
                        lastUpdated = System.currentTimeMillis()
                    )
                    filterListDao.update(updated)
                }
                existingId
            } else {
                // New filter
                val entity = FilterList(
                    name = name,
                    url = url,
                    description = "Custom filter: $name",
                    isEnabled = true,
                    isBuiltIn = false,
                    category = FilterList.CATEGORY_AD,
                    ruleCount = ruleCount,
                    domainCount = ruleCount,
                    bloomUrl = "",
                    trieUrl = "",
                    cssUrl = "",
                    originalUrl = url,
                    lastUpdated = System.currentTimeMillis()
                )
                val id = filterListDao.insert(entity)
                filterListDao.update(entity.copy(
                    id = id,
                    bloomUrl = "local://$id.bloom",
                    trieUrl = "local://$id.trie"
                ))
                id
            }

            // Move compiled files to final location
            File(remoteFilterDir, "$filterId.trie").let { tempTrie.copyTo(it, overwrite = true) }
            File(remoteFilterDir, "$filterId.bloom").let { tempBloom.copyTo(it, overwrite = true) }

            // Reload engine
            filterRepo.loadAllEnabledFilters()
            ServiceController.requestRestart(applicationContext)

            showResultNotification(name, true, "$ruleCount rules compiled")
            Timber.d("FilterCompileWorker: done, $ruleCount rules")

            return Result.success(workDataOf(
                KEY_RESULT_RULE_COUNT to ruleCount,
                KEY_RESULT_FILTER_ID to filterId
            ))
        } catch (e: Exception) {
            Timber.e(e, "FilterCompileWorker failed")
            showResultNotification(name, false, e.message ?: "Compilation failed")
            return Result.failure()
        } finally {
            tempFile.delete()
            tempTrie.delete()
            tempBloom.delete()
        }
    }

    private fun showProgressNotification(filterName: String) {
        createChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.filter_compile_progress_title))
            .setContentText(filterName)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun showResultNotification(filterName: String, success: Boolean, message: String) {
        createChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(
                if (success) applicationContext.getString(R.string.filter_compile_success_title)
                else applicationContext.getString(R.string.filter_compile_failed_title)
            )
            .setContentText("$filterName: $message")
            .setSmallIcon(if (success) R.drawable.ic_check else R.drawable.ic_error)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.filter_compile_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = applicationContext.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
