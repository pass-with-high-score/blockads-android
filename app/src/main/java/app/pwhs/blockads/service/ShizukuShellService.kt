package app.pwhs.blockads.service

/**
 * Shizuku user service that runs shell commands with elevated privileges.
 *
 * This class is instantiated by Shizuku in a separate process (not the app's
 * main process), so it must not reference app singletons like Timber, Koin, etc.
 * It must have a no-arg constructor.
 *
 * The return format for [execCommand] is: "EXIT_CODE\nSTDOUT_CONTENT"
 */
class ShizukuShellService : IShizukuShellService.Stub() {

    override fun destroy() {
        // Called when Shizuku unbinds. No cleanup needed.
    }

    override fun exit() {
        destroy()
        System.exit(0)
    }

    override fun execCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            "$exitCode\n$stdout"
        } catch (e: Exception) {
            "-1\n${e.message}"
        }
    }
}
