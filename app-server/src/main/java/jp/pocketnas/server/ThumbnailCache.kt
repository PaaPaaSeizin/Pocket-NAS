package jp.pocketnas.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * サムネイル生成・キャッシュ
 *
 * ★Step2 変更点:
 *  - 動画対応: MediaMetadataRetriever で 1 秒位置のフレームを代表画像にする。
 *    取れないコーデックでは null を返し、呼び出し側がアイコン表示にフォールバックする。
 *  - 並列化: 従来は get() 全体が単一 synchronized で全リクエスト直列だった。
 *    キャッシュキー単位のロックに変え、別ファイルのサムネは並行生成できるようにした。
 *  - キーに URI のハッシュも含め、同名・同サイズ・同時刻の別ファイルとの衝突を減らした。
 *    動画は "v_"、画像は "i_" プレフィックスで区別する。
 */
object ThumbnailCache {
    private const val MAX_BYTES = 200L * 1024 * 1024   // 200MB

    private val keyLocks = ConcurrentHashMap<String, Any>()
    private val trimLock = Any()

    private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "m4v", "3gp", "avi", "ts")

    // ★Step7: PDF サムネイル（1ページ目）対応
    fun isPdf(doc: DocumentFile): Boolean {
        if (doc.type == "application/pdf") return true
        val ext = (doc.name ?: "").substringAfterLast('.', "").lowercase()
        return ext == "pdf"
    }

    fun isVideo(doc: DocumentFile): Boolean {
        val type = doc.type ?: ""
        if (type.startsWith("video/")) return true
        val ext = (doc.name ?: "").substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTS
    }

    private fun dir(context: Context): File =
        File(context.cacheDir, "thumbs").apply { mkdirs() }

    fun get(context: Context, doc: DocumentFile, size: Int): File? {
        val video = isVideo(doc)
        val pdf = isPdf(doc)
        val mtime = doc.lastModified()
        val name = doc.name ?: return null
        val prefix = when {
            video -> "v"
            pdf -> "p"
            else -> "i"
        }
        val key = "${prefix}_${size}_${mtime}_${doc.length()}_${doc.uri.hashCode()}_${name.hashCode()}.jpg"
        val out = File(dir(context), key)

        // 高速パス: ロックなしでヒット確認
        if (out.exists() && out.length() > 0) {
            out.setLastModified(System.currentTimeMillis())
            return out
        }

        // キー単位で排他（同じファイルの二重生成だけ防ぎ、他ファイルは並行に進める）
        val lock = keyLocks.computeIfAbsent(key) { Any() }
        val result = synchronized(lock) {
            if (out.exists() && out.length() > 0) {
                out.setLastModified(System.currentTimeMillis())
                return@synchronized out
            }
            // 括弧で囲まないと ?: が else 側にだけ掛かり bmp が Bitmap? になる
            val bmp = (when {
                video -> decodeVideoFrame(context, doc.uri, size)
                pdf -> renderPdfFirstPage(context, doc.uri, size)
                else -> decodeScaled(context, doc.uri, size)
            }) ?: return@synchronized null
            try {
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            } finally {
                bmp.recycle()
            }
            trim(context)
            out
        }
        // ロック解放後に外す（解放前に外すと別ロックで同じ生成が並行してしまうため）
        keyLocks.remove(key, lock)
        return result
    }

    // ---------- 動画 ----------

    private fun decodeVideoFrame(context: Context, uri: Uri, target: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            // 1秒位置 → ダメなら先頭フレーム
            val frame = runCatching {
                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }.getOrNull() ?: runCatching { retriever.frameAtTime }.getOrNull()
                ?: return null
            scaleTo(frame, target)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ---------- 画像 ----------

    private fun decodeScaled(context: Context, uri: Uri, target: Int): Bitmap? {
        // 1回目: サイズだけ取得
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        var sample = 1
        val minDim = minOf(w, h)
        while (minDim / sample > target * 2) sample *= 2

        // 2回目: 縮小デコード
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }.getOrNull() ?: return null

        return scaleTo(bmp, target)
    }

    // ---------- PDF（1ページ目） ----------

    private fun renderPdfFirstPage(context: Context, uri: Uri, target: Int): Bitmap? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount <= 0) {
                        null
                    } else {
                        renderer.openPage(0).use { page ->
                            // 解像度を確保するため target の2倍で描いてから縮小する
                            val w = target * 2
                            val h = (w.toFloat() * page.height / page.width)
                                .toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(
                                w, h, Bitmap.Config.ARGB_8888
                            )
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(
                                bmp, null, null,
                                android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            )
                            scaleTo(bmp, target)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- 共通 ----------

    private fun scaleTo(bmp: Bitmap, target: Int): Bitmap {
        val maxDim = maxOf(bmp.width, bmp.height)
        if (maxDim <= target) return bmp
        val ratio = target.toFloat() / maxDim
        val nw = (bmp.width * ratio).toInt().coerceAtLeast(1)
        val nh = (bmp.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
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
