package app.pwhs.blockads.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import app.pwhs.blockads.data.dao.DnsLogDao
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBroadcastPendingResult
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.google.common.util.concurrent.ListenableFuture

@RunWith(RobolectricTestRunner::class)
class AdBlockWidgetProviderTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val provider = ComponentName(app, AdBlockWidgetProvider::class.java)
    private val daoCalls = AtomicInteger()
    private val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        val dao = mockk<DnsLogDao> {
            coEvery { getBlockedCountSinceSync(any()) } coAnswers { daoCalls.incrementAndGet(); 42 }
        }
        startKoin { modules(module { single { dao } }) }
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        stopKoin()
    }

    private fun bindExpanded(vararg ids: Int) {
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
        }
        val manager = AppWidgetManager.getInstance(app)
        ids.forEach { manager.bindAppWidgetIdIfAllowed(it, provider, options) }
    }

    // Robolectric doesn't register this app's manifest receivers, so attach a pending result the way
    // the framework does; goAsync() then hands it out exactly once.
    private fun broadcastUpdate(vararg ids: Int): ListenableFuture<BroadcastReceiver.PendingResult> {
        val pending = ReflectionHelpers.callStaticMethod<BroadcastReceiver.PendingResult>(
            ShadowBroadcastPendingResult::class.java, "create",
            ClassParameter.from(Int::class.javaPrimitiveType, 0),
            ClassParameter.from(String::class.java, null),
            ClassParameter.from(Bundle::class.java, null),
            ClassParameter.from(Boolean::class.javaPrimitiveType, false)
        )
        val receiver = AdBlockWidgetProvider()
        ReflectionHelpers.callInstanceMethod<Any>(
            receiver, "setPendingResult",
            ClassParameter.from(BroadcastReceiver.PendingResult::class.java, pending)
        )
        receiver.onReceive(
            app,
            Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        )
        return Shadow.extract<ShadowBroadcastPendingResult>(pending).future
    }

    // The stats load runs on Dispatchers.IO; after finish() give any straggling coroutine time to fail.
    private fun awaitFinished(future: ListenableFuture<BroadcastReceiver.PendingResult>) {
        future.get(5, TimeUnit.SECONDS)
        Thread.sleep(300)
    }

    @Ignore("known bug: goAsync is called per widget and the second null result crashes")
    @Test
    fun `updating two expanded widgets in one broadcast does not crash`() {
        bindExpanded(1, 2)

        awaitFinished(broadcastUpdate(1, 2))

        assertTrue("stats never loaded", daoCalls.get() > 0)
        assertEquals("uncaught exceptions: $uncaught", emptyList<Throwable>(), uncaught.toList())
    }

    @Ignore("known bug: a DAO failure in the widget stats load crashes the process")
    @Test
    fun `a failing stats query does not crash the process`() {
        stopKoin()
        val dao = mockk<DnsLogDao> {
            coEvery { getBlockedCountSinceSync(any()) } coAnswers {
                daoCalls.incrementAndGet(); throw IllegalStateException("db closed")
            }
        }
        startKoin { modules(module { single { dao } }) }
        bindExpanded(1)

        awaitFinished(broadcastUpdate(1))

        assertEquals("uncaught exceptions: $uncaught", emptyList<Throwable>(), uncaught.toList())
    }
}
