package jp.pocketnas.manager

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton

/**
 * ★Step7: 動画プレイヤーをプレイリスト対応に変更
 *
 * - フォルダ内の動画全体を Media3 のプレイリストとして受け取り、
 *   上部の「前」「次」ボタン（と標準コントローラ）で前後の動画へ切り替えられる
 * - タイトルと「n / 総数」を上部バーに表示し、切り替えに追随する
 * - 従来の単一指定（EXTRA_PATH/EXTRA_NAME）でもそのまま開ける
 */
class VideoPlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_NAME = "name"
        const val EXTRA_PATHS = "paths"
        const val EXTRA_NAMES = "names"
        const val EXTRA_INDEX = "index"
    }

    private var player: ExoPlayer? = null
    private lateinit var tvTitle: TextView
    private lateinit var tvCounter: TextView
    private var names: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        val prefs = Prefs(this)
        val api = PocketNasApi(prefs, this)

        val listPaths = intent.getStringArrayListExtra(EXTRA_PATHS)
        val paths: List<String>
        val startIndex: Int
        if (!listPaths.isNullOrEmpty()) {
            paths = listPaths
            names = intent.getStringArrayListExtra(EXTRA_NAMES)
                ?: listPaths.map { it.substringAfterLast('/') }
            startIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, listPaths.size - 1)
        } else {
            val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
            paths = listOf(path)
            names = listOf(intent.getStringExtra(EXTRA_NAME) ?: path.substringAfterLast('/'))
            startIndex = 0
        }

        tvTitle = findViewById(R.id.tvTitle)
        tvCounter = findViewById(R.id.tvCounter)
        val playerView = findViewById<PlayerView>(R.id.playerView)
        val progress = findViewById<ProgressBar>(R.id.progress)
        val btnPrev = findViewById<MaterialButton>(R.id.btnPrev)
        val btnNext = findViewById<MaterialButton>(R.id.btnNext)

        val items = paths.mapNotNull { p ->
            api.downloadUrl(p)?.let { url -> MediaItem.fromUri(url) }
        }
        if (items.isEmpty()) {
            Toast.makeText(this, "URL生成失敗", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.setMediaItems(items)
            exo.seekTo(startIndex, 0L)
            exo.prepare()
            exo.playWhenReady = true

            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    progress.visibility =
                        if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    updateHeader(exo.currentMediaItemIndex)
                }
            })
        }
        updateHeader(startIndex)

        btnPrev.setOnClickListener {
            player?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() }
        }
        btnNext.setOnClickListener {
            player?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
        }
    }

    private fun updateHeader(index: Int) {
        tvTitle.text = names.getOrNull(index) ?: ""
        tvCounter.text = "${index + 1} / ${names.size}"
    }

    override fun onStart() {
        super.onStart()
        // バックグラウンドから戻った時に再開
        player?.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        // バックグラウンドに行ったら一時停止
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
