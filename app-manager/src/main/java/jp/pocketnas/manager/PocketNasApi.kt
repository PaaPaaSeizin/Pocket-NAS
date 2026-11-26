package jp.pocketnas.manager

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class PocketNasApi(private val prefs: Prefs, context: Context) {

    private val cacheDir = File(context.cacheDir, "http_cache")
    private val cache = Cache(cacheDir, 512L * 1024 * 1024)

    private val http = OkHttpClient.Builder()
        .cache(cache)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())

    // ★Step6: 認証フック（MainActivity がダイアログを出す）
    var onAuthRequired: (() -> Unit)? = null
    var onFolderLocked: ((String) -> Unit)? = null

    private fun addAuthHeaders(b: Request.Builder) {
        prefs.sessionToken?.let { b.header("X-PN-Token", it) }
        val fts = folderTokenMap().values
        if (fts.isNotEmpty()) b.header("X-PN-Folder-Token", fts.joinToString(","))
    }

    private fun authQuery(): String {
        val sb = StringBuilder()
        prefs.sessionToken?.let { sb.append("&token=").append(enc(it)) }
        val fts = folderTokenMap().values
        if (fts.isNotEmpty()) sb.append("&ft=").append(enc(fts.joinToString(",")))
        return sb.toString()
    }

    fun folderTokenMap(): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        val json = prefs.folderTokensJson ?: return map
        runCatching {
            val o = JSONObject(json)
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = o.getString(k)
            }
        }
        return map
    }

    private fun saveFolderToken(path: String, token: String) {
        val map = folderTokenMap()
        map[path] = token
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        prefs.folderTokensJson = o.toString()
    }

    fun request(
        method: String,
        path: String,
        body: RequestBody? = null,
        done: (Int, String) -> Unit
    ) {
        requestInternal(method, path, body, false, done)
    }

    // ★Step6: 認証ヘッダー付与＋401時の自動ログイン＆1回だけリトライ＋フォルダロック通知
    private fun requestInternal(
        method: String,
        path: String,
        body: RequestBody?,
        retried: Boolean,
        done: (Int, String) -> Unit
    ) {
        val base = prefs.host ?: run {
            main.post { done(-1, "NAS未接続") }; return
        }
        val b = Request.Builder().url(base + path)
        addAuthHeaders(b)
        when (method) {
            "POST" -> b.post(body ?: ByteArray(0).toRequestBody(null))
            "DELETE" -> b.delete()
            else -> b.get()
        }
        Thread {
            try {
                val code: Int
                val result: String
                http.newCall(b.build()).execute().use { res ->
                    code = res.code
                    result = res.body?.string().orEmpty()
                }
                when {
                    code == 401 && !retried && !path.startsWith("/api/auth/") -> {
                        val saved = prefs.serverPassword
                        if (saved != null && loginBlocking(saved)) {
                            requestInternal(method, path, body, true, done)
                        } else {
                            main.post { onAuthRequired?.invoke(); done(code, result) }
                        }
                    }
                    code == 403 && result.startsWith("FOLDER_LOCKED:") -> {
                        val folder = result.removePrefix("FOLDER_LOCKED:").trim()
                        main.post { onFolderLocked?.invoke(folder); done(code, result) }
                    }
                    else -> main.post { done(code, result) }
                }
            } catch (e: Exception) {
                main.post { done(-1, e.message ?: "通信エラー") }
            }
        }.start()
    }

    fun listFiles(path: String, cb: (List<FileItem>) -> Unit) {
        request("GET", "/api/files?path=${enc(path)}") { code, body ->
            if (code != 200) { cb(emptyList()); return@request }
            cb(parseFileArray(body))
        }
    }

    fun searchFiles(basePath: String, q: String, cb: (List<FileItem>) -> Unit) {
        request("GET", "/api/search?path=${enc(basePath)}&q=${enc(q)}") { code, body ->
            if (code != 200) { cb(emptyList()); return@request }
            cb(parseFileArray(body))
        }
    }

    private fun parseFileArray(body: String): List<FileItem> = runCatching {
        val arr = JSONArray(body)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            FileItem(
                name = o.getString("name"),
                path = o.getString("path"),
                directory = o.getBoolean("directory"),
                size = o.optLong("size", 0),
                modified = o.optLong("modified", 0)
            )
        }
    }.getOrDefault(emptyList())

    fun listTrash(cb: (List<TrashItem>) -> Unit) {
        request("GET", "/api/trash") { code, body ->
            if (code != 200) { cb(emptyList()); return@request }
            cb(runCatching {
                val arr = JSONArray(body)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    TrashItem(
                        name = o.getString("name"),
                        original = o.optString("original", o.getString("name")),
                        trashedAt = o.optLong("trashedAt", 0),
                        size = o.optLong("size", 0),
                        directory = o.optBoolean("directory", false)
                    )
                }
            }.getOrDefault(emptyList()))
        }
    }

    fun download(
        path: String,
        outFile: File,
        onProgress: (received: Long, total: Long, speedBps: Long) -> Unit = { _, _, _ -> },
        cb: (Boolean, String?) -> Unit
    ) {
        val base = prefs.host ?: return cb(false, "未接続")
        Thread {
            try {
                val rb = Request.Builder()
                    .url("$base/api/download?path=${enc(path)}")
                addAuthHeaders(rb)
                val req = rb.get().build()
                http.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                    val total = res.body?.contentLength() ?: -1L
                    var received = 0L
                    var lastUpdate = 0L
                    var lastBytes = 0L
                    var currentSpeed = 0L

                    res.body?.byteStream()?.use { input ->
                        outFile.outputStream().buffered().use { out ->
                            val buf = ByteArray(1024 * 1024)
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); received += n
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate > 200) {
                                    val timeDiff = now - lastUpdate
                                    if (timeDiff > 0) {
                                        currentSpeed = (received - lastBytes) * 1000 / timeDiff
                                    }
                                    lastUpdate = now
                                    lastBytes = received
                                    main.post { onProgress(received, total, currentSpeed) }
                                }
                            }
                        }
                    }
                    main.post { onProgress(received, total, currentSpeed) }
                    main.post { cb(true, null) }
                }
            } catch (e: Exception) {
                main.post { cb(false, e.message) }
            }
        }.start()
    }

    fun upload(
        file: File,
        path: String,
        name: String,
        onProgress: (sent: Long, total: Long, speedBps: Long) -> Unit = { _, _, _ -> },
        cb: (Boolean, String?) -> Unit
    ) {
        val base = prefs.host ?: return cb(false, "未接続")
        Thread {
            try {
                val body = file.asRequestBody("application/octet-stream".toMediaType())
                val progressBody = ProgressRequestBody(body, file.length(), onProgress)
                val rb = Request.Builder()
                    .url("$base/api/upload?path=${enc(path)}&name=${enc(name)}")
                addAuthHeaders(rb)
                val req = rb.post(progressBody).build()
                http.newCall(req).execute().use { res ->
                    val err = if (res.isSuccessful) null
                    else "HTTP ${res.code}: ${res.body?.string().orEmpty()}"
                    main.post { cb(res.isSuccessful, err) }
                }
            } catch (e: Exception) {
                main.post { cb(false, e.message) }
            }
        }.start()
    }

    fun downloadUrl(path: String): String? {
        val base = prefs.host ?: return null
        return "$base/api/download?path=${enc(path)}${authQuery()}"
    }

    fun fetchStatus(cb: (JSONObject?) -> Unit) {
        request("GET", "/api/status") { code, body ->
            cb(if (code == 200) runCatching { JSONObject(body) }.getOrNull() else null)
        }
    }

    fun fetchStorage(cb: (JSONObject?) -> Unit) {
        request("GET", "/api/storage") { code, body ->
            cb(if (code == 200) runCatching { JSONObject(body) }.getOrNull() else null)
        }
    }

    // ★Index: インデックス状態取得
    fun fetchIndexStatus(cb: (JSONObject?) -> Unit) {
        request("GET", "/api/index/status") { code, body ->
            cb(if (code == 200) runCatching { JSONObject(body) }.getOrNull() else null)
        }
    }

    // ★Index: フルスキャンを手動開始
    fun rebuildIndex(cb: (Boolean) -> Unit) {
        request("POST", "/api/index/rebuild") { code, _ -> cb(code == 202) }
    }

    // ★Step6: ログイン（同期実行・自動ログイン用）
    private fun loginBlocking(password: String): Boolean {
        val base = prefs.host ?: return false
        return try {
            val json = JSONObject().put("password", password).toString()
            val req = Request.Builder()
                .url(base + "/api/auth/login")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    false
                } else {
                    val token = runCatching {
                        JSONObject(res.body?.string().orEmpty()).optString("token", "")
                    }.getOrDefault("")
                    if (token.isEmpty()) false else {
                        prefs.sessionToken = token
                        true
                    }
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    fun login(password: String, save: Boolean, cb: (Boolean) -> Unit) {
        Thread {
            val ok = loginBlocking(password)
            if (ok && save) prefs.serverPassword = password
            main.post { cb(ok) }
        }.start()
    }

    fun fetchAuthStatus(cb: (Boolean, Boolean, List<String>) -> Unit) {
        request("GET", "/api/auth/status") { code, body ->
            if (code != 200) { cb(false, false, emptyList()); return@request }
            val o = runCatching { JSONObject(body) }.getOrNull()
            if (o == null) { cb(false, false, emptyList()); return@request }
            val arr = o.optJSONArray("lockedFolders")
            val locked = mutableListOf<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) locked.add(arr.optString(i))
            }
            cb(true, o.optBoolean("serverPasswordSet", false), locked)
        }
    }

    fun setServerPassword(password: String, cb: (Boolean) -> Unit) {
        val json = JSONObject().put("password", password).toString()
        request(
            "POST", "/api/auth/set-password",
            json.toRequestBody("application/json".toMediaType())
        ) { code, _ -> cb(code == 200) }
    }

    fun setFolderPassword(path: String, password: String, cb: (Boolean) -> Unit) {
        val json = JSONObject().put("path", path).put("password", password).toString()
        request(
            "POST", "/api/auth/folder/set",
            json.toRequestBody("application/json".toMediaType())
        ) { code, _ -> cb(code == 200) }
    }

    fun unlockFolder(path: String, password: String, cb: (Boolean) -> Unit) {
        val json = JSONObject().put("path", path).put("password", password).toString()
        request(
            "POST", "/api/auth/folder/unlock",
            json.toRequestBody("application/json".toMediaType())
        ) { code, body ->
            if (code == 200) {
                val token = runCatching {
                    JSONObject(body).optString("token", "")
                }.getOrDefault("")
                if (token.isNotEmpty()) saveFolderToken(path, token)
            }
            cb(code == 200)
        }
    }

    fun fetchHealthDetail(cb: (JSONObject?) -> Unit) {
        request("GET", "/api/health/detail") { code, body ->
            cb(if (code == 200) runCatching { JSONObject(body) }.getOrNull() else null)
        }
    }
    // ★P1: サムネイルURL生成
    fun thumbnailUrl(path: String, size: Int = 200): String? {
        val base = prefs.host ?: return null
        return "$base/api/thumbnail?size=$size&path=${enc(path)}${authQuery()}"
    }

    // ★P3: プリロード（バックグラウンドで画像を先読み）
    fun preloadImages(paths: List<String>, onDone: (() -> Unit)? = null) {
        val base = prefs.host ?: return
        if (!prefs.preload) return
        Thread {
            try {
                paths.forEach { path ->
                    val rb = Request.Builder()
                        .url("$base/api/thumbnail?size=200&path=${enc(path)}")
                    addAuthHeaders(rb)
                    val req = rb.get().build()
                    runCatching { http.newCall(req).execute().use { it.body?.bytes() } }
                }
            } finally {
                if (onDone != null) main.post(onDone)
            }
        }.start()
    }

    fun rawGet(url: String): ByteArray? {
        return try {
            val rb = Request.Builder().url(url)
            addAuthHeaders(rb)
            val req = rb.get().build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) null else res.body?.bytes()
            }
        } catch (_: Exception) { null }
    }

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private class ProgressRequestBody(
        private val delegate: RequestBody,
        private val total: Long,
        private val cb: (Long, Long, Long) -> Unit
    ) : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()

        override fun writeTo(sink: okio.BufferedSink) {
            var lastUpdate = 0L
            var lastBytes = 0L
            var currentSpeed = 0L

            val buffered = object : okio.ForwardingSink(sink) {
                var written = 0L
                override fun write(source: okio.Buffer, byteCount: Long) {
                    written += byteCount
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 200) {
                        val timeDiff = now - lastUpdate
                        if (timeDiff > 0) {
                            currentSpeed = (written - lastBytes) * 1000 / timeDiff
                        }
                        lastUpdate = now
                        lastBytes = written
                        cb(written, total, currentSpeed)
                    }
                    super.write(source, byteCount)
                }
            }.buffer()
            delegate.writeTo(buffered)
            buffered.flush()
            cb(total, total, currentSpeed)
        }
    }
}