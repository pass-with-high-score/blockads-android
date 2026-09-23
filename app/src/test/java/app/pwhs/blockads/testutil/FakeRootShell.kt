package app.pwhs.blockads.testutil

import app.pwhs.blockads.utils.RootShell

/**
 * Scriptable [RootShell]. [respond] decides each command's result; [rootAfterBuilds] models a su daemon that only
 * grants root once libsu has built that many main shells (the poisoned-shell case after boot).
 */
class FakeRootShell(
    var rootAfterBuilds: Int = 0,
    var respond: (String) -> FakeResult = { FakeResult() },
) : RootShell {

    data class FakeResult(
        override val isSuccess: Boolean = true,
        override val out: List<String> = emptyList(),
        override val err: List<String> = emptyList(),
    ) : RootShell.Result

    inner class FakeHandle(override val isRoot: Boolean) : RootShell.Handle {
        var closed = false
        override fun close() {
            closed = true
            if (closeThrows) throw java.io.IOException("close failed")
            if (cached === this) cached = null
        }
    }

    val batches = mutableListOf<List<String>>()
    val commands: List<String> get() = batches.flatten()
    var cached: FakeHandle? = null
    var builds = 0
    var closeThrows = false
    var grantedRoot: Boolean? = null

    override fun exec(vararg commands: String): RootShell.Result {
        batches += commands.toList()
        return commands.map(respond).lastOrNull() ?: FakeResult()
    }

    override fun cachedShell(): RootShell.Handle? = cached

    override fun mainShell(): RootShell.Handle {
        cached?.let { return it }
        builds++
        return FakeHandle(isRoot = builds > rootAfterBuilds).also { cached = it }
    }

    override fun isAppGrantedRoot(): Boolean? = grantedRoot
}
