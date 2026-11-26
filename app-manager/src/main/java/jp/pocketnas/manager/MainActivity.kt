package jp.pocketnas.manager

import androidx.recyclerview.widget.GridLayoutManager
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var api: PocketNasApi
    private lateinit var thumbs: ThumbnailLoader

    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null

    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusCard: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var breadcrumbScroll: HorizontalScrollView
    private lateinit var breadcrumbBar: LinearLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var rvFiles: RecyclerView
    private lateinit var emptyView: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var progress: ProgressBar
    private lateinit var fab: FloatingActionButton
    private lateinit var searchBar: LinearLayout
    private lateinit var etSearch: EditText

    private lateinit var adapter: FileAdapter

    private var allFiles: List<FileItem> = emptyList()
    private var currentPath = ""
    private var searchMode = false

    private var pendingDownloadPath = ""
    private var pendingDownloadName = ""
    private var nfcReader: NfcReader? = null
    private var gridMode = false
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    // ★F1: 多重発火防止
    private val discovering = AtomicBoolean(false)
    // ★F2: 無限再接続ループ防止
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 3

    private val uploadPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { uploadFile(it) } }

    private val savePicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri -> uri?.let { saveDownloaded(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        api = PocketNasApi(prefs, this)
        thumbs = ThumbnailLoader(api)
        setContentView(R.layout.activity_main)
        // ★Step6: 認証が必要になったらダイアログを出す
        api.onAuthRequired = { showServerLoginDialog() }
        api.onFolderLocked = { folder -> showFolderUnlockDialog(folder) }
        findViewById<MaterialButton>(R.id.btnUnlock)?.setOnClickListener { showBiometricPrompt() }
        bindViews()
        setupUi()

        nfcReader = NfcReader(this) { host, _ ->
            runOnUiThread {
                if (host != null) {
                    prefs.host = host
                    toast("NFC で接続先取得: $host")
                    showReconnecting()
                    attemptReconnect()
                } else {
                    toast("NFC 読み取り失敗")
                }
            }
        }

        gridMode = prefs.gridMode
        applyLayoutMode()

        if (prefs.host == null) {
            showDisconnected("NASを検索しています…\n（NFCでタッチも可）")
            discover()
        } else {
            showReconnecting()
            attemptReconnect()
        }

        // ★Step9: 共有メニューから起動された場合の受け取り
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    // ★Step9: Android の共有（ACTION_SEND / SEND_MULTIPLE）で渡されたファイルを保持し、
    // 接続とロック解除が済んだタイミングでアップロード先を選ばせる
    private var pendingShareUris: List<Uri>? = null

    @Suppress("DEPRECATION")
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    ?: emptyList()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        pendingShareUris = uris
        intent.action = null   // 画面回転などで同じ共有を二重に処理しないため
        tryStartShareUpload()
    }

    private fun tryStartShareUpload() {
        val uris = pendingShareUris ?: return
        if (prefs.appLockEnabled && !appUnlocked) return   // ロック解除後に unlockApp から再開
        if (statusCard.isVisible) {
            // 未接続: 接続できたら showConnected から呼ばれる
            toast("接続後にアップロード先を選んでください")
            return
        }
        pendingShareUris = null
        pickFolder("アップロード先フォルダ", currentPath) { dst ->
            uploadSharedFiles(uris, dst)
        }
    }

    private fun uploadSharedFiles(uris: List<Uri>, dst: String) {
        var index = 0
        fun next() {
            if (index >= uris.size) return
            val uri = uris[index]
            index++
            uploadFile(uri, dst) { next() }
        }
        next()
    }

    private fun showReconnecting() {
        statusCard.isVisible = true
        breadcrumbScroll.isVisible = false
        rvFiles.isVisible = false
        emptyView.isVisible = false
        fab.isVisible = false
        tvStatus.text = "再接続中…"
        toolbar.subtitle = "接続中"
    }

    // ===== ★P5: 起動時の二重往復削減 =====
    private fun attemptReconnect() {
        // いきなり /api/files を叩く。成功すれば接続確認兼ねる。
        api.listFiles(currentPath) { list ->
            if (list.isEmpty() && prefs.host != null) {
                // 空でも接続は成功している可能性がある。念のため /api/status で切り分け
                api.request("GET", "/api/status") { code, result ->
                    if (code == 200) handleStatusSuccess(result)
                    else scheduleReconnect()
                }
            } else {
                handleStatusSuccessRaw()
                allFiles = list
                applyFilter(etSearch.text.toString())
                updateBreadcrumb(currentPath)
            }
        }
    }

    private fun handleStatusSuccessRaw() {
        // listFiles が成功した時点で接続は確定。サブタイトルだけ後で更新。
        toolbar.subtitle = "接続中"
        showConnected()
        reconnectAttempts = 0
        // 非同期で詳細取得して表示をリッチに
        api.fetchStatus { j ->
            if (j != null) {
                val model = j.optString("model")
                val batt = j.optInt("batteryPercent", -1)
                toolbar.subtitle = "接続中: $model" + if (batt >= 0) " · 🔋$batt%" else ""
                prefs.lastStatusJson = j.toString()
                prefs.lastStatusAt = System.currentTimeMillis()
            }
        }
    }

    private fun handleStatusSuccess(result: String) {
        val info = runCatching {
            val j = org.json.JSONObject(result)
            "接続中: ${j.optString("model")} · 🔋${j.optInt("batteryPercent")}%"
        }.getOrDefault("接続中")
        toolbar.subtitle = info
        prefs.lastStatusJson = result
        prefs.lastStatusAt = System.currentTimeMillis()
        showConnected()
        reconnectAttempts = 0
        listFiles(currentPath)
    }

    // ★F2: 無限再接続の防止（3回で打ち切り、バックオフ）
    private fun scheduleReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            tvStatus.text = "接続失敗（3回リトライ済み）。\n「NAS検索」か「手動」で再接続してください。"
            return
        }
        reconnectAttempts++
        val delay = when (reconnectAttempts) {
            1 -> 1000L
            2 -> 2000L
            else -> 4000L
        }
        tvStatus.text = "NASを再検索中…（試行 ${reconnectAttempts}/${maxReconnectAttempts}）"
        searchHandler.postDelayed({ discover() }, delay)
    }

    // ===== サーバー状態（★P7/P8: 1往復キャッシュ） =====
    private fun showServerStatus() {
        val cachedJson = prefs.lastStatusJson
        val cachedAt = prefs.lastStatusAt
        val fresh = (System.currentTimeMillis() - cachedAt) < 10_000

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 8)
        }
        val deviceSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val batterySection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val memorySection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val uptimeSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val storageSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val serverSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val indexSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val authSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(deviceSection); container.addView(batterySection)
        container.addView(memorySection); container.addView(uptimeSection)
        container.addView(storageSection); container.addView(serverSection)
        container.addView(indexSection)
        container.addView(authSection)

        val storageStatusText = TextView(this).apply {
            text = "取得中…"; textSize = 14f; setPadding(0,6,0,6); setTextColor(Color.GRAY)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("サーバー状態")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("閉じる", null)
            .create()
        dialog.show()

        fun renderStatus(json: org.json.JSONObject) {
            fillSection(deviceSection, "デバイス")
            addRow(deviceSection, "モデル", json.optString("model", "-"))
            addRow(deviceSection, "Android", "API ${json.optInt("android", -1)}")

            fillSection(batterySection, "バッテリー")
            val batt = json.optInt("batteryPercent", -1)
            addRow(batterySection, "残量", if (batt >= 0) "$batt%" else "-")
            val temp = json.optDouble("batteryTempC", -1.0)
            if (temp > 0) addRow(batterySection, "温度", "%.1f °C".format(temp))

            fillSection(memorySection, "メモリ")
            val freeMem = json.optLong("freeMemory", 0)
            val totalMem = json.optLong("totalMemory", 0)
            addRow(memorySection, "使用中", formatSize((totalMem - freeMem).coerceAtLeast(0)))
            addRow(memorySection, "空き", formatSize(freeMem))
            addRow(memorySection, "合計", formatSize(totalMem))

            fillSection(uptimeSection, "稼働時間")
            addRow(uptimeSection, "Uptime", formatUptime(json.optLong("uptimeSeconds", 0)))

            fillSection(serverSection, "サーバー")
            addRow(serverSection, "バインドIP", json.optString("boundIp", "-"))
            addRow(serverSection, "処理中", json.optInt("activeRequests", 0).toString())
            addRow(serverSection, "ログ件数", json.optInt("logSize", 0).toString())
        }

        // P8: 直近10秒以内なら即表示
        if (fresh && cachedJson != null) {
            runCatching { renderStatus(org.json.JSONObject(cachedJson)) }
        }

        // その後バックグラウンドで最新を取得
        api.fetchStatus { status ->
            runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                if (status != null) {
                    prefs.lastStatusJson = status.toString()
                    prefs.lastStatusAt = System.currentTimeMillis()
                    renderStatus(status)
                }
            }
        }

        // ★Index: インデックス状態（件数・最終スキャン・再構築ボタン）
        fillSection(indexSection, "インデックス")
        val indexStatusText = TextView(this).apply {
            text = "取得中…"; textSize = 14f; setPadding(0,6,0,6); setTextColor(Color.GRAY)
        }
        indexSection.addView(indexStatusText)
        api.fetchIndexStatus { idx ->
            runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                indexSection.removeAllViews()
                fillSection(indexSection, "インデックス")
                if (idx == null) {
                    indexSection.addView(TextView(this).apply {
                        text = "取得失敗（旧サーバーの可能性）"; textSize = 14f; setPadding(0,6,0,6)
                    })
                } else {
                    addRow(indexSection, "状態", when {
                        idx.optBoolean("scanning") -> "スキャン中…"
                        idx.optBoolean("built") -> "構築済み"
                        else -> "未構築"
                    })
                    addRow(indexSection, "件数", idx.optLong("count", 0).toString())
                    val last = idx.optLong("lastFullScanAt", 0)
                    addRow(indexSection, "最終スキャン",
                        if (last > 0) java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                            .format(java.util.Date(last)) else "-")
                    val btn = MaterialButton(this).apply {
                        text = "今すぐ再構築"
                        setOnClickListener {
                            api.rebuildIndex { ok ->
                                toast(if (ok) "インデックス再構築を開始しました" else "開始失敗")
                            }
                        }
                    }
                    indexSection.addView(btn)
                }
            }
        }

        // ★Step6: セキュリティ（サーバーパスワード／アプリロック／フォルダパスワード）
        fillSection(authSection, "セキュリティ")
        val authLoadingText = TextView(this).apply {
            text = "取得中…"; textSize = 14f; setPadding(0,6,0,6); setTextColor(Color.GRAY)
        }
        authSection.addView(authLoadingText)
        api.fetchAuthStatus { okFetch, pwSet, locked ->
            runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                authSection.removeAllViews()
                fillSection(authSection, "セキュリティ")
                if (!okFetch) {
                    authSection.addView(TextView(this).apply {
                        text = "取得失敗（旧サーバーの可能性）"; textSize = 14f; setPadding(0,6,0,6)
                    })
                    return@runOnUiThread
                }
                addRow(authSection, "サーバーパスワード", if (pwSet) "設定済み" else "未設定")
                addRow(authSection, "ロック中フォルダ",
                    if (locked.isEmpty()) "なし" else locked.size.toString() + "件")
                val btnPw = MaterialButton(this).apply {
                    text = if (pwSet) "サーバーパスワード変更" else "サーバーパスワード設定"
                    setOnClickListener { showServerPasswordDialog() }
                }
                authSection.addView(btnPw)
                if (pwSet) {
                    val btnClear = MaterialButton(this).apply {
                        text = "サーバーパスワード解除"
                        setOnClickListener {
                            api.setServerPassword("") { ok ->
                                if (ok) {
                                    prefs.serverPassword = null
                                    prefs.sessionToken = null
                                    toast("サーバーパスワードを解除しました")
                                    dialog.dismiss()
                                } else {
                                    toast("解除失敗")
                                }
                            }
                        }
                    }
                    authSection.addView(btnClear)
                }
                val btnLock = MaterialButton(this)
                btnLock.text = "指紋/PINロック: " + if (prefs.appLockEnabled) "ON" else "OFF"
                btnLock.setOnClickListener {
                    prefs.appLockEnabled = !prefs.appLockEnabled
                    btnLock.text = "指紋/PINロック: " + if (prefs.appLockEnabled) "ON" else "OFF"
                    toast("次にアプリを表示したときから適用されます")
                }
                authSection.addView(btnLock)
                val btnFolder = MaterialButton(this).apply {
                    text = "フォルダパスワード管理"
                    setOnClickListener {
                        dialog.dismiss()
                        pickFolder("パスワードを設定するフォルダ", currentPath) { path ->
                            showFolderPasswordDialog(path)
                        }
                    }
                }
                authSection.addView(btnFolder)
            }
        }

        // ストレージは後から（サーバー側でも30秒キャッシュなので2回目以降は速い）
        fillSection(storageSection, "ストレージ")
        storageSection.addView(storageStatusText)
        api.fetchStorage { storage ->
            runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                storageSection.removeAllViews()
                fillSection(storageSection, "ストレージ")
                if (storage != null) {
                    addRow(storageSection, "保存先", storage.optString("name", "-"))
                    val free = storage.optLong("free", -1)
                    val total = storage.optLong("total", -1)
                    if (total > 0) {
                        addRow(storageSection, "使用中", formatSize((total - free).coerceAtLeast(0)))
                        addRow(storageSection, "空き", formatSize(free))
                        addRow(storageSection, "合計", formatSize(total))
                    } else {
                        addRow(storageSection, "状態", "取得できませんでした")
                    }
                } else {
                    addRow(storageSection, "状態", "取得失敗（タップで再試行）")
                    storageSection.setOnClickListener { dialog.dismiss(); showServerStatus() }
                }
            }
        }
    }

    private fun fillSection(container: LinearLayout, title: String) {
        container.removeAllViews()
        container.addView(TextView(this).apply {
            text = title
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#3F51B5"))
            setPadding(0, 16, 0, 4)
        })
    }

    private fun addRow(container: LinearLayout, label: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 6)
        }
        row.addView(TextView(this).apply {
            text = label; textSize = 13f; setTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        row.addView(TextView(this).apply {
            text = value; textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1.5f)
        })
        container.addView(row)
    }

    private fun formatUptime(sec: Long): String {
        val d = sec / 86400; val h = (sec % 86400) / 3600; val m = (sec % 3600) / 60
        return when {
            d > 0 -> "${d}日 ${h}時間 ${m}分"
            h > 0 -> "${h}時間 ${m}分"
            m > 0 -> "${m}分"
            else -> "${sec}秒"
        }
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        statusCard = findViewById(R.id.statusCard)
        tvStatus = findViewById(R.id.tvStatus)
        breadcrumbScroll = findViewById(R.id.breadcrumbScroll)
        breadcrumbBar = findViewById(R.id.breadcrumbBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        rvFiles = findViewById(R.id.rvFiles)
        emptyView = findViewById(R.id.emptyView)
        tvEmpty = findViewById(R.id.tvEmpty)
        progress = findViewById(R.id.progress)
        fab = findViewById(R.id.fab)
        searchBar = findViewById(R.id.searchBar)
        etSearch = findViewById(R.id.etSearch)

        findViewById<MaterialButton>(R.id.btnDiscover).setOnClickListener { discover() }
        findViewById<MaterialButton>(R.id.btnManual).setOnClickListener { showManualHost() }
        findViewById<MaterialButton>(R.id.btnNfc).setOnClickListener { startNfc() }
        findViewById<MaterialButton>(R.id.btnServerSearch).setOnClickListener {
            val q = etSearch.text.toString().trim()
            if (q.isBlank()) { toast("検索語を入力してください"); return@setOnClickListener }
            serverSearch(q)
        }
        // btnGrid はレイアウト改修で toolbarメニューへ移動するので findViewById しない
    }

    private fun startNfc() {
        val reader = nfcReader
        if (reader == null || !reader.isAvailable()) { toast("この端末はNFC非対応です"); return }
        if (!reader.isEnabled()) { toast("NFCをONにしてください"); return }
        toast("サーバー端末のNFCエリアにタッチしてください")
        reader.start()
    }

    private fun applyLayoutMode() {
        rvFiles.layoutManager = if (gridMode)
            GridLayoutManager(this, 3) else LinearLayoutManager(this)
        // GridAdapter に切り替えたい場合はここで adapter 差し替え（今回は FileAdapter 一本で対応）
    }

    private fun setupUi() {
        setSupportActionBar(toolbar)

        adapter = FileAdapter(
            prefs = prefs,
            thumbs = thumbs,
            onItemClick = { item ->
                if (item.directory) listFiles(item.path) else showFileActions(item)
            },
            onLongClick = { item ->
                adapter.enterSelection(item)
                updateToolbar()
            },
            onSelectionChanged = { updateToolbar() }
        )
        rvFiles.layoutManager = LinearLayoutManager(this)
        rvFiles.adapter = adapter
        rvFiles.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        swipeRefresh.setOnRefreshListener { listFiles(currentPath) }
        fab.setOnClickListener { showFabMenu() }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                if (searchMode) return
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                val r = Runnable { applyFilter(s?.toString().orEmpty()) }
                searchRunnable = r
                searchHandler.postDelayed(r, 200)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // ===== ★F1: discover 多重発火防止 =====
    private fun discover() {
        if (!discovering.compareAndSet(false, true)) return
        try {
            val wifi = getSystemService(WIFI_SERVICE) as WifiManager
            if (multicast?.isHeld != true) {
                multicast = wifi.createMulticastLock("pocketnas-manager").apply {
                    setReferenceCounted(false); acquire()
                }
            }
            nsd = getSystemService(NSD_SERVICE) as NsdManager
            runCatching { discovery?.let { nsd?.stopServiceDiscovery(it) } }

            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) {}
                override fun onServiceFound(service: NsdServiceInfo) {
                    if (!service.serviceType.contains("_pocketnas._tcp")) return
                    runCatching {
                        nsd?.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
                            override fun onServiceResolved(i: NsdServiceInfo) {
                                val addr = i.host?.hostAddress ?: return
                                val newHost = "http://$addr:${i.port}"
                                runOnUiThread {
                                    // ★F3: 変わった時だけ書き込み
                                    if (prefs.host != newHost) prefs.host = newHost
                                    if (!statusCard.isVisible) attemptReconnect()
                                    else { showReconnecting(); attemptReconnect() }
                                }
                            }
                        })
                    }
                }
                override fun onServiceLost(s: NsdServiceInfo) {}
                override fun onDiscoveryStopped(t: String) { discovering.set(false) }
                override fun onStartDiscoveryFailed(t: String, c: Int) {
                    discovering.set(false)
                    runOnUiThread { tvStatus.text = "検索開始失敗: $c" }
                }
                override fun onStopDiscoveryFailed(t: String, c: Int) { discovering.set(false) }
            }
            discovery = listener
            nsd?.discoverServices("_pocketnas._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            discovering.set(false)
            tvStatus.text = "検索エラー: ${e.message}"
        }
    }

    private fun showDisconnected(msg: String) {
        statusCard.isVisible = true
        breadcrumbScroll.isVisible = false
        rvFiles.isVisible = false
        emptyView.isVisible = false
        fab.isVisible = false
        tvStatus.text = msg
        toolbar.subtitle = "未接続"
    }

    private fun showConnected() {
        statusCard.isVisible = false
        breadcrumbScroll.isVisible = true
        rvFiles.isVisible = true
        fab.isVisible = !adapter.isSelectionMode() && !isAppLocked()
        // ★Step9: 共有から起動して接続待ちだった場合は、ここでアップロード先選択へ進む
        tryStartShareUpload()
    }

    private fun showManualHost() {
        val input = EditText(this).apply {
            hint = "IPアドレス:ポート（例: 192.168.0.10:8080）"
            setText(prefs.host?.removePrefix("http://") ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("NASホスト手動入力")
            .setView(input)
            .setPositiveButton("設定") { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isEmpty()) return@setPositiveButton
                prefs.host = if (raw.startsWith("http")) raw else "http://$raw"
                reconnectAttempts = 0
                showReconnecting()
                attemptReconnect()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ===== ファイル一覧 =====
    private fun listFiles(path: String) {
        currentPath = path
        searchMode = false
        progress.isVisible = true
        api.listFiles(path) { list ->
            progress.isVisible = false
            swipeRefresh.isRefreshing = false
            allFiles = list
            applyFilter(etSearch.text.toString())
            updateBreadcrumb(path)
            // ★P3: プリロード（前後3件の画像）
            if (prefs.preload) {
                val images = list.filter { !it.directory &&
                        it.name.lowercase(Locale.ROOT).matches(
                            Regex(".*\\.(jpg|jpeg|png|gif|webp|bmp|heic)$")
                        )
                }
                val nearby = images.take(6).map { it.path }  // 最初の6枚を先読み
                api.preloadImages(nearby)
            }
        }
    }

    private fun applyFilter(q: String) {
        val filtered = if (q.isBlank()) allFiles
        else allFiles.filter { it.name.contains(q, ignoreCase = true) }
        val sorted = when (prefs.defaultSort) {
            "NAME_DESC" -> filtered.sortedWith(
                compareBy<FileItem> { !it.directory }.thenByDescending { it.name.lowercase(Locale.ROOT) })
            "DATE_DESC" -> filtered.sortedWith(
                compareBy<FileItem> { !it.directory }.thenByDescending { it.modified })
            "DATE_ASC" -> filtered.sortedWith(
                compareBy<FileItem> { !it.directory }.thenBy { it.modified })
            "SIZE_DESC" -> filtered.sortedWith(
                compareBy<FileItem> { !it.directory }.thenByDescending { it.size })
            "SIZE_ASC" -> filtered.sortedWith(
                compareBy<FileItem> { !it.directory }.thenBy { it.size })
            else -> filtered.sortedWith(
                compareBy<FileItem> { !it.directory }.thenBy { it.name.lowercase(Locale.ROOT) })
        }
        adapter.submitList(sorted)
        val empty = sorted.isEmpty()
        emptyView.isVisible = empty
        rvFiles.isVisible = !empty
        tvEmpty.text = if (q.isBlank()) "このフォルダは空です" else "「$q」に一致なし"
    }

    private fun updateBreadcrumb(path: String) {
        breadcrumbBar.removeAllViews()
        addCrumb("/", "")
        var cum = ""
        for (p in path.split("/").filter { it.isNotBlank() }) {
            breadcrumbBar.addView(TextView(this).apply {
                text = "›"; setPadding(0, 8, 0, 8); setTextColor(Color.GRAY)
            })
            cum = if (cum.isEmpty()) p else "$cum/$p"
            addCrumb(p, cum)
        }
        breadcrumbScroll.post { breadcrumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun addCrumb(label: String, target: String) {
        breadcrumbBar.addView(TextView(this).apply {
            text = label; setPadding(16, 8, 16, 8); textSize = 14f
            setTextColor(if (target == currentPath) Color.parseColor("#3F51B5") else Color.DKGRAY)
            setOnClickListener { listFiles(target) }
        })
    }

    // ===== メニュー =====
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val sel = adapter.isSelectionMode()
        menu.findItem(R.id.action_search)?.isVisible = !sel
        menu.findItem(R.id.action_sort)?.isVisible = !sel
        menu.findItem(R.id.action_refresh)?.isVisible = !sel
        menu.findItem(R.id.action_trash)?.isVisible = !sel
        menu.findItem(R.id.action_settings)?.isVisible = !sel
        menu.findItem(R.id.action_delete)?.isVisible = sel
        menu.findItem(R.id.action_close_selection)?.isVisible = sel
        menu.findItem(R.id.action_server_status)?.isVisible = !sel
        menu.findItem(R.id.action_toggle_grid)?.isVisible = !sel
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_search -> { toggleSearch(); true }
        R.id.action_sort -> { showSortMenu(); true }
        R.id.action_refresh -> { listFiles(currentPath); true }
        R.id.action_trash -> { startActivity(Intent(this, TrashActivity::class.java)); true }
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        R.id.action_delete -> { deleteSelected(); true }
        R.id.action_server_status -> { showServerStatus(); true }
        R.id.action_toggle_grid -> { gridMode = !gridMode; prefs.gridMode = gridMode; applyLayoutMode(); true }
        R.id.action_close_selection -> { adapter.exitSelection(); updateToolbar(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun updateToolbar() {
        invalidateOptionsMenu()
        if (adapter.isSelectionMode()) {
            toolbar.title = "選択中 (${adapter.selectedItems().size})"
            fab.isVisible = false
        } else {
            toolbar.title = "PocketNAS"
            fab.isVisible = statusCard.visibility != View.VISIBLE && !isAppLocked()
        }
    }

    private fun toggleSearch() {
        if (searchBar.isVisible) {
            searchBar.isVisible = false
            etSearch.setText("")
            searchMode = false
            applyFilter("")
        } else {
            searchBar.isVisible = true
            etSearch.requestFocus()
            etSearch.setText("")
        }
    }

    private fun showSortMenu() {
        // ★Step8: 並び替えメニューをツールバー右上（ボタンの位置）に出す。
        // 以前は toolbar 全体がアンカーで左上に表示されていた
        val anchor = toolbar.findViewById<View>(R.id.action_sort) ?: toolbar
        val popup = PopupMenu(this, anchor, Gravity.END)
        val labels = arrayOf("名前 ↑", "名前 ↓", "新しい順", "古い順", "サイズ大", "サイズ小")
        val keys = arrayOf("NAME_ASC", "NAME_DESC", "DATE_DESC", "DATE_ASC", "SIZE_DESC", "SIZE_ASC")
        keys.forEachIndexed { i, k ->
            popup.menu.add(0, i, i, if (k == prefs.defaultSort) "✓ ${labels[i]}" else labels[i])
        }
        popup.setOnMenuItemClickListener { m ->
            prefs.defaultSort = keys[m.itemId]
            applyFilter(etSearch.text.toString())
            true
        }
        popup.show()
    }

    private fun showFabMenu() {
        val popup = PopupMenu(this, fab)
        popup.menu.add(0, 1, 0, "📤 ファイルをアップロード")
        popup.menu.add(0, 2, 1, "📁 フォルダを作成")
        popup.setOnMenuItemClickListener { m ->
            when (m.itemId) {
                1 -> uploadPicker.launch(arrayOf("*/*"))
                2 -> showCreateFolder()
            }
            true
        }
        popup.show()
    }

    private fun serverSearch(q: String) {
        searchMode = true
        progress.isVisible = true
        api.searchFiles(currentPath, q) { list ->
            progress.isVisible = false
            allFiles = list
            adapter.submitList(list.sortedWith(
                compareBy<FileItem> { !it.directory }.thenBy { it.name.lowercase(Locale.ROOT) }))
            emptyView.isVisible = list.isEmpty()
            rvFiles.isVisible = list.isNotEmpty()
            tvEmpty.text = if (list.isEmpty()) "「$q」に一致なし" else ""
            toolbar.subtitle = "🔍「$q」: ${list.size}件"
        }
    }

    private fun showCreateFolder() {
        val input = EditText(this).apply { hint = "フォルダ名" }
        MaterialAlertDialogBuilder(this)
            .setTitle("フォルダ作成")
            .setView(input)
            .setPositiveButton("作成") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton
                api.request("POST", "/api/folder?path=${enc(currentPath)}&name=${enc(name)}") { code, _ ->
                    toast(if (code == 201) "作成しました" else "失敗: $code")
                    listFiles(currentPath)
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showFileActions(item: FileItem) {
        val fav = prefs.isFavorite(item.path)
        // ★Step8: 項目ごとに表示メニューが変わる（zip解凍）ため、
        // ラベルと処理のペアで組み立てる
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions.add("👁 プレビュー" to { previewFile(item) })
        actions.add("💾 ダウンロード" to { beginDownload(item) })
        actions.add("📤 共有" to { shareFile(item) })
        actions.add((if (fav) "⭐ お気に入り解除" else "☆ お気に入り登録") to {
            prefs.toggleFavorite(item.path)
            toast(if (prefs.isFavorite(item.path)) "登録しました" else "解除しました")
            listFiles(currentPath)
        })
        actions.add("✏️ 名前変更" to { showRename(item) })
        actions.add("➡️ 移動" to { showMove(item) })
        actions.add("📋 コピー" to { showCopy(item) })
        if (!item.directory && item.name.lowercase(Locale.ROOT).endsWith(".zip")) {
            actions.add("🗜 解凍する" to { extractZip(item) })
        }
        actions.add("🗑 削除" to { confirmDelete(item) })
        actions.add("キャンセル" to {})
        MaterialAlertDialogBuilder(this)
            .setTitle(item.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.second?.invoke()
            }.show()
    }

    // ★Step8: ZIP解凍（サーバー側で「zip名」フォルダに展開する）
    private fun extractZip(item: FileItem) {
        val dialog = createProgressDialog("解凍中", item.name)
        api.request("POST", "/api/extract?path=${enc(item.path)}") { code, _ ->
            dialog.dismiss()
            toast(if (code == 200) "解凍しました" else "解凍失敗: $code")
            if (code == 200) listFiles(currentPath)
        }
    }

    // ★Step6: サーバーログイン（パスワード入力）
    private var loginDialogShowing = false

    private fun showServerLoginDialog() {
        if (loginDialogShowing) return
        loginDialogShowing = true
        val input = EditText(this).apply {
            hint = "サーバーパスワード"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("🔐 サーバーパスワードが必要です")
            .setView(input)
            .setPositiveButton("ログイン") { _, _ ->
                loginDialogShowing = false
                api.login(input.text.toString(), save = true) { ok ->
                    if (ok) {
                        toast("ログインしました")
                        listFiles(currentPath)
                    } else {
                        toast("パスワードが違います")
                    }
                }
            }
            .setNegativeButton("キャンセル") { _, _ -> loginDialogShowing = false }
            .setOnCancelListener { loginDialogShowing = false }
            .show()
    }

    // ★Step6: フォルダロック解除
    private var unlockDialogFolder: String? = null

    private fun showFolderUnlockDialog(folder: String) {
        if (unlockDialogFolder == folder) return
        unlockDialogFolder = folder
        val input = EditText(this).apply {
            hint = "フォルダのパスワード"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("🔐 「${folder.substringAfterLast('/')}」はロックされています")
            .setView(input)
            .setPositiveButton("解除") { _, _ ->
                unlockDialogFolder = null
                api.unlockFolder(folder, input.text.toString()) { ok ->
                    if (ok) {
                        toast("解除しました")
                        listFiles(currentPath)
                    } else {
                        toast("パスワードが違います")
                    }
                }
            }
            .setNegativeButton("キャンセル") { _, _ -> unlockDialogFolder = null }
            .setOnCancelListener { unlockDialogFolder = null }
            .show()
    }

    // ★Step6: サーバーパスワード設定/変更
    private fun showServerPasswordDialog() {
        val input = EditText(this).apply {
            hint = "新しいパスワード"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("サーバーパスワード設定")
            .setView(input)
            .setPositiveButton("設定") { _, _ ->
                val pw = input.text.toString()
                if (pw.isEmpty()) {
                    toast("パスワードを入力してください")
                    return@setPositiveButton
                }
                api.setServerPassword(pw) { ok ->
                    if (ok) {
                        prefs.serverPassword = pw
                        api.login(pw, save = false) { }
                        toast("サーバーパスワードを設定しました")
                    } else {
                        toast("設定失敗")
                    }
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ★Step6: フォルダパスワード設定/解除
    private fun showFolderPasswordDialog(path: String) {
        if (path.isEmpty()) {
            toast("ルートには設定できません")
            return
        }
        val input = EditText(this).apply {
            hint = "フォルダのパスワード"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("🔐 $path")
            .setView(input)
            .setPositiveButton("設定") { _, _ ->
                val pw = input.text.toString()
                if (pw.isEmpty()) {
                    toast("パスワードを入力してください")
                    return@setPositiveButton
                }
                api.setFolderPassword(path, pw) { ok ->
                    toast(if (ok) "フォルダパスワードを設定しました" else "設定失敗")
                }
            }
            .setNeutralButton("解除") { _, _ ->
                api.setFolderPassword(path, "") { ok ->
                    toast(if (ok) "フォルダパスワードを解除しました" else "解除失敗")
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ★Step6: アプリロック（指紋/PIN）。60秒以内の復帰では再認証しない
    private var appUnlocked = false
    private var lastBackgroundAt = 0L

    private fun isAppLocked(): Boolean =
        findViewById<View>(R.id.lockOverlay)?.visibility == View.VISIBLE

    private fun maybeLockApp() {
        if (!prefs.appLockEnabled) return
        val overlay = findViewById<View>(R.id.lockOverlay) ?: return
        if (overlay.visibility == View.VISIBLE) return
        if (appUnlocked &&
            System.currentTimeMillis() - lastBackgroundAt < 60_000
        ) return
        appUnlocked = false
        overlay.visibility = View.VISIBLE
        // ★Step9: ロック中は FAB を隠す（FAB は elevation が高く、
        // ロック用オーバーレイより手前に浮いて見えてしまうため）
        fab.isVisible = false
        showBiometricPrompt()
    }

    private fun unlockApp() {
        appUnlocked = true
        lastBackgroundAt = System.currentTimeMillis()
        findViewById<View>(R.id.lockOverlay)?.visibility = View.GONE
        // ★Step9: FAB の表示を通常ルールに戻し、保留中の共有アップロードがあれば再開する
        updateToolbar()
        tryStartShareUpload()
    }

    private fun showBiometricPrompt() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val can = BiometricManager.from(this).canAuthenticate(authenticators)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            // 端末に画面ロック等が無い場合は締め出さない
            unlockApp()
            return
        }
        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    unlockApp()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    toast("認証エラー: $errString")
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Pocket NAS")
            .setSubtitle("指紋または画面ロックで認証してください")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }

    override fun onResume() {
        super.onResume()
        maybeLockApp()
    }

    override fun onStop() {
        lastBackgroundAt = System.currentTimeMillis()
        super.onStop()
    }

    private fun showRename(item: FileItem) {
        val input = EditText(this).apply { setText(item.name); setSelection(item.name.length) }
        MaterialAlertDialogBuilder(this).setTitle("名前変更").setView(input)
            .setPositiveButton("変更") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank() || name == item.name) return@setPositiveButton
                api.request("POST", "/api/rename?path=${enc(item.path)}&name=${enc(name)}") { code, _ ->
                    toast(if (code == 200) "変更しました" else "失敗: $code")
                    listFiles(currentPath)
                }
            }.setNegativeButton("キャンセル", null).show()
    }

    private fun showMove(item: FileItem) {
        pickFolder("移動先フォルダ", currentPath) { dst ->
            val newPath = if (dst.isBlank()) item.name else "$dst/${item.name}"
            if (newPath == item.path) { toast("同じ場所"); return@pickFolder }
            api.request("POST", "/api/move?from=${enc(item.path)}&to=${enc(newPath)}") { code, _ ->
                toast(if (code == 200) "移動しました" else "失敗: $code")
                listFiles(currentPath)
            }
        }
    }

    private fun showCopy(item: FileItem) {
        pickFolder("コピー先フォルダ", currentPath) { dst ->
            val newPath = if (dst.isBlank() || dst == currentPath)
                "copy_${item.name}" else "$dst/${item.name}"
            api.request("POST", "/api/copy?from=${enc(item.path)}&to=${enc(newPath)}") { code, _ ->
                toast(if (code == 200) "コピーしました" else "失敗: $code")
                listFiles(currentPath)
            }
        }
    }

    private fun pickFolder(title: String, start: String, cb: (String) -> Unit) {
        var cur = start
        val items = mutableListOf<Pair<String, () -> Unit>>()
        val listView = ListView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        val pathLabel = TextView(this).apply {
            text = "/"; textSize = 12f; setPadding(0, 8, 0, 8); setTextColor(Color.GRAY)
        }
        container.addView(pathLabel)
        container.addView(listView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (360 * resources.displayMetrics.density).toInt()))
        lateinit var dialog: AlertDialog
        fun reload() {
            pathLabel.text = if (cur.isBlank()) "/" else "/$cur"
            items.clear()
            items.add("✅ このフォルダに決定" to { dialog.dismiss(); cb(cur) })
            if (cur.isNotEmpty()) items.add("⬆ 上のフォルダ" to {
                cur = cur.substringBeforeLast("/", ""); reload()
            })
            api.listFiles(cur) { list ->
                for (f in list) if (f.directory) {
                    items.add("📁 ${f.name}" to { cur = f.path; reload() })
                }
                listView.adapter = ArrayAdapter(this@MainActivity,
                    android.R.layout.simple_list_item_1, items.map { it.first })
                listView.setOnItemClickListener { _, _, pos, _ ->
                    items.getOrNull(pos)?.second?.invoke()
                }
            }
        }
        dialog = MaterialAlertDialogBuilder(this).setTitle(title).setView(container)
            .setNegativeButton("キャンセル", null).create()
        dialog.show(); reload()
    }

    private fun confirmDelete(item: FileItem) {
        MaterialAlertDialogBuilder(this).setTitle("削除")
            .setMessage("「${item.name}」を${if (prefs.useTrash) "ゴミ箱へ移動" else "完全削除"}しますか？")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("削除") { _, _ ->
                val q = if (prefs.useTrash) "&trash=true" else ""
                api.request("DELETE", "/api/files?path=${enc(item.path)}$q") { code, _ ->
                    toast(if (code == 200) "削除しました" else "失敗: $code")
                    listFiles(currentPath)
                }
            }.show()
    }

    private fun deleteSelected() {
        val items = adapter.selectedItems()
        if (items.isEmpty()) return
        MaterialAlertDialogBuilder(this).setTitle("一括削除")
            .setMessage("${items.size}件を削除しますか？")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("削除") { _, _ ->
                val q = if (prefs.useTrash) "&trash=true" else ""
                var doneCount = 0; var failed = 0
                for (item in items) {
                    api.request("DELETE", "/api/files?path=${enc(item.path)}$q") { code, _ ->
                        if (code != 200) failed++
                        doneCount++
                        if (doneCount == items.size) {
                            adapter.exitSelection(); updateToolbar()
                            Snackbar.make(rvFiles,
                                if (failed == 0) "削除しました" else "${failed}件失敗",
                                Snackbar.LENGTH_SHORT).show()
                            listFiles(currentPath)
                        }
                    }
                }
            }.show()
    }

    private fun previewFile(item: FileItem) {
        val lower = item.name.lowercase(Locale.ROOT)
        when {
            lower.matches(Regex(".*\\.(jpg|jpeg|png|gif|webp|bmp|heic)$")) -> {
                // ★Step3: 表示中リストの画像だけを抜き出して丸ごと渡す（ビューア側でスワイプ切替）
                val imageRegex = Regex(".*\\.(jpg|jpeg|png|gif|webp|bmp|heic)$")
                val images = adapter.currentList.filter {
                    !it.directory && it.name.lowercase(Locale.ROOT).matches(imageRegex)
                }
                val startIndex = images.indexOfFirst { it.path == item.path }.coerceAtLeast(0)
                startActivity(Intent(this, ImageViewerActivity::class.java).apply {
                    putExtra(ImageViewerActivity.EXTRA_PATH, item.path)
                    putExtra(ImageViewerActivity.EXTRA_NAME, item.name)
                    putStringArrayListExtra(
                        ImageViewerActivity.EXTRA_PATHS, ArrayList(images.map { it.path }))
                    putStringArrayListExtra(
                        ImageViewerActivity.EXTRA_NAMES, ArrayList(images.map { it.name }))
                    putExtra(
                        ImageViewerActivity.EXTRA_MODIFIEDS, images.map { it.modified }.toLongArray())
                    putExtra(ImageViewerActivity.EXTRA_INDEX, startIndex)
                })
                prefs.pushRecent(item.path)
            }
            lower.matches(Regex(".*\\.(mp4|mkv|webm|m4v|mov|3gp)$")) -> {
                // ★Step7: 表示中リストの動画だけを抜き出して丸ごと渡す（プレイヤー側で前後切替）
                val videoRegex = Regex(".*\\.(mp4|mkv|webm|m4v|mov|3gp)$")
                val videos = adapter.currentList.filter {
                    !it.directory && it.name.lowercase(Locale.ROOT).matches(videoRegex)
                }
                val startIndex = videos.indexOfFirst { it.path == item.path }.coerceAtLeast(0)
                startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
                    putExtra(VideoPlayerActivity.EXTRA_PATH, item.path)
                    putExtra(VideoPlayerActivity.EXTRA_NAME, item.name)
                    putStringArrayListExtra(
                        VideoPlayerActivity.EXTRA_PATHS, ArrayList(videos.map { it.path }))
                    putStringArrayListExtra(
                        VideoPlayerActivity.EXTRA_NAMES, ArrayList(videos.map { it.name }))
                    putExtra(VideoPlayerActivity.EXTRA_INDEX, startIndex)
                })
                prefs.pushRecent(item.path)
            }
            // ★Step4: PDFビューア
            lower.matches(Regex(".*\\.pdf$")) -> {
                startActivity(Intent(this, PdfViewerActivity::class.java).apply {
                    putExtra(PdfViewerActivity.EXTRA_PATH, item.path)
                    putExtra(PdfViewerActivity.EXTRA_NAME, item.name)
                    putExtra(PdfViewerActivity.EXTRA_MODIFIED, item.modified)
                })
                prefs.pushRecent(item.path)
            }
            // ★Step4: 音楽プレイヤー（フォルダ内をプレイリスト化）
            lower.matches(Regex(".*\\.(mp3|wav|flac|ogg|m4a|aac)$")) -> {
                val audioRegex = Regex(".*\\.(mp3|wav|flac|ogg|m4a|aac)$")
                val audios = adapter.currentList.filter {
                    !it.directory && it.name.lowercase(Locale.ROOT).matches(audioRegex)
                }
                val startIndex = audios.indexOfFirst { it.path == item.path }.coerceAtLeast(0)
                startActivity(Intent(this, AudioPlayerActivity::class.java).apply {
                    putExtra(AudioPlayerActivity.EXTRA_PATH, item.path)
                    putExtra(AudioPlayerActivity.EXTRA_NAME, item.name)
                    putStringArrayListExtra(
                        AudioPlayerActivity.EXTRA_PATHS, ArrayList(audios.map { it.path }))
                    putStringArrayListExtra(
                        AudioPlayerActivity.EXTRA_NAMES, ArrayList(audios.map { it.name }))
                    putExtra(AudioPlayerActivity.EXTRA_INDEX, startIndex)
                })
                prefs.pushRecent(item.path)
            }
            lower.matches(Regex(".*\\.(txt|md|log|json|csv|xml|kt|java|py|js|ts|html|css|sh|yaml|yml|toml)$")) -> {
                // ★B4: mtime ベースのファイル名で再利用
                val tmp = File(cacheDir, "txt_${item.path.hashCode()}_${item.modified}")
                api.download(item.path, tmp) { ok, _ ->
                    if (!ok) { toast("取得失敗"); return@download }
                    val text = tmp.readText(Charsets.UTF_8).take(200_000)
                    val tv = TextView(this).apply {
                        this.text = text; textSize = 13f
                        setPadding(24, 16, 24, 16)
                        typeface = android.graphics.Typeface.MONOSPACE
                        setTextIsSelectable(true)
                    }
                    MaterialAlertDialogBuilder(this).setTitle(item.name)
                        .setView(ScrollView(this).apply { addView(tv) })
                        .setPositiveButton("閉じる", null).show()
                }
            }
            else -> toast("この形式はプレビュー非対応です。ダウンロードしてください")
        }
    }

    private fun beginDownload(item: FileItem) {
        pendingDownloadPath = item.path
        pendingDownloadName = item.name
        savePicker.launch(item.name)
    }

    private fun saveDownloaded(uri: Uri) {
        val item = FileItem(pendingDownloadName, pendingDownloadPath, false, 0, 0)
        val tmp = File(cacheDir, "dl_${System.currentTimeMillis()}")
        val dialog = createProgressDialog("ダウンロード中", item.name)
        api.download(
            path = item.path, outFile = tmp,
            onProgress = { recv, total, speed ->
                if (total > 0) updateProgress(dialog, ((recv * 100) / total).toInt(),
                    "${formatSize(recv)} / ${formatSize(total)}", speed)
                else updateProgress(dialog, -1, "${formatSize(recv)} 取得済み", speed)
            }
        ) { ok, err ->
            dialog.dismiss()
            if (!ok) { toast("取得失敗: $err"); return@download }
            Thread {
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        tmp.inputStream().use { it.copyTo(out) }
                    } ?: throw java.io.IOException("保存先エラー")
                    runOnUiThread { toast("保存しました") }
                } catch (e: Exception) {
                    runOnUiThread { toast("保存失敗: ${e.message}") }
                } finally {
                    // ★F5: 一時ファイル自動削除
                    tmp.delete()
                }
            }.start()
        }
    }

    private fun shareFile(item: FileItem) {
        val safeName = item.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val tmp = File(cacheDir, "sh_${System.currentTimeMillis()}_$safeName")
        val dialog = createProgressDialog("準備中", item.name)
        api.download(item.path, tmp, onProgress = { _, _, _ -> }) { ok, _ ->
            dialog.dismiss()
            if (!ok) { toast("取得失敗"); return@download }
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", tmp)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "共有"))
            } catch (e: Exception) {
                toast("共有失敗: ${e.message}")
            }
        }
    }

    // ★Step9: destPath と onDone を追加（共有アップロードで宛先指定＆連続処理するため）。
    // 既存の呼び出し（ファイル選択からのアップロード）は引数なしで従来どおり動く
    private fun uploadFile(uri: Uri, destPath: String? = null, onDone: (() -> Unit)? = null) {
        val dest = destPath ?: currentPath
        val name = queryName(uri) ?: run {
            toast("ファイル名取得失敗")
            onDone?.invoke()
            return
        }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val tmp = File(cacheDir, "up_${System.currentTimeMillis()}_$safeName")
        val dialog = createProgressDialog("アップロード中", name)
        Thread {
            try {
                contentResolver.openInputStream(uri)?.use { i ->
                    tmp.outputStream().use { o -> i.copyTo(o) }
                } ?: throw java.io.IOException("読み込み失敗")
                runOnUiThread {
                    api.upload(
                        file = tmp, path = dest, name = name,
                        onProgress = { sent, total, speed ->
                            if (total > 0) updateProgress(dialog, ((sent * 100) / total).toInt(),
                                "${formatSize(sent)} / ${formatSize(total)}", speed)
                            else updateProgress(dialog, -1, "${formatSize(sent)} 送信済み", speed)
                        }
                    ) { ok, err ->
                        dialog.dismiss()
                        tmp.delete()
                        if (ok) {
                            toast("アップロード完了")
                            if (dest == currentPath) listFiles(currentPath)
                        } else toast("失敗: $err")
                        onDone?.invoke()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dialog.dismiss()
                    tmp.delete()
                    toast("失敗: ${e.message}")
                    onDone?.invoke()
                }
            }
        }.start()
    }

    private fun queryName(uri: Uri): String? = runCatching {
        contentResolver.query(uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i < 0) return@use null
            c.getString(i)?.trim()?.takeIf { it.isNotBlank() && !it.contains('/') }
        }
    }.getOrNull()

    private fun createProgressDialog(title: String, msg: String): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(R.layout.dialog_progress)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT)
        d.setCancelable(false)
        d.findViewById<TextView>(R.id.tvTitle).text = title
        d.findViewById<TextView>(R.id.tvMessage).text = msg
        d.findViewById<ProgressBar>(R.id.progressBar).isIndeterminate = true
        d.show()
        return d
    }

    private fun updateProgress(d: Dialog, pct: Int, msg: String, speedBps: Long = 0) {
        if (!d.isShowing) return
        val speedStr = if (speedBps > 0) " • %.1f MB/s".format(speedBps / (1024.0 * 1024.0)) else ""
        d.findViewById<TextView>(R.id.tvMessage)?.text = "$msg$speedStr"
        d.findViewById<ProgressBar>(R.id.progressBar)?.let {
            if (pct < 0) it.isIndeterminate = true
            else { it.isIndeterminate = false; it.progress = pct }
        }
    }

    @Deprecated("Deprecated")
    override fun onBackPressed() {
        if (adapter.isSelectionMode()) { adapter.exitSelection(); updateToolbar(); return }
        if (searchBar.isVisible) { toggleSearch(); return }
        if (currentPath.isNotEmpty()) {
            listFiles(currentPath.substringBeforeLast('/', "")); return
        }
        super.onBackPressed()
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
    private fun toast(v: String) = Toast.makeText(this, v, Toast.LENGTH_LONG).show()
    private fun formatSize(v: Long): String = when {
        v >= 1L shl 30 -> "%.2f GB".format(v / (1024.0 * 1024 * 1024))
        v >= 1L shl 20 -> "%.1f MB".format(v / (1024.0 * 1024))
        v >= 1L shl 10 -> "%.1f KB".format(v / 1024.0)
        else -> "$v B"
    }

    override fun onPause() { super.onPause(); nfcReader?.stop() }

    override fun onDestroy() {
        runCatching { discovery?.let { nsd?.stopServiceDiscovery(it) } }
        runCatching { multicast?.let { if (it.isHeld) it.release() } }
        nfcReader?.stop()
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        super.onDestroy()
    }
}