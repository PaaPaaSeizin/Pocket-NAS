package jp.pocketnas.server

import android.content.Context
import android.content.SharedPreferences

class ServerPrefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("pocketnas", Context.MODE_PRIVATE)

    var treeUri: String?
        get() = sp.getString("tree", null)
        set(v) = sp.edit().putString("tree", v).apply()
}