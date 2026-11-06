package pl.magazyn.mobile.agent

import android.util.Log

internal object AgentTimings {
    private const val TAG = "MagazynAgent"

    private val enabled by lazy {
        System.getProperty("java.vm.name") == "Dalvik" && Log.isLoggable(TAG, Log.DEBUG)
    }

    fun start(): Long = System.nanoTime()

    fun record(stage: String, started: Long) {
        if (enabled) {
            Log.d(TAG, "stage=$stage duration_ms=${(System.nanoTime() - started) / 1_000_000}")
        }
    }

    inline fun <T> measure(stage: String, run: () -> T): T {
        val started = start()
        try { return run() } finally { record(stage, started) }
    }
}
