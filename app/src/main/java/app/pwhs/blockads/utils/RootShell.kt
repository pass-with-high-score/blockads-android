package app.pwhs.blockads.utils

import com.topjohnwu.superuser.Shell

/** The slice of libsu the app uses, so root-dependent logic can run against a fake. */
interface RootShell {
    fun exec(vararg commands: String): Result

    /** libsu's cached main shell, or null when none has been built yet. */
    fun cachedShell(): Handle?

    /** libsu's main shell, building a fresh one (and asking for su) if none is cached. */
    fun mainShell(): Handle

    fun isAppGrantedRoot(): Boolean?

    interface Result {
        val isSuccess: Boolean
        val out: List<String>
        val err: List<String>
    }

    interface Handle {
        val isRoot: Boolean
        fun close()
    }
}

object LibsuRootShell : RootShell {
    override fun exec(vararg commands: String): RootShell.Result = LibsuResult(Shell.cmd(*commands).exec())

    override fun cachedShell(): RootShell.Handle? = Shell.getCachedShell()?.let(::LibsuHandle)

    override fun mainShell(): RootShell.Handle = LibsuHandle(Shell.getShell())

    override fun isAppGrantedRoot(): Boolean? = Shell.isAppGrantedRoot()

    // Delegates lazily so callers touch exactly the libsu fields they read.
    private class LibsuResult(private val result: Shell.Result) : RootShell.Result {
        override val isSuccess: Boolean get() = result.isSuccess
        override val out: List<String> get() = result.out
        override val err: List<String> get() = result.err
    }

    private class LibsuHandle(private val shell: Shell) : RootShell.Handle {
        override val isRoot: Boolean get() = shell.isRoot
        override fun close() = shell.close()
    }
}
