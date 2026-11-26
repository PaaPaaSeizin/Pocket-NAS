package jp.pocketnas.manager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.button.MaterialButton
import java.io.File
import java.util.concurrent.Executors

/**
 * ★Step3: 画像ビューアを ViewPager2 のスワイプ式に刷新
 *
 * - フォルダ内の画像リスト全体を受け取り、左右スワイプで切り替える（開閉の手間をなくす）
 * - 各ページは「サムネイル（400px）を先に表示 → 原画に差し替え」の段階表示
 * - 原画は画面サイズに合わせてサンプルデコードし、全解像度デコードによるメモリ圧迫を避ける
 * - offscreenPageLimit=1 で前後のページが先読みされる
 * - ダウンロード済みファイルは modified を含むキャッシュ名で再利用し、同名差し替えにも追随する
 */
class ImageViewerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_NAME = "name"
        const val EXTRA_PATHS = "paths"
        const val EXTRA_NAMES = "names"
        const val EXTRA_MODIFIEDS = "modifieds"
        const val EXTRA_INDEX = "index"
    }

    private lateinit var api: PocketNasApi
    private lateinit var viewPager: ViewPager2
    private lateinit var tvName: TextView
    private lateinit var tvCounter: TextView

    private var paths: List<String> = emptyList()
    private var names: List<String> = emptyList()
    private var modifieds: List<Long> = emptyList()
    private var currentIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)

        val prefs = Prefs(this)
        api = PocketNasApi(prefs, this)

        // リスト形式の extras を優先し、無ければ従来の単一指定にフォールバック
        val listPaths = intent.getStringArrayListExtra(EXTRA_PATHS)
        if (!listPaths.isNullOrEmpty()) {
            paths = listPaths
            names = intent.getStringArrayListExtra(EXTRA_NAMES) ?: listPaths.map { it.substringAfterLast('/') }
            modifieds = intent.getLongArrayExtra(EXTRA_MODIFIEDS)?.toList() ?: List(listPaths.size) { 0L }
            currentIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, listPaths.size - 1)
        } else {
            val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
            paths = listOf(path)
            names = listOf(intent.getStringExtra(EXTRA_NAME) ?: path.substringAfterLast('/'))
            modifieds = listOf(0L)
            currentIndex = 0
        }

        viewPager = findViewById(R.id.viewPager)
        tvName = findViewById(R.id.tvName)
        tvCounter = findViewById(R.id.tvCounter)

        // ★Step7 修正: 開始位置はローカル変数に固定する。
        // 以前は post の中で currentIndex を参照していたが、初回レイアウトで
        // onPageSelected(0) が先に走って currentIndex が 0 に上書きされ、
        // 常に1枚目から開いてしまっていた。
        val startIndex = currentIndex
        viewPager.adapter = ImagePagerAdapter()
        viewPager.offscreenPageLimit = 1
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentIndex = position
                updateHeader()
            }
        })
        viewPager.setCurrentItem(startIndex, false)
        viewPager.post { viewPager.setCurrentItem(startIndex, false) }
        currentIndex = startIndex
        updateHeader()

        findViewById<MaterialButton>(R.id.btnShare).setOnClickListener { shareCurrent() }
    }

    override fun onDestroy() {
        (viewPager.adapter as? ImagePagerAdapter)?.shutdown()
        super.onDestroy()
    }

    private fun updateHeader() {
        tvName.text = names.getOrNull(currentIndex) ?: ""
        tvCounter.text = "${currentIndex + 1} / ${paths.size}"
    }

    private fun cacheFileFor(index: Int): File {
        val safeName = (names.getOrNull(index) ?: "image")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val modified = modifieds.getOrNull(index) ?: 0L
        return File(cacheDir, "pv_${paths[index].hashCode()}_${modified}_$safeName")
    }

    private fun shareCurrent() {
        val index = currentIndex
        val file = cacheFileFor(index)
        val doShare = {
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", file
                )
                val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "image/*"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(android.content.Intent.createChooser(i, "共有"))
            } catch (e: Exception) {
                Toast.makeText(this, "共有失敗: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        if (file.exists() && file.length() > 0) {
            doShare()
        } else {
            Toast.makeText(this, "取得中…", Toast.LENGTH_SHORT).show()
            api.download(paths[index], file) { ok, err ->
                if (ok) doShare()
                else Toast.makeText(this, "取得失敗: $err", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- ページ側 ----------

    private inner class ImagePagerAdapter : RecyclerView.Adapter<ImagePagerAdapter.VH>() {

        private val executor = Executors.newFixedThreadPool(3)
        private val main = Handler(Looper.getMainLooper())

        fun shutdown() { executor.shutdownNow() }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val photoView: PhotoView = itemView.findViewById(R.id.photoView)
            val progress: ProgressBar = itemView.findViewById(R.id.progress)
            var currentPath: String? = null
            var fullLoaded: Boolean = false
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_image_page, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = paths.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val path = paths[position]
            holder.currentPath = path
            holder.fullLoaded = false
            holder.photoView.setImageDrawable(null)
            holder.progress.visibility = View.VISIBLE

            executor.execute {
                // 1) サムネイルを先に表示（体感速度用）
                val thumbUrl = api.thumbnailUrl(path, 400)
                val thumbBytes = if (thumbUrl != null) api.rawGet(thumbUrl) else null
                val thumbBmp = if (thumbBytes != null) {
                    BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
                } else {
                    null
                }
                if (thumbBmp != null) {
                    main.post {
                        if (holder.currentPath == path && !holder.fullLoaded) {
                            holder.photoView.setImageBitmap(thumbBmp)
                        }
                    }
                }

                // 2) 原画を用意する（キャッシュがあればダウンロードしない）
                val file = cacheFileFor(position)
                if (!file.exists() || file.length() == 0L) {
                    // api.download はコールバック方式なので、ラッチでこのスレッドを待たせる
                    // （プールは3本あり、表示中＋前後のページ分は賄える）
                    val latch = java.util.concurrent.CountDownLatch(1)
                    val success = java.util.concurrent.atomic.AtomicBoolean(false)
                    api.download(path, file) { ok, _ ->
                        success.set(ok)
                        latch.countDown()
                    }
                    val finished = latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
                    if (!finished || !success.get()) {
                        main.post {
                            if (holder.currentPath == path) {
                                holder.progress.visibility = View.GONE
                                Toast.makeText(
                                    this@ImageViewerActivity, "取得失敗", Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        return@execute
                    }
                }

                // 3) 画面向けにサンプルデコードして差し替える
                val bmp = decodeSampled(file)
                main.post {
                    if (holder.currentPath == path) {
                        holder.progress.visibility = View.GONE
                        if (bmp != null) {
                            holder.fullLoaded = true
                            holder.photoView.setImageBitmap(bmp)
                        }
                    }
                }
            }
        }

        private fun decodeSampled(file: File, maxDim: Int = 2048): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return BitmapFactory.decodeFile(file.absolutePath)
            }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeFile(file.absolutePath, opts)
        }
    }
}
