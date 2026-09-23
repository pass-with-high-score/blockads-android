package app.pwhs.blockads.worker

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.robolectric.Shadows.shadowOf

/** Robolectric app + test WorkManager + a Koin graph of mocks for KoinComponent workers. */
class WorkerHarness(vararg modules: Module) {

    val app: Application = ApplicationProvider.getApplicationContext()

    init {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build()
        )
        startKoin { modules(modules.toList()) }
    }

    fun close() = stopKoin()

    inline fun <reified W : ListenableWorker> run(
        context: Context = app,
        configure: TestListenableWorkerBuilder<W>.() -> Unit = {},
    ): ListenableWorker.Result = TestListenableWorkerBuilder<W>(context).apply(configure).build().startWork().get()

    fun uniqueWork(name: String): List<WorkInfo> = WorkManager.getInstance(app).getWorkInfosForUniqueWork(name).get()

    val notifications: List<Notification>
        get() = shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications
}
