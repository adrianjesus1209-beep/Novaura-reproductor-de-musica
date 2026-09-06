package com.novaura.music.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Captura cualquier excepción no controlada y la guarda en un archivo
 * para diagnosticar el error desde la propia app.
 *
 * Guarda el stack trace en:
 *  - crash_last.txt  (almacenamiento interno, siempre accesible)
 *  - crash_YYYYMMDD-HHmmss.txt (almacenamiento externo de la app, si está disponible)
 */
object CrashLogger {

    @Volatile
    private var initialized = false

    private val fileLock = Any()

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrashLog(appContext, throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(context: Context, throwable: Throwable) {
        try {
            val stackTrace = StringWriter().also { sw ->
                throwable.printStackTrace(PrintWriter(sw))
            }.toString()

            val laz = System.currentTimeMillis()
            val text = buildString {
                append("=== NOVAURA CRASH ===\n")
                append("Fecha: ${java.text.DateFormat.getDateTimeInstance().format(laz)}\n")
                append("Causa: ${throwable.toString()}\n\n")
                append(stackTrace)
                append("\n=== FIN ===\n")
            }

            synchronized(fileLock) {
                context.openFileOutput(
                    FILE_NAME_LAST,
                    Context.MODE_PRIVATE
                ).bufferedWriter().use { it.write(text) }

                val extDir = context.getExternalFilesDir(null)
                if (extDir != null) {
                    val stamp = java.text.SimpleDateFormat(
                        "yyyyMMdd-HHmmss",
                        java.util.Locale.US
                    ).format(laz)
                    File(extDir, "crash_$stamp.txt").writeText(text)
                }
            }
        } catch (_: Exception) {
            // Nunca se debe caer dentro del crash handler.
        }
    }

    /**
     * Devuelve el último error guardado (o null si no hay ninguno).
     */
    fun readLastCrash(context: Context): String? =
        try {
            context.openFileInput(FILE_NAME_LAST)
                .bufferedReader()
                .use { it.readText() }
                .takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

    private const val FILE_NAME_LAST = "crash_last.txt"
}