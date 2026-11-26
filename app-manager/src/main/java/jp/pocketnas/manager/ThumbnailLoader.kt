package jp.pocketnas.manager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

class ThumbnailLoader(private val api: PocketNasApi) {

    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val inflight = HashSet<String>()

    /**
     * ★Step2: isVideo=true の場合もサーバーの /api/thumbnail を使う（動画フレーム対応）。
     * 取得に失敗した動画は再生アイコン（ic_media_play）を表示してフォールバックする。
     */
    fun load(path: String, iv: ImageView, isVideo: Boolean = false, isPdf: Boolean = false) {
        // ★Step7: PDF も /api/thumbnail（1ページ目）を使う。失敗時はプレースホルダのまま
        val placeholder = when {
            isVideo -> android.R.drawable.ic_media_play
            isPdf -> android.R.drawable.ic_menu_view
            else -> android.R.drawable.ic_menu_gallery
        }
        iv.setImageResource(placeholder)

        cache.get(path)?.let { iv.setImageBitmap(it); return }

        synchronized(inflight) { if (!inflight.add(path)) return }

        val tag = path
        iv.tag = tag

        executor.execute {
            var bmp: Bitmap? = null
            try {
                val url = api.thumbnailUrl(path, 200) ?: return@execute
                val bytes = api.rawGet(url) ?: return@execute
                bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@execute
                cache.put(path, bmp)
                main.post {
                    if (iv.tag == tag) iv.setImageBitmap(bmp)
                }
            } catch (_: Exception) {
                // 失敗時はプレースホルダのまま（動画は再生アイコン、画像はギャラリーアイコン）
            } finally {
                synchronized(inflight) { inflight.remove(path) }
            }
        }
    }
}
