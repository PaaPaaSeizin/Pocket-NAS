package jp.pocketnas.server

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLogger {
    private const val MAX_BYTES = 1L * 1024 * 1024
    private const val KEEP = 3

    @Synchronized
    fun log(context: Context, tag: String, message: String) {
        try {
            val f = File(context.filesDir, "pocketnas.log")
            if (f.exists() && f.length() > MAX_BYTES) {
                for (i in KEEP - 1 downTo 1) {
                    val from = File(context.filesDir, "pocketnas.log.$i")
                    val to = File(context.filesDir, "pocketnas.log.${i + 1}")
                    if (from.exists()) from.renameTo(to)
                }
                f.renameTo(File(context.filesDir, "pocketnas.log.1"))
            }
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            File(context.filesDir, "pocketnas.log").appendText("$ts [$tag] $message\n")
        } catch (_: Exception) {}
    }
}