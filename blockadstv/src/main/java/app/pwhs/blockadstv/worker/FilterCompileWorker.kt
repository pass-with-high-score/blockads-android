package app.pwhs.blockadstv.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.pwhs.blockadstv.R
import app.pwhs.blockadstv.data.dao.FilterListDao
import app.pwhs.blockadstv.data.entities.FilterList
import app.pwhs.blockadstv.data.repository.FilterListRepository
import app.pwhs.blockadstv.service.ServiceController
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
        val ts = System.currentTimeMillis()
        val tempFile = File(applicationContext.cacheDir, "worker_compile_$ts.txt")
        val tempBase = File(applicationContext.cacheDir, "worker_compile_$ts")
        val tempTrie = File("${tempBase.absolutePath}.trie")
        val tempBloom = File("${tempBase.absolutePath}.bloom")
        val tempCss = File("${tempBase.absolutePath}.css")
        val tempScriptlets = File("${tempBase.absolutePath}.scriptlets")
        val tempModules = File("${tempBase.absolutePath}.modules.json")

        try {
            showProgressNotification(name)

            val urls = url.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (urls.isEmpty()) {
                showResultNotification(name, false, "No valid URL provided")
                return Result.failure()
            }
            tempFile.outputStream().use { output ->
                for (u in urls) {
                    Timber.d("FilterCompileWorker: downloading $u")
                    val response = client.get(u)
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(8 * 1024)
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buffer)
                        if (read > 0) output.write(buffer, 0, read)
                    }
                    output.write("\n".toByteArray())
                }
            }

            Timber.d("FilterCompileWorker: compiling...")
            val ruleCount = tunnel.Tunnel.compileFilterList(
                tempFile.absolutePath, tempTrie.absolutePath, tempBloom.absolutePath
            ).toInt()

            if (ruleCount == 0) {
                showResultNotification(name, false, "No valid domains found")
                return Result.failure()
            }

            val hasCss = tempCss.exists() && tempCss.length() > 0

            val filterId = if (existingId > 0) {
                val existing = filterListDao.getById(existingId)
                if (existing != null) {
                    val updated = existing.copy(
                        ruleCount = ruleCount,
                        domainCount = ruleCount,
                        description = "Custom filter: ${existing.name}",
                        bloomUrl = "local://$existingId.bloom",
                        trieUrl = "local://$existingId.trie",
                        cssUrl = if (hasCss) "local://$existingId.css" else existing.cssUrl,
                        lastUpdated = System.currentTimeMillis()
                    )
                    filterListDao.update(updated)
                }
                existingId
            } else {
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
                filterListDao.update(
                    entity.copy(
                        id = id,
                        bloomUrl = "local://$id.bloom",
                        trieUrl = "local://$id.trie",
                        cssUrl = if (hasCss) "local://$id.css" else ""
                    )
                )
                id
            }

            File(remoteFilterDir, "$filterId.trie").let { tempTrie.copyTo(it, overwrite = true) }
            File(remoteFilterDir, "$filterId.bloom").let { tempBloom.copyTo(it, overwrite = true) }
            if (hasCss) {
                tempCss.copyTo(File(remoteFilterDir, "$filterId.css"), overwrite = true)
            }
            if (tempModules.exists() && tempModules.length() > 0) {
                tempModules.copyTo(File(remoteFilterDir, "$filterId.modules.json"), overwrite = true)
            }

            filterRepo.loadAllEnabledFilters()
            ServiceController.requestRestart(applicationContext)

            showResultNotification(name, true, "$ruleCount rules compiled")
            Timber.d("FilterCompileWorker: done, $ruleCount rules")

            return Result.success(
                workDataOf(
                    KEY_RESULT_RULE_COUNT to ruleCount,
                    KEY_RESULT_FILTER_ID to filterId
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "FilterCompileWorker failed")
            showResultNotification(name, false, e.message ?: "Compilation failed")
            if (existingId > 0) {
                val existing = filterListDao.getById(existingId)
                if (existing != null && existing.ruleCount == 0) {
                    filterListDao.delete(existing)
                    Timber.d("Deleted failed placeholder filter id=$existingId")
                }
            }
            return Result.failure()
        } finally {
            tempFile.delete()
            tempTrie.delete()
            tempBloom.delete()
            tempCss.delete()
            tempScriptlets.delete()
            tempModules.delete()
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
