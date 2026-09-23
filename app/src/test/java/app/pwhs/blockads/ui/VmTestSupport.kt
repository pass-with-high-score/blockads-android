package app.pwhs.blockads.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Routes viewModelScope onto a test dispatcher; unconfined so launches run eagerly. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

/**
 * Joins the jobs [action] launches, and any they launch in turn; pre-existing children
 * (stateIn sharing coroutines, which never complete) are left alone.
 */
fun <T : ViewModel> T.settle(action: T.() -> Unit): T {
    val job = viewModelScope.coroutineContext[Job]!!
    val before = job.children.toSet()
    action()
    runBlocking {
        withTimeout(10_000) {
            while (true) {
                val pending = job.children.filter { it !in before && it.isActive }.toList()
                if (pending.isEmpty()) break
                pending.joinAll()
            }
        }
    }
    return this
}

/** Wall-clock poll for work a ViewModel hands to Dispatchers.IO. */
fun awaitUntil(timeoutMs: Long = 10_000, message: String = "condition", condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        check(System.currentTimeMillis() < deadline) { "Timed out waiting for $message" }
        Thread.sleep(10)
    }
}

/** Subscribes to WhileSubscribed state flows so their value tracks upstream. */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.keepHot(vararg flows: Flow<*>) {
    flows.forEach { flow ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }
}

/** [keepHot] for tests that cannot use runTest, e.g. a VM with an endless ticker it would spin. */
fun CoroutineScope.keepHot(vararg flows: Flow<*>) {
    flows.forEach { flow -> launch { flow.collect {} } }
}

/** Stops long-running init loops (e.g. uptime tickers) once a test is done. */
fun ViewModel.close() = viewModelScope.cancel()
