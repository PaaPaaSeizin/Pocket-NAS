package jp.pocketnas.server

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

/**
 * ★Index-1: 永続ファイルインデックス（SQLite）
 *
 * 目的:
 *  - /api/files（フォルダ一覧）と /api/search（全体検索）を、SAF の都度スキャンではなく
 *    DB 参照で返すことで高速化する。
 *  - modified は「ミリ秒（epoch millis）」で統一する。DocumentFile.lastModified() の戻り値そのまま。
 *
 * 設計メモ:
 *  - Room ではなく標準 SQLiteOpenHelper を使っている。依存追加なしで導入でき、
 *    このクラスの中だけに閉じるので、後から Room へ載せ替えることもできる。
 *  - path を主キーにする。parent は「親フォルダの path」。ルート直下の parent は ""。
 *  - 全件入れ替えは replaceAll() でトランザクション一括実行する。
 *  - API 経由の変更は refreshDirectory() / removeSubtree() で差分追随する。
 *    外部アプリ等による変更は、定期フルスキャン（IndexWorker）で最終的に整合する。
 */
class FileIndex(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    data class Item(
        val name: String,
        val path: String,
        val parent: String,
        val directory: Boolean,
        val size: Long,
        val modified: Long // epoch millis
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE entries (
                path TEXT PRIMARY KEY,
                parent TEXT NOT NULL,
                name TEXT NOT NULL,
                name_lower TEXT NOT NULL,
                directory INTEGER NOT NULL,
                size INTEGER NOT NULL,
                modified INTEGER NOT NULL,
                ext TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_entries_parent ON entries(parent)")
        db.execSQL("CREATE INDEX idx_entries_name_lower ON entries(name_lower)")
        db.execSQL(
            """
            CREATE TABLE meta (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // スキーマを変えたらここで移行する。今は作り直しで十分（インデックスは再生成できる情報のみ）
        db.execSQL("DROP TABLE IF EXISTS entries")
        db.execSQL("DROP TABLE IF EXISTS meta")
        onCreate(db)
    }

    // ---------- 参照 ----------

    /** フルスキャンが 1 回でも完了していれば true */
    fun isBuilt(): Boolean = getMeta(KEY_LAST_FULL_SCAN) != null

    fun lastFullScanAt(): Long = getMeta(KEY_LAST_FULL_SCAN)?.toLongOrNull() ?: 0L

    fun count(): Long {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM entries", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    /** 指定フォルダの直下だけを返す（一覧表示用）。並びは従来どおり フォルダ優先＋名前順 */
    fun children(parent: String): List<Item> {
        val out = ArrayList<Item>()
        readableDatabase.query(
            "entries",
            COLUMNS,
            "parent = ?",
            arrayOf(parent),
            null, null,
            "directory DESC, name_lower ASC"
        ).use { c ->
            while (c.moveToNext()) out.add(c.toItem())
        }
        return out
    }

    /** path のエントリそのものを返す（無ければ null） */
    fun find(path: String): Item? {
        if (path.isEmpty()) return null
        readableDatabase.query(
            "entries", COLUMNS, "path = ?", arrayOf(path), null, null, null
        ).use { c -> return if (c.moveToFirst()) c.toItem() else null }
    }

    fun contains(path: String): Boolean {
        if (path.isEmpty()) return true
        readableDatabase.rawQuery("SELECT 1 FROM entries WHERE path = ? LIMIT 1", arrayOf(path)).use { c ->
            return c.moveToFirst()
        }
    }

    /** 名前の部分一致検索。basePath 配下に限定できる。 */
    fun search(query: String, basePath: String, limit: Int = 500): List<Item> {
        val q = "%${escapeLike(query.lowercase(Locale.ROOT))}%"
        val (selection, args) = if (basePath.isEmpty()) {
            "name_lower LIKE ? ESCAPE '\\'" to arrayOf(q)
        } else {
            "(path = ? OR path LIKE ? ESCAPE '\\') AND name_lower LIKE ? ESCAPE '\\'" to
                arrayOf(basePath, escapeLike(basePath) + "/%", q)
        }
        val out = ArrayList<Item>()
        readableDatabase.query(
            "entries", COLUMNS, selection, args, null, null,
            "directory DESC, name_lower ASC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) out.add(c.toItem())
        }
        return out
    }

    // ---------- 更新 ----------

    /** フルスキャン結果で全件入れ替える */
    fun replaceAll(items: List<Item>, scannedAt: Long) = synchronized(WRITE_LOCK) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("entries", null, null)
            for (item in items) db.insert("entries", null, item.toValues())
            putMeta(db, KEY_LAST_FULL_SCAN, scannedAt.toString())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 1 フォルダ分の直下エントリだけを差し替える（API 経由の変更への追随用） */
    fun replaceChildren(parent: String, items: List<Item>) = synchronized(WRITE_LOCK) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("entries", "parent = ?", arrayOf(parent))
            for (item in items) db.insert("entries", null, item.toValues())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** path 自身と、その配下をすべて消す（削除・移動元用） */
    fun removeSubtree(path: String) {
        if (path.isEmpty()) return
        synchronized(WRITE_LOCK) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("entries", "path = ?", arrayOf(path))
            db.delete("entries", "path LIKE ? ESCAPE '\\'", arrayOf(escapeLike(path) + "/%"))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        }
    }

    /** path 配下を丸ごと差し替える（移動・コピー先のサブツリー登録用） */
    fun replaceSubtree(rootPath: String, items: List<Item>) = synchronized(WRITE_LOCK) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("entries", "path = ?", arrayOf(rootPath))
            db.delete("entries", "path LIKE ? ESCAPE '\\'", arrayOf(escapeLike(rootPath) + "/%"))
            for (item in items) db.insert("entries", null, item.toValues())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- meta ----------

    private fun getMeta(key: String): String? {
        readableDatabase.query(
            "meta", arrayOf("value"), "key = ?", arrayOf(key), null, null, null
        ).use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    private fun putMeta(db: SQLiteDatabase, key: String, value: String) {
        val v = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        db.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------- 変換 ----------

    private fun Item.toValues(): ContentValues = ContentValues().apply {
        put("path", path)
        put("parent", parent)
        put("name", name)
        put("name_lower", name.lowercase(Locale.ROOT))
        put("directory", if (directory) 1 else 0)
        put("size", size)
        put("modified", modified)
        put("ext", if (directory) "" else name.substringAfterLast('.', "").lowercase(Locale.ROOT))
    }

    private fun android.database.Cursor.toItem(): Item = Item(
        name = getString(0),
        path = getString(1),
        parent = getString(2),
        directory = getInt(3) != 0,
        size = getLong(4),
        modified = getLong(5)
    )

    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    companion object {
        // FileIndex は複数インスタンス作られるため、書き込み排他はクラス共有ロックで行う
        private val WRITE_LOCK = Any()
        private const val DB_NAME = "pocketnas_index.db"
        private const val DB_VERSION = 1
        private const val KEY_LAST_FULL_SCAN = "last_full_scan"
        private val COLUMNS = arrayOf("name", "path", "parent", "directory", "size", "modified")
    }
}
