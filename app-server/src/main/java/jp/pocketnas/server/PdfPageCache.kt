package jp.pocketnas.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * ★Step5: Web UI 用に PDF のページを画像化して返すためのキャッシュ
 *
 * ブラウザ標準の PDF ビューア（iframe）はスマホで拡大されすぎたり
 * 1ページしか見られなかったりするため、サーバー側（Android）の
 * PdfRenderer でページを PNG 化し、Web UI は画像として全ページ表示する。
 *
 * - ページ数とレンダリング結果をキャッシュ（キーに更新日時・サイズ・URIハッシュを含む）
 * - PdfRenderer はスレッドセーフではないため、キー単位ロックで同一ページの二重生成を防ぐ
 *   （別ページ・別ファイルは並行可。ただし同一ファイルの同時オープンは PdfRenderer の
 *    実装上も読み取り専用なら問題ない）
 */
object PdfPageCache {
    private const val MAX_BYTES = 200L * 1024 * 1024   // 200MB

    private val keyLocks = ConcurrentHashMap<String, Any>()
    private val trimLock = Any()
    private val countCache = ConcurrentHashMap<String, Int>()

    private fun dir(context: Context): File =
        File(context.cacheDir, "pdfpages").apply { mkdirs() }

    private fun baseKey(doc: DocumentFile): String =
        "${doc.lastModified()}_${doc.length()}_${doc.uri.hashCode()}"

    fun pageCount(context: Context, doc: DocumentFile): Int? {
        val key = baseKey(doc)
        val cached = countCache[key]
        if (cached != null) return cached
        val lock = keyLocks.computeIfAbsent("count_$key") { Any() }
        return synchronized(lock) {
            val again = countCache[key]
            if (again != null) {
                again
            } else {
                try {
                    val n = context.contentResolver.openFileDescriptor(doc.uri, "r")?.use { pfd ->
                        PdfRenderer(pfd).use { renderer -> renderer.pageCount }
                    }
                    if (n != null) countCache[key] = n
                    n
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    fun page(context: Context, doc: DocumentFile, page: Int, width: Int): File? {
        val key = "${baseKey(doc)}_p${page}_w${width}.png"
        val out = File(dir(context), key)
        if (out.exists() && out.length() > 0) {
            out.setLastModified(System.currentTimeMillis())
            return out
        }
        val lock = keyLocks.computeIfAbsent(key) { Any() }
        return synchronized(lock) {
            if (out.exists() && out.length() > 0) {
                out.setLastModified(System.currentTimeMillis())
                return@synchronized out
            }
            try {
                val bmp = context.contentResolver.openFileDescriptor(doc.uri, "r")?.use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        if (page < 0 || page >= renderer.pageCount) {
                            null
                        } else {
                            renderer.openPage(page).use { pg ->
                                val h = (width.toFloat() * pg.height / pg.width)
                                    .toInt().coerceAtLeast(1)
                                val b = Bitmap.createBitmap(
                                    width, h, Bitmap.Config.ARGB_8888
                                )
                                b.eraseColor(Color.WHITE)
                                pg.render(
                                    b, null, null,
                                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                                )
                                b
                            }
                        }
                    }
                }
                if (bmp == null) {
                    null
                } else {
                    FileOutputStream(out).use { stream ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    }
                    bmp.recycle()
                    trim(context)
                    out
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun trim(context: Context) = synchronized(trimLock) {
        val d = dir(context)
        val files = d.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= MAX_BYTES) break
            total -= f.length()
            f.delete()
        }
    }
}
