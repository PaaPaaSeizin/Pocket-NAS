package jp.pocketnas.server

import java.io.BufferedInputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import android.app.*
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.*
import androidx.documentfile.provider.DocumentFile
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class Entry(
    val name: String, val path: String, val directory: Boolean,
    val size: Long, val modified: Long
)
@Serializable data class DeviceStatus(
    val online: Boolean, val model: String, val android: Int,
    val freeMemory: Long, val totalMemory: Long,
    val batteryPercent: Int, val batteryTempC: Float?,
    val uptimeSeconds: Long, val storageName: String,
    // P7: health 情報も統合
    val activeRequests: Int = 0,
    val logSize: Int = 0,
    val boundIp: String = ""
)
@Serializable data class LogEntry(
    val time: Long, val method: String, val path: String, val remote: String
)
@Serializable data class TrashItem(
    val name: String, val original: String,
    val trashedAt: Long, val size: Long, val directory: Boolean
)
@Serializable data class StorageInfo(val free: Long, val total: Long, val name: String)
@Serializable data class IndexStatus(
    val built: Boolean, val count: Long, val lastFullScanAt: Long,
    val scanning: Boolean, val lastScanCount: Int, val lastError: String?
)
@Serializable data class AuthBody(val password: String = "", val path: String = "")
@Serializable data class AuthStatus(val serverPasswordSet: Boolean, val lockedFolders: List<String> = emptyList())
@Serializable data class AuthTokenResult(val token: String, val path: String = "")
@Serializable data class HealthDetail(
    val uptimeSeconds: Long, val freeMemory: Long, val totalMemory: Long,
    val activeRequests: Int, val logSize: Int, val boundIp: String, val port: Int
)

class NasServerService : Service() {
    companion object {
        @Volatile var running = false
        private const val TAG = "PocketNAS"
        private const val CHANNEL_ID = "pocketnas"
        private const val NOTIF_ID = 9
    }

    private var stopServer: (() -> Unit)? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var networkMonitor: NetworkMonitor? = null
    private val alive = AtomicBoolean(false)
    private val activeRequests = AtomicInteger(0)        // ★F6

    private lateinit var prefs: ServerPrefs
    private lateinit var trash: TrashManager
    private val logs = ArrayDeque<LogEntry>(200)
    private val logsLock = Any()
    @Volatile private var boundIp: String = "0.0.0.0"

    // ★P2: ディレクトリインデックス
    private val dirCache = DirIndexCache()

    // ★Index: 永続インデックス（SQLite）
    private val fileIndex by lazy { FileIndex(this) }

    private val findCache = object : LinkedHashMap<String, DocumentFile>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DocumentFile>?) =
            size > 128
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> {
                WatchdogReceiver.cancel(this)
                stopSelf()
                return START_NOT_STICKY
            }
            "REFRESH_ADVERTISE" -> { readvertise(); return START_STICKY }
        }
        if (alive.compareAndSet(false, true)) startNas()
        WatchdogReceiver.schedule(this)
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        prefs = ServerPrefs(this)
        trash = TrashManager(this)
        Thread.setDefaultUncaughtExceptionHandler(
            CrashHandler(this, Thread.getDefaultUncaughtExceptionHandler())
        )
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "PocketNAS", NotificationManager.IMPORTANCE_LOW)
            )
        }
        updateNotification()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restart = Intent(applicationContext, NasServerService::class.java).setAction("START")
        try { startForegroundService(restart) } catch (_: Exception) {}
        super.onTaskRemoved(rootIntent)
    }

    private fun updateNotification() {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, NasServerService::class.java).setAction("STOP"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("PocketNAS 稼働中")
            .setContentText("http://$boundIp:8080")
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "停止", stopIntent).build())
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, n)
    }

    private fun wifiIp(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.name.startsWith("wlan") && it.isUp }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull()?.hostAddress
    } catch (_: Exception) { null }

    private fun startNas() {
        running = true
        val root = prefs.treeUri?.let { DocumentFile.fromTreeUri(this, android.net.Uri.parse(it)) }
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        boundIp = wifiIp() ?: "0.0.0.0"
        FileLogger.log(this, TAG, "Binding to $boundIp:8080")

        // ★Index: 充電中＋アイドル時の定期フルスキャンを登録し、未構築/古い場合は今すぐ裏で作る
        try { IndexWorker.schedule(this) } catch (e: Exception) {
            FileLogger.log(this, TAG, "index schedule failed: ${e.message}")
        }
        val indexAge = System.currentTimeMillis() - fileIndex.lastFullScanAt()
        if (!fileIndex.isBuilt() || indexAge > 7 * 24 * 60 * 60 * 1000L) {
            IndexScanner.startFullScan(this)
        }

        // ★Index: API経由の変更をインデックスへ差分反映するヘルパー
        val refreshIndexDir: (String) -> Unit = { parentPath ->
            synchronized(findCache) { findCache.clear() }
            val rr = root
            if (rr != null) {
                val dirDoc = resolve(rr, parentPath)
                if (dirDoc != null && dirDoc.isDirectory) {
                    IndexScanner.refreshDirectory(this, parentPath, dirDoc)
                }
            }
        }
        val refreshIndexSubtree: (String) -> Unit = { dirPath ->
            val rr = root
            if (rr != null) {
                val dirDoc = resolve(rr, dirPath)
                if (dirDoc != null && dirDoc.isDirectory) {
                    IndexScanner.refreshSubtree(this, dirPath, dirDoc)
                }
            }
        }

        val server = embeddedServer(CIO, host = boundIp, port = 8080) {
            install(ContentNegotiation) { json(json) }
            install(Compression) { gzip() }   // ★P9: 全レスポンス自動gzip

            intercept(ApplicationCallPipeline.Setup) {
                val t0 = System.currentTimeMillis()
                val remote = call.request.local.remoteHost
                activeRequests.incrementAndGet()
                try { proceed() } finally {
                    activeRequests.decrementAndGet()
                    synchronized(logsLock) {
                        if (logs.size >= 200) logs.removeFirst()
                        logs.addLast(LogEntry(t0, call.request.httpMethod.value,
                            call.request.uri, remote))
                    }
                }
            }

            // ★Step6: アクセスパスワードのチェック（全体＋フォルダごと）
            intercept(ApplicationCallPipeline.Plugins) {
                val reqPath = call.request.path()
                val isPublic = reqPath == "/" || reqPath == "/health" ||
                    reqPath == "/api/status" || reqPath.startsWith("/api/auth/")
                if (!isPublic && reqPath.startsWith("/api/")) {
                    val ctx = this@NasServerService
                    if (AuthStore.isServerPasswordSet(ctx) &&
                        !AuthStore.validSession(sessionTokenOf(call))
                    ) {
                        call.respondText("UNAUTHORIZED", status = HttpStatusCode.Unauthorized)
                        finish()
                        return@intercept
                    }
                    val ftoks = folderTokensOf(call)
                    val targets = listOfNotNull(
                        call.request.queryParameters["path"],
                        call.request.queryParameters["from"],
                        call.request.queryParameters["to"]
                    )
                    for (target in targets) {
                        if (target.isEmpty()) continue
                        val locked = AuthStore.lockedAncestorOf(ctx, target) ?: continue
                        if (!AuthStore.folderAccessAllowed(ftoks, target)) {
                            call.respondText(
                                "FOLDER_LOCKED:$locked", status = HttpStatusCode.Forbidden
                            )
                            finish()
                            return@intercept
                        }
                    }
                }
            }

            routing {
                get("/health") { call.respondText("PocketNAS OK") }

                get("/") {
                    try {
                        val html = resources.openRawResource(R.raw.webui)
                            .bufferedReader().use { it.readText() }
                        call.respondText(html, ContentType.Text.Html)
                    } catch (e: Exception) {
                        call.respondText("Web UI を読み込めません: ${e.message}",
                            status = HttpStatusCode.InternalServerError)
                    }
                }

                get("/api/status") {
                    val mi = android.app.ActivityManager.MemoryInfo()
                    (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
                    val b = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val level = b?.getIntExtra("level", -1) ?: -1
                    val scale = b?.getIntExtra("scale", 100) ?: 100
                    val temp = b?.getIntExtra("temperature", -1)?.takeIf { it >= 0 }?.div(10f)
                    call.respond(DeviceStatus(
                        online = true, model = Build.MODEL ?: "Android",
                        android = Build.VERSION.SDK_INT,
                        freeMemory = mi.availMem, totalMemory = mi.totalMem,
                        batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
                        batteryTempC = temp,
                        uptimeSeconds = SystemClock.elapsedRealtime() / 1000,
                        storageName = StorageUtils.name(this@NasServerService, root),
                        activeRequests = activeRequests.get(),
                        logSize = synchronized(logsLock) { logs.size },
                        boundIp = boundIp
                    ))
                }

                get("/api/health/detail") {
                    val mi = android.app.ActivityManager.MemoryInfo()
                    (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
                    call.respond(HealthDetail(
                        uptimeSeconds = SystemClock.elapsedRealtime() / 1000,
                        freeMemory = mi.availMem, totalMemory = mi.totalMem,
                        activeRequests = activeRequests.get(),
                        logSize = synchronized(logsLock) { logs.size },
                        boundIp = boundIp, port = 8080
                    ))
                }

                // ★Step6: 認証API
                get("/api/auth/status") {
                    val ctx = this@NasServerService
                    val set = AuthStore.isServerPasswordSet(ctx)
                    val authed = !set || AuthStore.validSession(sessionTokenOf(call))
                    call.respond(
                        AuthStatus(
                            serverPasswordSet = set,
                            lockedFolders = if (authed) AuthStore.lockedFolders(ctx) else emptyList()
                        )
                    )
                }

                post("/api/auth/login") {
                    val ctx = this@NasServerService
                    val body = try { call.receive<AuthBody>() } catch (e: Exception) { AuthBody() }
                    if (AuthStore.isServerPasswordSet(ctx) &&
                        !AuthStore.checkServerPassword(ctx, body.password)
                    ) {
                        call.respondText("UNAUTHORIZED", status = HttpStatusCode.Unauthorized); return@post
                    }
                    val token = AuthStore.createSession()
                    call.response.cookies.append(
                        "pn_token", token, path = "/", maxAge = 7L * 24 * 3600, httpOnly = true
                    )
                    call.respond(AuthTokenResult(token = token))
                }

                post("/api/auth/logout") {
                    AuthStore.revokeSession(sessionTokenOf(call))
                    call.response.cookies.append("pn_token", "", path = "/", maxAge = 0L)
                    call.respondText("OK")
                }

                post("/api/auth/set-password") {
                    val ctx = this@NasServerService
                    if (AuthStore.isServerPasswordSet(ctx) &&
                        !AuthStore.validSession(sessionTokenOf(call))
                    ) {
                        call.respondText("UNAUTHORIZED", status = HttpStatusCode.Unauthorized); return@post
                    }
                    val body = try { call.receive<AuthBody>() } catch (e: Exception) { AuthBody() }
                    AuthStore.setServerPassword(ctx, body.password)
                    call.respondText("OK")
                }

                post("/api/auth/folder/set") {
                    val ctx = this@NasServerService
                    if (AuthStore.isServerPasswordSet(ctx) &&
                        !AuthStore.validSession(sessionTokenOf(call))
                    ) {
                        call.respondText("UNAUTHORIZED", status = HttpStatusCode.Unauthorized); return@post
                    }
                    val body = try { call.receive<AuthBody>() } catch (e: Exception) { AuthBody() }
                    if (body.path.isEmpty()) {
                        call.respondText("Bad Request", status = HttpStatusCode.BadRequest); return@post
                    }
                    AuthStore.setFolderPassword(ctx, body.path, body.password)
                    call.respondText("OK")
                }

                post("/api/auth/folder/unlock") {
                    val ctx = this@NasServerService
                    if (AuthStore.isServerPasswordSet(ctx) &&
                        !AuthStore.validSession(sessionTokenOf(call))
                    ) {
                        call.respondText("UNAUTHORIZED", status = HttpStatusCode.Unauthorized); return@post
                    }
                    val body = try { call.receive<AuthBody>() } catch (e: Exception) { AuthBody() }
                    if (!AuthStore.checkFolderPassword(ctx, body.path, body.password)) {
                        call.respondText("UNAUTHORIZED", status = HttpStatusCode.Unauthorized); return@post
                    }
                    val token = AuthStore.createFolderToken(body.path)
                    val existing = call.request.cookies["pn_ft"]
                        ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                    val combined = (existing + token).joinToString(",")
                    call.response.cookies.append(
                        "pn_ft", combined, path = "/", maxAge = 7L * 24 * 3600, httpOnly = true
                    )
                    call.respond(AuthTokenResult(token = token, path = body.path))
                }

                // ★P1: サムネイル生成API
                get("/api/thumbnail") {
                    val path = call.request.queryParameters["path"].orEmpty()
                    val size = (call.request.queryParameters["size"]?.toIntOrNull() ?: 200)
                        .coerceIn(64, 1024)
                    val doc = root?.let { resolve(it, path) }
                    if (doc == null || !doc.isFile) {
                        call.respondText("Not Found", status = HttpStatusCode.NotFound); return@get
                    }
                    val file = ThumbnailCache.get(this@NasServerService, doc, size)
                    if (file == null) {
                        call.respondText("生成失敗", status = HttpStatusCode.InternalServerError); return@get
                    }
                    call.response.header("Cache-Control", "public, max-age=86400")
                    call.respondFile(file)
                }

                // ★Step5: Web UI 用 PDF ページ画像化API
                get("/api/pdf/info") {
                    val path = call.request.queryParameters["path"].orEmpty()
                    val doc = root?.let { resolve(it, path) }
                    if (doc == null || !doc.isFile) {
                        call.respondText("Not Found", status = HttpStatusCode.NotFound); return@get
                    }
                    val n = PdfPageCache.pageCount(this@NasServerService, doc)
                    if (n == null) {
                        call.respondText("生成失敗", status = HttpStatusCode.InternalServerError); return@get
                    }
                    call.respondText("{\"pages\":$n}", ContentType.Application.Json)
                }

                get("/api/pdf/page") {
                    val path = call.request.queryParameters["path"].orEmpty()
                    val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
                    val width = (call.request.queryParameters["width"]?.toIntOrNull() ?: 1200)
                        .coerceIn(400, 2000)
                    val doc = root?.let { resolve(it, path) }
                    if (doc == null || !doc.isFile) {
                        call.respondText("Not Found", status = HttpStatusCode.NotFound); return@get
                    }
                    val file = PdfPageCache.page(this@NasServerService, doc, page, width)
                    if (file == null) {
                        call.respondText("生成失敗", status = HttpStatusCode.InternalServerError); return@get
                    }
                    call.response.header("Cache-Control", "public, max-age=86400")
                    call.respondFile(file)
                }

                get("/api/files") {
                    val r = root ?: run {
                        call.respondText("保存先未選択", status = HttpStatusCode.PreconditionFailed); return@get
                    }
                    val basePath = call.request.queryParameters["path"].orEmpty()

                    // ★Index: 構築済みなら DB から返す（SAF スキャンなし）
                    // インデックスに無いフォルダ（外部で作られた等）は従来スキャンにフォールバック。
                    // ★Step2: ファイルのパスが渡された場合は従来どおり 404 を返す（旧: 空リスト200）
                    if (fileIndex.isBuilt()) {
                        if (basePath.isEmpty()) {
                            call.respond(fileIndex.children("").map { it.toEntry() }); return@get
                        }
                        val indexed = fileIndex.find(basePath)
                        if (indexed != null) {
                            if (!indexed.directory) {
                                call.respondText("Not Found", status = HttpStatusCode.NotFound); return@get
                            }
                            call.respond(fileIndex.children(basePath).map { it.toEntry() }); return@get
                        }
                    }

                    // ★P2: キャッシュヒットなら即返す
                    dirCache.get(basePath)?.let { cached ->
                        call.respond(cached.map { it.toEntry() }); return@get
                    }

                    val dir = resolve(r, basePath)
                    if (dir == null || !dir.isDirectory) {
                        call.respondText("Not Found", status = HttpStatusCode.NotFound); return@get
                    }
                    val list = dir.listFiles()
                        .asSequence()
                        .filter { it.name != null && it.name != TrashManager.TRASH_DIR_NAME }
                        .map { f ->
                            val p = if (basePath.isEmpty()) f.name!! else "$basePath/${f.name}"
                            DirIndexCache.EntryItem(f.name!!, p, f.isDirectory, f.length(), f.lastModified())
                        }
                        .sortedWith(compareByDescending<DirIndexCache.EntryItem> { it.directory }
                            .thenBy { it.name.lowercase() })
                        .toList()

                    dirCache.put(basePath, list)
                    call.respond(list.map { it.toEntry() })
                }

                post("/api/folder") {
                    val dir = root?.let { resolve(it, call.request.queryParameters["path"].orEmpty()) }
                    val name = call.request.queryParameters["name"].orEmpty()
                    val parentPath = call.request.queryParameters["path"].orEmpty()
                    if (dir == null || !dir.isDirectory || !safe(name)) {
                        call.respondText("Bad Request", status = HttpStatusCode.BadRequest); return@post
                    }
                    val ok = dir.findFile(name) == null && dir.createDirectory(name) != null
                    if (ok) { dirCache.invalidate(parentPath); refreshIndexDir(parentPath) }
                    call.respondText("", status = if (ok) HttpStatusCode.Created else HttpStatusCode.Conflict)
                }

                post("/api/rename") {
                    val path = call.request.queryParameters["path"].orEmpty()
                    val doc = root?.let { resolve(it, path) }
                    val name = call.request.queryParameters["name"].orEmpty()
                    if (doc == null || !safe(name)) {
                        call.respondText("Bad Request", status = HttpStatusCode.BadRequest); return@post
                    }
                    val ok = doc.renameTo(name)
                    if (ok) {
                        dirCache.invalidatePrefix(path.substringBeforeLast("/", ""))
                        refreshIndexDir(path.substringBeforeLast("/", ""))
                    }
                    call.respondText("", status = if (ok) HttpStatusCode.OK else HttpStatusCode.Conflict)
                }

                delete("/api/files") {
                    val r = root
                    val path = call.request.queryParameters["path"].orEmpty()
                    val doc = r?.let { resolve(it, path) }
                    if (doc == null || doc.uri == r?.uri) {
                        call.respondText("Bad Request", status = HttpStatusCode.BadRequest); return@delete
                    }
                    val toTrash = call.request.queryParameters["trash"] == "true"
                    val ok = if (toTrash) trash.moveToTrash(r, doc) != null else doc.delete()
                    if (ok) {
                        dirCache.invalidatePrefix(path.substringBeforeLast("/", ""))
                        StorageCache.invalidate()
                        fileIndex.removeSubtree(path)
                        refreshIndexDir(path.substringBeforeLast("/", ""))
                    }
                    call.respondText("", status = if (ok) HttpStatusCode.OK else HttpStatusCode.InternalServerError)
                }

                post("/api/upload") {
                    val basePath = call.request.queryParameters["path"].orEmpty()
                    val dir = root?.let { resolve(it, basePath) }
                    val name = call.request.queryParameters["name"].orEmpty()
                    if (dir == null || !dir.isDirectory || !safe(name) ||
                        name.length > 240 || name.any { it.isISOControl() }
                    ) {
                        call.respondText("Bad Request", status = HttpStatusCode.BadRequest); return@post
                    }
                    if (dir.findFile(name) != null) {
                        call.respondText("同名ファイルあり", status = HttpStatusCode.Conflict); return@post
                    }
                    val ext = name.substringAfterLast(".", "").lowercase()
                    val mime = android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                    val doc = try { dir.createFile(mime, name) } catch (e: Exception) {
                        FileLogger.log(this@NasServerService, TAG, "create failed: ${e.message}"); null
                    } ?: run {
                        call.respondText("作成失敗", status = HttpStatusCode.InternalServerError); return@post
                    }
                    try {
                        val ch = call.receiveChannel()
                        contentResolver.openOutputStream(doc.uri, "w")?.use { out ->
                            val buf = ByteArray(1024 * 1024)
                            while (true) {
                                val n = ch.readAvailable(buf, 0, buf.size)
                                if (n < 0) break
                                if (n > 0) out.write(buf, 0, n)
                                else if (ch.isClosedForRead) break
                            }
                            out.flush()
                        } ?: throw java.io.IOException("保存先を開けません")
                        if ((doc.name ?: "") != name) {
                            if (!doc.renameTo(name)) throw java.io.IOException("リネーム失敗")
                        }
                        dirCache.invalidate(basePath)
                        StorageCache.invalidate()
                        refreshIndexDir(basePath)
                        call.respondText("Uploaded: $name", status = HttpStatusCode.Created)
                    } catch (e: Exception) {
                        try { doc.delete() } catch (_: Exception) {}
                        call.respondText("Upload failed: ${e.message}",
                            status = HttpStatusCode.InternalServerError)
                    }
                }

                get("/api/download") {
                    val doc = root?.let { resolve(it, call.request.queryParameters["path"].orEmpty()) }
                    if (doc == null || !doc.isFile) {
                        call.respondText("Not Found", status = HttpStatusCode.NotFound); return@get
                    }
                    val total = doc.length()
                    val lastMod = doc.lastModified()
                    val etag = "\"${total}-${lastMod}\""
                    call.response.header("ETag", etag)
                    if (call.request.headers["If-None-Match"] == etag) {
                        call.respond(HttpStatusCode.NotModified); return@get
                    }
                    val ct = doc.type?.let { ContentType.parse(it) }
                        ?: ContentType.Application.OctetStream
                    call.response.header("Accept-Ranges", "bytes")

                    // ★Step2: Range 解析を正しく（bytes=0-0 / bytes=-500 / bytes=500- に対応）
                    val rangeHeader = call.request.headers["Range"]
                    val range = if (rangeHeader != null) parseRangeHeader(rangeHeader, total) else null
                    if (rangeHeader != null && range == null) {
                        call.response.header("Content-Range", "bytes */$total")
                        call.respondText("Range Not Satisfiable",
                            status = HttpStatusCode.RequestedRangeNotSatisfiable); return@get
                    }
                    if (range != null) {
                        val (start, end) = range
                        val length = end - start + 1
                        call.response.header("Content-Range", "bytes $start-$end/$total")
                        call.response.header("Content-Length", length.toString())
                        call.respondOutputStream(ct, HttpStatusCode.PartialContent) {
                            var written = 0L
                            // ★Step2: まず FileDescriptor で直接シークする（大きな動画の後方シーク対策）
                            val seekOk = try {
                                contentResolver.openFileDescriptor(doc.uri, "r")?.use { pfd ->
                                    java.io.FileInputStream(pfd.fileDescriptor).use { fis ->
                                        fis.channel.position(start)
                                        val buf = ByteArray(1024 * 1024)
                                        var remaining = length
                                        while (remaining > 0) {
                                            val want = minOf(buf.size.toLong(), remaining).toInt()
                                            val r = fis.read(buf, 0, want)
                                            if (r < 0) break
                                            write(buf, 0, r)
                                            written += r
                                            remaining -= r
                                        }
                                    }
                                } != null
                            } catch (e: Exception) { false }
                            // シークできない保存先では従来どおり先頭から読み捨てる（何も書いていない場合のみ）
                            if (!seekOk && written == 0L) {
                                contentResolver.openInputStream(doc.uri)?.use { input ->
                                    var skipped = 0L
                                    val skipBuf = ByteArray(128 * 1024)
                                    while (skipped < start) {
                                        val want = minOf(skipBuf.size.toLong(), start - skipped).toInt()
                                        val r = input.read(skipBuf, 0, want)
                                        if (r < 0) break
                                        skipped += r
                                    }
                                    var remaining = length
                                    val buf = ByteArray(1024 * 1024)
                                    while (remaining > 0) {
                                        val want = minOf(buf.size.toLong(), remaining).toInt()
                                        val r = input.read(buf, 0, want)
                                        if (r < 0) break
                                        write(buf, 0, r)
                                        remaining -= r
                                    }
                                }
                            }
                            flush()
                        }
                    } else {
                        call.respondOutputStream(ct) {
                            contentResolver.openInputStream(doc.uri)?.use { input ->
                                input.copyTo(this, 1024 * 1024)
                                flush()
                            }
                        }
                    }
                }

                post("/api/move") {
                    val r = root ?: run {
                        call.respondText("保存先未選択", status = HttpStatusCode.PreconditionFailed); return@post
                    }
                    val from = call.request.queryParameters["from"].orEmpty()
                    val to = call.request.queryParameters["to"].orEmpty()
                    if (from.isBlank() || to.isBlank() || from == to || to.startsWith("$from/")) {
                        call.respondText("Bad Request", status = HttpStatusCode.BadRequest); return@post
                    }
                    val src = resolve(r, from) ?: run {
                        call.respondText("Not Found", status = HttpStatusCode.NotFound); return@post
                    }
                    if (src.uri == r.uri) {
                        call.respondText("Bad Request", status = HttpStatusCode.BadRequest); return@post
                    }
                    val (dstParent, dstName) = split(r, to) ?: run {
                        call.respondText("Bad Request", status = HttpStatusCode.BadRequest); return@post
                    }
                    if (!dstParent.isDirectory || !safe(dstName) || dstParent.findFile(dstName) != null) {
                        call.respondText("Conflict", status = HttpStatusCode.Conflict); return@post
                    }
                    val ok = if (from.substringBeforeLast("/", "") == to.substringBeforeLast("/", ""))
                        src.renameTo(dstName)
                    else copyRecursive(src, dstParent, dstName).also { if (it) src.delete() }
                    if (ok) {
                        dirCache.invalidatePrefix(from.substringBeforeLast("/", ""))
                        dirCache.invalidatePrefix(to.substringBeforeLast("/", ""))
                        fileIndex.removeSubtree(from)
                        refreshIndexDir(from.substringBeforeLast("/", ""))
                        refreshIndexSubtree(to)
                    }
                    call.respondText("", status = if (ok) HttpStatusCode.OK else HttpStatusCode.InternalServerError)
                }

                post("/api/copy") {
                    val r = root ?: run {
                        call.respondText("保存先未選択", status = HttpStatusCode.PreconditionFailed); return@post
                    }
                    val from = call.request.queryParameters["from"].orEmpty()
                    val to = call.request.queryParameters["to"].orEmpty()
                    if (from.isBlank() || to.isBlank() || from == to || to.startsWith("$from/")) {
                        call.respondText("Bad Request", status = HttpStatusCode.BadRequest); return@post
                    }
                    val src = resolve(r, from) ?: run {
                        call.respondText("Not Found", status = HttpStatusCode.NotFound); return@post
                    }
                    val (dstParent, dstName) = split(r, to) ?: run {
                        call.respondText("Bad Request", status = HttpStatusCode.BadRequest); return@post
                    }
                    if (!dstParent.isDirectory || !safe(dstName) || dstParent.findFile(dstName) != null) {
                        call.respondText("Conflict", status = HttpStatusCode.Conflict); return@post
                    }
                    val ok = copyRecursive(src, dstParent, dstName)
                    if (ok) {
                        dirCache.invalidatePrefix(to.substringBeforeLast("/", ""))
                        refreshIndexSubtree(to)
                    }
                    call.respondText("", status = if (ok) HttpStatusCode.OK else HttpStatusCode.InternalServerError)
                }

                // ★Step8: ZIP解凍（同じフォルダ内に「zip名」フォルダを作って展開する）
                post("/api/extract") {
                    val r = root ?: run {
                        call.respondText("保存先未選択", status = HttpStatusCode.PreconditionFailed); return@post
                    }
                    val path = call.request.queryParameters["path"].orEmpty()
                    val doc = resolve(r, path) ?: run {
                        call.respondText("Not Found", status = HttpStatusCode.NotFound); return@post
                    }
                    val zipName = doc.name.orEmpty()
                    if (!doc.isFile || !zipName.lowercase().endsWith(".zip")) {
                        call.respondText("Bad Request", status = HttpStatusCode.BadRequest); return@post
                    }
                    val parentPath = path.substringBeforeLast("/", "")
                    val parent = resolve(r, parentPath) ?: run {
                        call.respondText("Not Found", status = HttpStatusCode.NotFound); return@post
                    }
                    // 展開先フォルダ名: zip名から拡張子を除いたもの（重なれば _1, _2...）
                    val baseName = zipName.dropLast(4).ifBlank { "archive" }
                    var destName = baseName
                    var n = 1
                    while (parent.findFile(destName) != null) {
                        destName = "${baseName}_$n"
                        n++
                    }
                    val destDir = parent.createDirectory(destName) ?: run {
                        call.respondText("Create Failed", status = HttpStatusCode.InternalServerError); return@post
                    }
                    var fileCount = 0
                    var ok = false
                    try {
                        val ins = this@NasServerService.contentResolver.openInputStream(doc.uri)
                        if (ins != null) {
                            ZipInputStream(BufferedInputStream(ins)).use { zis ->
                                val buf = ByteArray(64 * 1024)
                                var entry = zis.nextEntry
                                while (entry != null) {
                                    // Zip Slip 対策: 「..」や絶対パスの要素を取り除く
                                    val parts = entry.name.replace('\\', '/').split('/')
                                        .filter { it.isNotEmpty() && it != "." && it != ".." }
                                    if (parts.isNotEmpty()) {
                                        var dir = destDir
                                        var valid = true
                                        for (seg in parts.dropLast(1)) {
                                            val nextDir = dir.findFile(seg)?.takeIf { it.isDirectory }
                                                ?: dir.createDirectory(seg)
                                            if (nextDir == null) { valid = false; break }
                                            dir = nextDir
                                        }
                                        if (valid && !entry.isDirectory) {
                                            val leaf = parts.last()
                                            // 同名があれば消してから作り直す（SAF の自動リネーム回避）
                                            dir.findFile(leaf)?.takeIf { it.isFile }?.delete()
                                            val mime = android.webkit.MimeTypeMap.getSingleton()
                                                .getMimeTypeFromExtension(
                                                    leaf.substringAfterLast('.', "").lowercase())
                                                ?: "application/octet-stream"
                                            val outFile = dir.createFile(mime, leaf)
                                            if (outFile != null) {
                                                this@NasServerService.contentResolver.openOutputStream(outFile.uri, "w")?.use { os ->
                                                    var len = zis.read(buf)
                                                    while (len >= 0) {
                                                        os.write(buf, 0, len)
                                                        len = zis.read(buf)
                                                    }
                                                }
                                                fileCount++
                                            }
                                        }
                                    }
                                    zis.closeEntry()
                                    entry = zis.nextEntry
                                }
                            }
                            ok = true
                        }
                    } catch (e: Exception) {
                        FileLogger.log(this@NasServerService, TAG, "extract failed: ${e.message}")
                    }
                    if (ok) {
                        val destPath = if (parentPath.isBlank()) destName else "$parentPath/$destName"
                        dirCache.invalidatePrefix(parentPath)
                        refreshIndexDir(parentPath)
                        refreshIndexSubtree(destPath)
                        val escName = destName.replace("\\", "\\\\").replace("\"", "\\\"")
                        call.respondText(
                            "{\"folder\":\"$escName\",\"files\":$fileCount}",
                            ContentType.Application.Json
                        )
                    } else {
                        call.respondText("Extract Failed", status = HttpStatusCode.InternalServerError)
                    }
                }

                get("/api/search") {
                    val r = root ?: run {
                        call.respondText("保存先未選択", status = HttpStatusCode.PreconditionFailed); return@get
                    }
                    val q = call.request.queryParameters["q"].orEmpty()
                    val basePath = call.request.queryParameters["path"].orEmpty()
                    if (q.isBlank()) { call.respond(emptyList<Entry>()); return@get }
                    // ★Index: 構築済みなら DB 検索（ツリー全走査をしない）
                    if (fileIndex.isBuilt()) {
                        val results = fileIndex.search(q, basePath).map { it.toEntry() }
                        call.respond(filterSearchResults(call, results)); return@get
                    }
                    val base = resolve(r, basePath) ?: run {
                        call.respondText("Not Found", status = HttpStatusCode.NotFound); return@get
                    }
                    if (!base.isDirectory) {
                        call.respondText("Not a directory", status = HttpStatusCode.BadRequest); return@get
                    }
                    val out = mutableListOf<Entry>()
                    searchRecursive(base, q, basePath, out, limit = 500)
                    call.respond(filterSearchResults(call, out))
                }

                // ★Index: インデックス状態
                get("/api/index/status") {
                    call.respond(IndexStatus(
                        built = fileIndex.isBuilt(),
                        count = if (fileIndex.isBuilt()) fileIndex.count() else 0L,
                        lastFullScanAt = fileIndex.lastFullScanAt(),
                        scanning = IndexScanner.isScanning(),
                        lastScanCount = IndexScanner.lastScanCount,
                        lastError = IndexScanner.lastError
                    ))
                }

                // ★Index: 手動でフルスキャンを開始
                post("/api/index/rebuild") {
                    if (root == null) {
                        call.respondText("保存先未選択", status = HttpStatusCode.PreconditionFailed); return@post
                    }
                    IndexScanner.startFullScan(this@NasServerService)
                    call.respondText("", status = HttpStatusCode.Accepted)
                }

                get("/api/trash") {
                    val r = root ?: run { call.respond(emptyList<TrashItem>()); return@get }
                    call.respond(trash.list(r))
                }

                post("/api/trash/restore") {
                    val r = root ?: run {
                        call.respondText("Bad Request", status = HttpStatusCode.BadRequest); return@post
                    }
                    val name = call.request.queryParameters["name"].orEmpty()
                    val toPath = call.request.queryParameters["to"].orEmpty()
                    val dstDir = resolve(r, toPath) ?: run {
                        call.respondText("Not Found", status = HttpStatusCode.NotFound); return@post
                    }
                    if (!dstDir.isDirectory) {
                        call.respondText("Not a directory", status = HttpStatusCode.BadRequest); return@post
                    }
                    val ok = trash.restore(r, name, dstDir)
                    if (ok) { dirCache.invalidatePrefix(toPath); refreshIndexDir(toPath) }
                    call.respondText("", status = if (ok) HttpStatusCode.OK else HttpStatusCode.Conflict)
                }

                delete("/api/trash") {
                    val r = root ?: run {
                        call.respondText("Bad Request", status = HttpStatusCode.BadRequest); return@delete
                    }
                    val name = call.request.queryParameters["name"].orEmpty()
                    val ok = if (name == "ALL") trash.emptyAll(r) else trash.deletePermanently(r, name)
                    call.respondText("", status = if (ok) HttpStatusCode.OK else HttpStatusCode.InternalServerError)
                }

                get("/api/logs") {
                    val snapshot = synchronized(logsLock) { logs.toList().reversed() }
                    call.respond(snapshot)
                }

                delete("/api/logs") {
                    synchronized(logsLock) { logs.clear() }
                    call.respondText("", status = HttpStatusCode.OK)
                }

                get("/api/storage") {
                    call.respond(StorageCache.get(this@NasServerService, root))   // ★P6
                }
            }
        }

        server.start(wait = false)
        stopServer = { server.stop(500, 1000, TimeUnit.MILLISECONDS) }
        advertise()

        networkMonitor = NetworkMonitor(this) { connected ->
            if (connected && alive.get()) readvertise()
        }
        networkMonitor?.start()
        updateNotification()
    }

    private fun readvertise() {
        try { registration?.let { nsd?.unregisterService(it) } } catch (_: Exception) {}
        try { multicast?.release() } catch (_: Exception) {}
        advertise()
    }

    private fun advertise() {
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            multicast = wm.createMulticastLock("pocketnas-mdns").apply {
                setReferenceCounted(false); acquire()
            }
            nsd = getSystemService(NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = "PocketNAS"
                serviceType = "_pocketnas._tcp."
                port = 8080
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, c: Int) {
                    FileLogger.log(this@NasServerService, TAG, "NSD reg $c")
                }
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, c: Int) {}
            }
            registration = l
            nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            FileLogger.log(this, TAG, "NSD failed: ${e.message}")
        }
    }

    private fun DirIndexCache.EntryItem.toEntry() =
        Entry(name, path, directory, size, modified)

    private fun FileIndex.Item.toEntry() =
        Entry(name, path, directory, size, modified)

    /**
     * ★Step2: Range ヘッダー解析。単一レンジのみ対応。
     * "bytes=0-0" → 0..0 / "bytes=-500" → 末尾500バイト / "bytes=500-" → 500..末尾。
     * 解釈できない・範囲外なら null（呼び出し側で 416 を返す）。
     */
    private fun parseRangeHeader(header: String, total: Long): Pair<Long, Long>? {
        if (total <= 0) return null
        if (!header.startsWith("bytes=")) return null
        val spec = header.removePrefix("bytes=").split(",").first().trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startPart = spec.substring(0, dash).trim()
        val endPart = spec.substring(dash + 1).trim()
        if (startPart.isEmpty() && endPart.isEmpty()) return null
        return if (startPart.isEmpty()) {
            val suffix = endPart.toLongOrNull() ?: return null
            if (suffix <= 0) return null
            maxOf(0L, total - suffix) to (total - 1)
        } else {
            val start = startPart.toLongOrNull() ?: return null
            var end = if (endPart.isEmpty()) total - 1 else endPart.toLongOrNull() ?: return null
            if (end >= total) end = total - 1
            if (start < 0 || start > end || start >= total) null else start to end
        }
    }

    private fun safe(s: String) = s.isNotBlank() && s != "." && s != ".." &&
            !s.contains("/") && !s.contains("\\") && s.none { it.isISOControl() }

    // ★Step6: 認証ヘルパー
    private fun sessionTokenOf(call: ApplicationCall): String? =
        call.request.header("X-PN-Token")
            ?: call.request.cookies["pn_token"]
            ?: call.request.queryParameters["token"]

    private fun folderTokensOf(call: ApplicationCall): List<String> =
        listOfNotNull(
            call.request.header("X-PN-Folder-Token"),
            call.request.cookies["pn_ft"],
            call.request.queryParameters["ft"]
        ).flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }

    private fun filterSearchResults(call: ApplicationCall, results: List<Entry>): List<Entry> {
        if (AuthStore.lockedFolders(this).isEmpty()) return results
        val ftoks = folderTokensOf(call)
        return results.filter { entry ->
            val anc = AuthStore.strictLockedAncestorOf(this, entry.path)
            anc == null || AuthStore.folderAccessAllowed(ftoks, entry.path)
        }
    }

    private fun resolve(root: DocumentFile, raw: String): DocumentFile? {
        if (raw.isEmpty()) return root
        synchronized(findCache) { findCache[raw] }?.let { return it }
        var cur = root
        for (part in raw.split("/").filter { it.isNotBlank() }) {
            if (!safe(part)) return null
            cur = cur.findFile(part) ?: return null
        }
        synchronized(findCache) { findCache[raw] = cur }
        return cur
    }

    private fun split(root: DocumentFile, full: String): Pair<DocumentFile, String>? {
        val parts = full.split("/").filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val name = parts.last()
        if (!safe(name)) return null
        val parentPath = parts.dropLast(1).joinToString("/")
        val cur = resolve(root, parentPath) ?: return null
        return cur to name
    }

    private fun copyRecursive(src: DocumentFile, dstDir: DocumentFile, name: String): Boolean {
        return try {
            if (src.isDirectory) {
                val dst = dstDir.createDirectory(name) ?: return false
                for (c in src.listFiles()) {
                    val n = c.name ?: continue
                    if (!copyRecursive(c, dst, n)) return false
                }
                true
            } else {
                val mime = src.type ?: "application/octet-stream"
                val dst = dstDir.createFile(mime, name) ?: return false
                contentResolver.openInputStream(src.uri)?.use { i ->
                    contentResolver.openOutputStream(dst.uri, "w")?.use { o ->
                        i.copyTo(o, 1024 * 1024)
                    } ?: return false
                } ?: return false
                true
            }
        } catch (e: Exception) {
            FileLogger.log(this, TAG, "copy: ${e.message}"); false
        }
    }

    private fun searchRecursive(
        dir: DocumentFile, q: String, path: String,
        out: MutableList<Entry>, limit: Int
    ) {
        if (out.size >= limit) return
        for (f in dir.listFiles()) {
            if (out.size >= limit) return
            val n = f.name ?: continue
            if (n == TrashManager.TRASH_DIR_NAME) continue
            val cp = if (path.isEmpty()) n else "$path/$n"
            if (n.contains(q, ignoreCase = true)) {
                out.add(Entry(n, cp, f.isDirectory, f.length(), f.lastModified()))
            }
            if (f.isDirectory) searchRecursive(f, q, cp, out, limit)
        }
    }

    override fun onDestroy() {
        try { registration?.let { nsd?.unregisterService(it) } } catch (_: Exception) {}
        try { multicast?.release() } catch (_: Exception) {}
        try { networkMonitor?.stop() } catch (_: Exception) {}
        stopServer?.invoke()
        stopServer = null
        alive.set(false)
        running = false
        super.onDestroy()
    }
}