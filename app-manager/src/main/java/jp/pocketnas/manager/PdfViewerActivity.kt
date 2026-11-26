package jp.pocketnas.manager

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

/**
 * ★Step4: PDFビューア
 *
 * - PDF をキャッシュにダウンロードして PdfRenderer で開く（キャッシュ名に modified を含む）
 * - ページを縦スクロールのリストとして表示し、表示範囲だけを逐次レンダリングする
 * - PdfRenderer はスレッドセーフではないため、レンダリングは単一スレッド＋ロックで直列化する
 * - 割り切り: ピンチズームは未対応（ページ単位の拡大表示は今後の課題）
 */
class PdfViewerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_NAME = "name"
        const val EXTRA_MODIFIED = "modified"
    }

    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private val renderLock = Any()
    private lateinit var adapter: PdfPageAdapter
    private lateinit var progress: ProgressBar
    private lateinit var tvTitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_viewer)

        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        val name = intent.getStringExtra(EXTRA_NAME) ?: path.substringAfterLast('/')
        val modified = intent.getLongExtra(EXTRA_MODIFIED, 0L)

        tvTitle = findViewById(R.id.tvTitle)
        tvTitle.text = name
        progress = findViewById(R.id.progress)

        val rv = findViewById<RecyclerView>(R.id.rvPages)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = PdfPageAdapter()
        rv.adapter = adapter

        val prefs = Prefs(this)
        val api = PocketNasApi(prefs, this)
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(cacheDir, "pdf_${path.hashCode()}_${modified}_$safeName")

        val openPdf = {
            Thread {
                try {
                    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val r = PdfRenderer(fd)
                    pfd = fd
                    renderer = r
                    runOnUiThread {
                        progress.visibility = View.GONE
                        tvTitle.text = "$name（${r.pageCount}ページ）"
                        adapter.notifyDataSetChanged()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        progress.visibility = View.GONE
                        Toast.makeText(this, "PDFを開けません: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }

        if (file.exists() && file.length() > 0) {
            openPdf()
        } else {
            api.download(path, file) { ok, err ->
                if (ok) {
                    openPdf()
                } else {
                    progress.visibility = View.GONE
                    Toast.makeText(this, "取得失敗: $err", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        adapter.shutdown()
        try { renderer?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        renderer = null
        pfd = null
        super.onDestroy()
    }

    private inner class PdfPageAdapter : RecyclerView.Adapter<PdfPageAdapter.VH>() {

        private val executor = Executors.newSingleThreadExecutor()
        private val renderWidth: Int by lazy {
            (resources.displayMetrics.widthPixels * 1.5).toInt().coerceAtMost(2480)
        }

        fun shutdown() { executor.shutdownNow() }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val image: ImageView = itemView.findViewById(R.id.ivPage)
            val label: TextView = itemView.findViewById(R.id.tvPageNum)
            var pageIndex: Int = -1
            var bitmap: Bitmap? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_pdf_page, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = renderer?.pageCount ?: 0

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.pageIndex = position
            holder.label.text = "${position + 1}"
            holder.image.setImageBitmap(null)
            executor.execute {
                val bmp = renderPage(position) ?: return@execute
                runOnUiThread {
                    if (holder.pageIndex == position) {
                        holder.bitmap?.recycle()
                        holder.bitmap = bmp
                        holder.image.setImageBitmap(bmp)
                    } else {
                        bmp.recycle()
                    }
                }
            }
        }

        override fun onViewRecycled(holder: VH) {
            holder.pageIndex = -1
            holder.image.setImageBitmap(null)
            holder.bitmap?.recycle()
            holder.bitmap = null
            super.onViewRecycled(holder)
        }

        private fun renderPage(index: Int): Bitmap? = synchronized(renderLock) {
            val r = renderer ?: return null
            if (index < 0 || index >= r.pageCount) return null
            try {
                r.openPage(index).use { page ->
                    val width = renderWidth
                    val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
