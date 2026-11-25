package io.github.onceberkant.reklamsizvpn

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Yakalanmayan hataları (ana süreç ve ":openvpn" süreci) diske yazar.
 * "Bağlantı günlüğünü kopyala" düğmesi bu kaydı da kopyalar.
 */
object CrashRecorder {

    private const val FILE = "son_cokme.txt"

    fun install(context: Context, processName: String) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
                val text = buildString {
                    append("Zaman: ").append(time).append('\n')
                    append("Süreç: ").append(processName).append(" / iş parçacığı: ").append(thread.name).append('\n')
                    append("Cihaz: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                        .append(" Android ").append(Build.VERSION.RELEASE)
                        .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                    append("Sürüm: ").append(BuildConfig.VERSION_NAME).append("\n\n")
                    append(sw.toString())
                }
                File(app.filesDir, FILE).writeText(text)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? {
        val f = File(context.filesDir, FILE)
        return if (f.exists()) f.readText() else null
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}
