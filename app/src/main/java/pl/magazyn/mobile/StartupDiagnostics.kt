package pl.magazyn.mobile

import android.content.Context

/**
 * Zapisuje krótki opis nieobsłużonego błędu do pokazania przy kolejnym starcie.
 * Nie zastępuje systemowego handlera i nie pokazuje użytkownikowi stack trace.
 */
class StartupDiagnostics private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun lastProblem(): String? = preferences.getString(KEY_LAST_PROBLEM, null)

    fun clearLastProblem() {
        preferences.edit().remove(KEY_LAST_PROBLEM).apply()
    }

    companion object {
        private const val PREFERENCES = "startup-diagnostics"
        private const val KEY_LAST_PROBLEM = "last-problem"
        private const val MAX_LENGTH = 500

        fun from(context: Context) = StartupDiagnostics(context)

        fun install(context: Context) {
            val appContext = context.applicationContext
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching {
                    val summary = buildString {
                        append(error.javaClass.simpleName.ifBlank { "Nieznany błąd" })
                        error.message?.takeIf(String::isNotBlank)?.let { append(": ").append(it) }
                    }.take(MAX_LENGTH)
                    appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                        .edit().putString(KEY_LAST_PROBLEM, summary).apply()
                }
                previousHandler?.uncaughtException(thread, error)
            }
        }
    }
}
