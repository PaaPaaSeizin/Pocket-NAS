package jp.pocketnas.server

import android.content.Context
import androidx.documentfile.provider.DocumentFile

object StorageUtils {

    fun name(context: Context, tree: DocumentFile?): String {
        if (tree == null) return "未選択"
        // ★ tree.name はSAFの重いスキャンを引き起こすため、URIから直接抽出する
        val path = tree.uri.lastPathSegment ?: ""
        // "primary:Download/PocketNAS" のような形式から最後の部分を取り出す
        return path.substringAfterLast('/').substringAfterLast(':').ifBlank { "不明" }
    }

    fun freeSpaceOf(context: Context, tree: DocumentFile?): Long {
        if (tree == null) return -1
        return runCatching {
            val pfd = context.contentResolver.openFileDescriptor(tree.uri, "r")
                ?: return -1
            pfd.use {
                val s = android.system.Os.fstatvfs(it.fileDescriptor)
                s.f_bavail * s.f_bsize
            }
        }.getOrDefault(-1)
    }

    fun totalSpaceOf(context: Context, tree: DocumentFile?): Long {
        if (tree == null) return -1
        return runCatching {
            val pfd = context.contentResolver.openFileDescriptor(tree.uri, "r")
                ?: return -1
            pfd.use {
                val s = android.system.Os.fstatvfs(it.fileDescriptor)
                s.f_blocks * s.f_bsize
            }
        }.getOrDefault(-1)
    }

    // インターフェース名とIPアドレスのペアを返す
    fun localIps(): List<Pair<String, String>> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                    .mapNotNull { addr ->
                        val ip = addr.hostAddress ?: return@mapNotNull null
                        nif.name to ip
                    }
            }
            .sortedWith(compareBy({ !it.first.startsWith("wlan") }, { it.first }))
    } catch (_: Exception) { emptyList() }

    fun formatBytes(v: Long): String = when {
        v < 0 -> "-"
        v >= 1L shl 30 -> "%.2f GB".format(v / (1024.0 * 1024 * 1024))
        v >= 1L shl 20 -> "%.1f MB".format(v / (1024.0 * 1024))
        v >= 1L shl 10 -> "%.1f KB".format(v / 1024.0)
        else -> "$v B"
    }
}