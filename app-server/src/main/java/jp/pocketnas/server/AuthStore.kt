package jp.pocketnas.server

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ★Step6: アクセスパスワード（サーバー全体＋フォルダごと）の保存とトークン管理
 *
 * - パスワードは平文で保存せず、ソルト付き SHA-256 ハッシュで SharedPreferences に保存する
 * - ログイン成功でセッショントークン（7日有効）を発行。Web は Cookie、Manager はヘッダーで送る
 * - フォルダ解除はフォルダ専用トークンを発行し、そのフォルダ配下へのアクセスだけを許可する
 * - パスワード未設定のときは従来どおり無認証（機能オフ）
 */
object AuthStore {
    private const val PREFS = "pocketnas_auth"
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    private val sessions = ConcurrentHashMap<String, Long>()
    private val folderTokens = ConcurrentHashMap<String, Pair<Long, String>>()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- ハッシュ ----------
    private fun hash(password: String, salt: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        return Base64.encodeToString(
            md.digest(password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP
        )
    }

    private fun encodeStored(password: String): String {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return Base64.encodeToString(salt, Base64.NO_WRAP) + ":" + hash(password, salt)
    }

    private fun verify(password: String, stored: String?): Boolean {
        if (stored == null) return false
        val parts = stored.split(":")
        if (parts.size != 2) return false
        val salt = try {
            Base64.decode(parts[0], Base64.NO_WRAP)
        } catch (e: Exception) {
            return false
        }
        return hash(password, salt) == parts[1]
    }

    // ---------- サーバー全体のパスワード ----------
    fun isServerPasswordSet(context: Context): Boolean =
        prefs(context).getString("server_pw", null) != null

    fun checkServerPassword(context: Context, password: String): Boolean =
        verify(password, prefs(context).getString("server_pw", null))

    fun setServerPassword(context: Context, password: String?) {
        val e = prefs(context).edit()
        if (password.isNullOrEmpty()) {
            e.remove("server_pw")
        } else {
            e.putString("server_pw", encodeStored(password))
        }
        e.apply()
        sessions.clear()
    }

    // ---------- フォルダごとのパスワード ----------
    // 保存形式: StringSet の各要素が Base64(path) + "|" + salt:hash
    private fun readFolderEntries(context: Context): MutableMap<String, String> {
        val set = prefs(context).getStringSet("folder_pw", emptySet()) ?: emptySet()
        val map = mutableMapOf<String, String>()
        for (entry in set) {
            val idx = entry.indexOf('|')
            if (idx <= 0) continue
            val path = try {
                String(Base64.decode(entry.substring(0, idx), Base64.NO_WRAP), Charsets.UTF_8)
            } catch (e: Exception) {
                continue
            }
            map[path] = entry.substring(idx + 1)
        }
        return map
    }

    private fun writeFolderEntries(context: Context, map: Map<String, String>) {
        val set = map.map { (path, stored) ->
            Base64.encodeToString(path.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) + "|" + stored
        }.toSet()
        prefs(context).edit().putStringSet("folder_pw", set).apply()
    }

    fun lockedFolders(context: Context): List<String> =
        readFolderEntries(context).keys.sorted()

    fun checkFolderPassword(context: Context, path: String, password: String): Boolean =
        verify(password, readFolderEntries(context)[path])

    fun setFolderPassword(context: Context, path: String, password: String?) {
        val map = readFolderEntries(context)
        if (password.isNullOrEmpty()) {
            map.remove(path)
        } else {
            map[path] = encodeStored(password)
        }
        writeFolderEntries(context, map)
        // そのフォルダの解除トークンは無効化する
        folderTokens.keys
            .filter { folderTokens[it]?.second == path }
            .forEach { folderTokens.remove(it) }
    }

    /** target がロックフォルダ自身か、その配下なら、そのフォルダを返す（最長一致） */
    fun lockedAncestorOf(context: Context, target: String): String? =
        lockedFolders(context)
            .filter { target == it || target.startsWith("$it/") }
            .maxByOrNull { it.length }

    /** target がロックフォルダの「中身」（厳密な配下）なら、そのフォルダを返す。検索結果の除外用 */
    fun strictLockedAncestorOf(context: Context, target: String): String? =
        lockedFolders(context)
            .filter { target.startsWith("$it/") }
            .maxByOrNull { it.length }

    // ---------- トークン ----------
    private fun newToken(): String =
        UUID.randomUUID().toString() + UUID.randomUUID().toString()

    fun createSession(): String {
        val token = newToken()
        sessions[token] = System.currentTimeMillis() + MAX_AGE_MS
        return token
    }

    fun validSession(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val exp = sessions[token] ?: return false
        if (exp < System.currentTimeMillis()) {
            sessions.remove(token)
            return false
        }
        return true
    }

    fun revokeSession(token: String?) {
        if (!token.isNullOrEmpty()) sessions.remove(token)
    }

    fun createFolderToken(folder: String): String {
        val token = newToken()
        folderTokens[token] = Pair(System.currentTimeMillis() + MAX_AGE_MS, folder)
        return token
    }

    /** 提示されたフォルダトークン群で target へアクセスできるか */
    fun folderAccessAllowed(tokens: List<String>, target: String): Boolean {
        val now = System.currentTimeMillis()
        for (token in tokens) {
            val pair = folderTokens[token] ?: continue
            if (pair.first < now) {
                folderTokens.remove(token)
                continue
            }
            val folder = pair.second
            if (target == folder || target.startsWith("$folder/")) return true
        }
        return false
    }
}
