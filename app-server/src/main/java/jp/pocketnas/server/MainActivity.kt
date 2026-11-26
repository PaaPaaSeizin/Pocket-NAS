package jp.pocketnas.server

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: ServerPrefs
    private val pick = 41

    private lateinit var tvServerState: TextView
    private lateinit var tvStorage: TextView
    private lateinit var tvAddresses: TextView
    private lateinit var tvBattery: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ServerPrefs(this)
        setContentView(R.layout.activity_main)

        tvServerState = findViewById(R.id.tvServerState)
        tvStorage = findViewById(R.id.tvStorage)
        tvAddresses = findViewById(R.id.tvAddresses)
        tvBattery = findViewById(R.id.tvBattery)

        findViewById<MaterialButton>(R.id.btnPickFolder).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), pick)
        }
        findViewById<MaterialButton>(R.id.btnStart).setOnClickListener {
            if (prefs.treeUri == null) {
                toast("先に保存先を選択してください"); return@setOnClickListener
            }
            ContextCompat.startForegroundService(
                this,
                Intent(this, NasServerService::class.java).setAction("START")
            )
            refresh()
        }
        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener {
            startService(Intent(this, NasServerService::class.java).setAction("STOP"))
            WatchdogReceiver.cancel(this)
            refresh()
        }
        findViewById<MaterialButton>(R.id.btnBattery).setOnClickListener {
            requestIgnoreBattery()
        }
        findViewById<MaterialButton>(R.id.btnNfc).setOnClickListener {
            showNfcInfo()
        }

        // ★Index: 充電中＋アイドル時の定期インデックスを登録
        try { IndexWorker.schedule(this) } catch (_: Exception) {}
        refresh()
        if (!isBatteryExempt()) requestIgnoreBattery()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun isBatteryExempt(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBattery() {
        if (isBatteryExempt()) return
        if (Build.VERSION.SDK_INT < 23) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun showNfcInfo() {
        AlertDialog.Builder(this)
            .setTitle("NFC ペアリング")
            .setMessage(
                "マネージャー端末をこの 端末 の NFC エリア（カメラ付近）にタッチすると、" +
                        "接続先が自動設定されます。\n\n" +
                        "うまくいかない場合：\n" +
                        "・端末 の NFC がONか確認\n" +
                        "・マネージャー側で『NFCでタッチ』を押してから近づける\n" +
                        "・ケースを外す"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun refresh() {
        tvServerState.text = if (NasServerService.running)
            "✅ 稼働中（LAN内）" else "⏹ 停止中"

        tvBattery.text = if (isBatteryExempt())
            "🔋 バッテリー最適化: 除外済み（スリープで止まりません）"
        else
            "⚠ バッテリー最適化: 未除外（スリープで止まる可能性）"

        // ★ ストレージ情報の取得は別スレッドで行う（ANR防止）
        val treeUriStr = prefs.treeUri
        if (treeUriStr == null) {
            tvStorage.text = "保存先: 未選択"
        } else {
            tvStorage.text = "保存先: 読み込み中…"
            Thread {
                val root = runCatching {
                    DocumentFile.fromTreeUri(this, Uri.parse(treeUriStr))
                }.getOrNull()

                val storageName = StorageUtils.name(this, root)
                val free = StorageUtils.freeSpaceOf(this, root)
                val total = StorageUtils.totalSpaceOf(this, root)

                runOnUiThread {
                    tvStorage.text = if (root == null)
                        "保存先: 未選択"
                    else
                        "保存先: $storageName\n空き ${StorageUtils.formatBytes(free)} / " +
                                "合計 ${StorageUtils.formatBytes(total)}"
                }
            }.start()
        }

        // ネットワーク情報の取得も別スレッド
        tvAddresses.text = "取得中…"
        Thread {
            val ips = StorageUtils.localIps()
            runOnUiThread {
                tvAddresses.text = if (ips.isEmpty()) "Wi-Fi未接続"
                else ips.joinToString("\n") { pair ->
                    val nif = pair.first
                    val ip = pair.second
                    val label = when {
                        nif.startsWith("wlan") -> "Wi-Fi"
                        nif.startsWith("rmnet") || nif.startsWith("ccmni") -> "モバイル"
                        nif.startsWith("tun") || nif.startsWith("ppp") -> "VPN"
                        nif.startsWith("ap") || nif.startsWith("swlan") -> "テザリング"
                        else -> nif
                    }
                    "[$label]  http://$ip:8080"
                }
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pick && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val flags = data.flags and (
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
                prefs.treeUri = uri.toString()
            } catch (e: Exception) {
                toast("権限取得失敗: ${e.message}")
            }
            // ★ OSのインデックス作成が落ち着くまで1秒待ってからUIを更新
            tvStorage.text = "保存先: 読み込み中…"
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                refresh()
            }, 1000)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}