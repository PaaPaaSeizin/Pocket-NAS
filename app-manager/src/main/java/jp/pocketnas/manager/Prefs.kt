package jp.pocketnas.manager

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("manager", Context.MODE_PRIVATE)

    var host: String?
        get() = sp.getString("host", null)
        set(v) = sp.edit().putString("host", v).apply()

    var defaultSort: String
        get() = sp.getString("sort", "NAME_ASC") ?: "NAME_ASC"
        set(v) = sp.edit().putString("sort", v).apply()

    var useTrash: Boolean
        get() = sp.getBoolean("use_trash", true)
        set(v) = sp.edit().putBoolean("use_trash", v).apply()

    var useDarkTheme: Boolean
        get() = sp.getBoolean("dark_theme", false)
        set(v) = sp.edit().putBoolean("dark_theme", v).apply()

    var gridMode: Boolean
        get() = sp.getBoolean("grid_mode", false)
        set(v) = sp.edit().putBoolean("grid_mode", v).apply()

    // 既存の gridMode の下に追加
    var preload: Boolean
        get() = sp.getBoolean("preload", true)
        set(v) = sp.edit().putBoolean("preload", v).apply()

    // 状態ダイアログのキャッシュ（P8）
    var lastStatusJson: String?
        get() = sp.getString("last_status_json", null)
        set(v) = sp.edit().putString("last_status_json", v).apply()

    var lastStatusAt: Long
        get() = sp.getLong("last_status_at", 0L)
        set(v) = sp.edit().putLong("last_status_at", v).apply()

    // ★Step6: 認証（サーバーパスワードは自動ログイン用に端末内保存）
    var serverPassword: String?
        get() = sp.getString("server_pw", null)
        set(v) = sp.edit().putString("server_pw", v).apply()

    var sessionToken: String?
        get() = sp.getString("session_token", null)
        set(v) = sp.edit().putString("session_token", v).apply()

    var folderTokensJson: String?
        get() = sp.getString("folder_tokens", null)
        set(v) = sp.edit().putString("folder_tokens", v).apply()

    var appLockEnabled: Boolean
        get() = sp.getBoolean("app_lock", false)
        set(v) = sp.edit().putBoolean("app_lock", v).apply()

    fun clearAuth() {
        sp.edit().remove("host").apply()
    }

    fun favorites(): MutableSet<String> =
        sp.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()

    fun toggleFavorite(path: String): Boolean {
        val set = favorites()
        val added = set.add(path)
        if (!added) set.remove(path)
        sp.edit().putStringSet("favorites", set).apply()
        return added
    }

    fun isFavorite(path: String) = path in favorites()

    fun recentPaths(): List<String> {
        val raw = sp.getString("recent", "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.split("\n").filter { it.isNotBlank() }
    }

    fun pushRecent(path: String) {
        val list = recentPaths().toMutableList()
        list.remove(path)
        list.add(0, path)
        if (list.size > 20) list.subList(20, list.size).clear()
        sp.edit().putString("recent", list.joinToString("\n")).apply()
    }
}