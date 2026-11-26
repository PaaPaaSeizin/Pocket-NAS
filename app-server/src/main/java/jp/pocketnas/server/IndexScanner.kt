package jp.pocketnas.server

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ★Index-2: 全件スキャナ
 *
 * SAF ツリーを再帰走査して FileIndex を作る。
 * - 走査はバックグラウンドの単一スレッドで実行し、二重起動しない。
 * - 深さ優先をスタックで実装（再帰のスタックオーバーフロー回避）。
 * - ゴミ箱フォルダ（__trash__）は対象外。
 * - 走査中の一覧・検索は、従来の SAF スキャン側にフォールバックする想定
 *   （FileIndex.isBuilt() が true になるのは初回スキャン完了後）。
 */
object IndexScanner {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pocketnas-index").apply { isDaemon = true }
    }
    private val scanning = AtomicBoolean(false)

    @Volatile var lastScanStartedAt: Long = 0L
        private set
    @Volatile var lastScanFinishedAt: Long = 0L
        private set
    @Volatile var lastScanCount: Int = 0
        private set
    @Volatile var lastError: String? = null
        private set

    fun isScanning(): Boolean = scanning.get()

    /** 非同期でフルスキャンを開始する。実行中なら何もしない。 */
    fun startFullScan(context: Context, onDone: ((Boolean) -> Unit)? = null) {
        if (!scanning.compareAndSet(false, true)) {
            onDone?.invoke(false)
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            var ok = false
            try {
                ok = runFullScan(appContext)
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                FileLogger.log(appContext, TAG, "index scan failed: ${e.message}")
            } finally {
                scanning.set(false)
                onDone?.invoke(ok)
            }
        }
    }

    private val scanLock = Any()

    /** 同期版。WorkManager の Worker から呼ぶ。非同期版と同時には走らない。 */
    fun runFullScan(context: Context): Boolean = synchronized(scanLock) { runFullScanLocked(context) }

    private fun runFullScanLocked(context: Context): Boolean {
        val prefs = ServerPrefs(context)
        val treeUri = prefs.treeUri ?: return false
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return false

        lastScanStartedAt = System.currentTimeMillis()
        lastError = null

        val items = ArrayList<FileIndex.Item>(4096)

        // (DocumentFile, path) のスタックで深さ優先
        val stack = ArrayDeque<Pair<DocumentFile, String>>()
        stack.addLast(root to "")

        while (stack.isNotEmpty()) {
            val (dir, dirPath) = stack.removeLast()
            val children = try {
                dir.listFiles()
            } catch (_: Exception) {
                continue
            }
            for (child in children) {
                val name = child.name ?: continue
                if (name == TrashManager.TRASH_DIR_NAME) continue
                val path = if (dirPath.isEmpty()) name else "$dirPath/$name"
                val isDir = child.isDirectory
                items.add(
                    FileIndex.Item(
                        name = name,
                        path = path,
                        parent = dirPath,
                        directory = isDir,
                        size = if (isDir) 0L else runCatching { child.length() }.getOrDefault(0L),
                        modified = runCatching { child.lastModified() }.getOrDefault(0L)
                    )
                )
                if (isDir) stack.addLast(child to path)
            }
        }

        FileIndex(context).replaceAll(items, System.currentTimeMillis())
        lastScanCount = items.size
        lastScanFinishedAt = System.currentTimeMillis()
        FileLogger.log(context, TAG, "index scan done: ${items.size} entries")
        return true
    }

    /**
     * 1 フォルダ（直下のみ）を SAF から読み直してインデックスに反映する。
     * API 経由の作成・アップロード・削除などのあとに呼ぶ差分更新。
     * フルスキャン未完了の場合は何もしない（初回スキャンが全体を拾うため）。
     */
    fun refreshDirectory(context: Context, dirPath: String, dir: DocumentFile) {
        val index = FileIndex(context)
        if (!index.isBuilt()) return
        executor.execute {
            try {
                val items = dir.listFiles()
                    .asSequence()
                    .filter { it.name != null && it.name != TrashManager.TRASH_DIR_NAME }
                    .map { f ->
                        val p = if (dirPath.isEmpty()) f.name!! else "$dirPath/${f.name}"
                        FileIndex.Item(
                            name = f.name!!,
                            path = p,
                            parent = dirPath,
                            directory = f.isDirectory,
                            size = if (f.isDirectory) 0L else runCatching { f.length() }.getOrDefault(0L),
                            modified = runCatching { f.lastModified() }.getOrDefault(0L)
                        )
                    }
                    .toList()
                index.replaceChildren(dirPath, items)
            } catch (e: Exception) {
                FileLogger.log(context, TAG, "index refresh failed ($dirPath): ${e.message}")
            }
        }
    }

    /**
     * フォルダ配下を丸ごと読み直してインデックスに反映する。
     * 移動・コピーでフォルダが増えたときに使う。
     */
    fun refreshSubtree(context: Context, dirPath: String, dir: DocumentFile) {
        val index = FileIndex(context)
        if (!index.isBuilt()) return
        executor.execute {
            try {
                val items = ArrayList<FileIndex.Item>()
                val parent = dirPath.substringBeforeLast("/", "")
                val rootName = dir.name ?: return@execute
                items.add(FileIndex.Item(rootName, dirPath, parent, true, 0L,
                    runCatching { dir.lastModified() }.getOrDefault(0L)))
                val stack = ArrayDeque<Pair<DocumentFile, String>>()
                stack.addLast(dir to dirPath)
                while (stack.isNotEmpty()) {
                    val (d, dPath) = stack.removeLast()
                    for (child in d.listFiles()) {
                        val name = child.name ?: continue
                        if (name == TrashManager.TRASH_DIR_NAME) continue
                        val path = "$dPath/$name"
                        val isDir = child.isDirectory
                        items.add(FileIndex.Item(name, path, dPath, isDir,
                            if (isDir) 0L else runCatching { child.length() }.getOrDefault(0L),
                            runCatching { child.lastModified() }.getOrDefault(0L)))
                        if (isDir) stack.addLast(child to path)
                    }
                }
                index.replaceSubtree(dirPath, items)
            } catch (e: Exception) {
                FileLogger.log(context, TAG, "index refreshSubtree failed ($dirPath): ${e.message}")
            }
        }
    }

    private const val TAG = "PocketNAS-Index"
}
