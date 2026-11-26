package jp.pocketnas.manager

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton

/**
 * ★Step4: 音楽プレイヤー
 *
 * - フォルダ内の音声ファイル全体を Media3 のプレイリストとして再生する
 * - 前/次の曲移動はプレイヤー標準コントローラ、リピート（なし→全曲→1曲）とシャッフルの切替ボタン付き
 * - 割り切り: バックグラウンド再生（メディアセッション/通知コントロール）は未対応。
 *   画面を離れると一時停止する（VideoPlayerActivity と同じ方針）
 */
class AudioPlayerActivity : AppCompatActivity() {
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
        setContentView(R.layout.activity_audio_player)

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
        val btnRepeat = findViewById<MaterialButton>(R.id.btnRepeat)
        val btnShuffle = findViewById<MaterialButton>(R.id.btnShuffle)

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
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    updateHeader(exo.currentMediaItemIndex)
                }
            })
        }
        updateHeader(startIndex)

        btnRepeat.setOnClickListener {
            val exo = player ?: return@setOnClickListener
            exo.repeatMode = when (exo.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            btnRepeat.text = repeatLabel(exo.repeatMode)
        }
        btnShuffle.setOnClickListener {
            val exo = player ?: return@setOnClickListener
            exo.shuffleModeEnabled = !exo.shuffleModeEnabled
            btnShuffle.text = if (exo.shuffleModeEnabled) "🔀 シャッフル ON" else "🔀 シャッフル OFF"
        }
    }

    private fun repeatLabel(mode: Int): String = when (mode) {
        Player.REPEAT_MODE_ALL -> "🔁 全曲リピート"
        Player.REPEAT_MODE_ONE -> "🔂 1曲リピート"
        else -> "🔁 リピートなし"
    }

    private fun updateHeader(index: Int) {
        tvTitle.text = names.getOrNull(index) ?: ""
        tvCounter.text = "${index + 1} / ${names.size}"
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
    }

    override fun onStop() {
        player?.playWhenReady = false
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
